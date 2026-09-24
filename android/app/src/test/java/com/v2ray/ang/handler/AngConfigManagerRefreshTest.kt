package com.v2ray.ang.handler

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ОБНОВЛЕНИЕ ПОДПИСКИ ЦЕЛИКОМ, КАК ЕГО ВИДИТ ХРАНИЛИЩЕ: что остаётся на месте, а что нет.
 *
 * [AngConfigManager.parseConfigViaSub] - это всё, что обновление делает с ответом провайдера, когда
 * он уже скачан; и ручное «Обновить», и фоновое по расписанию, и импорт аккаунта приходят сюда. Тест
 * гоняет его по-настоящему, от разбора ответа до записей в MMKV, подменив только само MMKV: вместо
 * нативного хранилища - словарь на каждый ID ([InMemoryStores]).
 *
 * Эмулятора здесь нет, поэтому именно этот тест - доказательство того, о чём просил владелец:
 * обновление, в котором ничего не поменялось, оставляет серверам их guid, пинг и отметку выбора и
 * не трогает счётчик изменений - значит, экран не перечитывает список и не предлагает
 * переподключиться к тому же серверу.
 */
class AngConfigManagerRefreshTest {

    private val stores = InMemoryStores()

    private val sub = "sub-1"

    @Before
    fun setUp() = stores.start()

    @After
    fun tearDown() = stores.stop()

    // ------------------------------------------------------------------ ссылки

    private fun trojan(name: String, host: String, port: Int = 443) =
        "trojan://pass-$host@$host.example.com:$port?security=tls&sni=$host.example.com&type=tcp#$name"

    private val threeLinks = listOf(
        trojan("Germany", "de"),
        trojan("Netherlands", "nl"),
        trojan("Finland", "fi"),
    ).joinToString("\n")

    private fun serverNames(): List<String> =
        MmkvManager.decodeServerList(sub).map { MmkvManager.decodeServerConfig(it)!!.remarks }

    /** Первое добавление: новые guid в порядке провайдера, отмечен первый сервер. */
    @Test
    fun theFirstImportSelectsTheFirstServerOfTheProvider() {
        assertEquals(3, AngConfigManager.parseConfigViaSub(threeLinks, sub, false))
        assertEquals(listOf("Germany", "Netherlands", "Finland"), serverNames())
        assertEquals(MmkvManager.decodeServerList(sub)[0], MmkvManager.getSelectServer())
    }

    /**
     * Обновление без изменений: те же guid, тот же пинг, та же отметка, то же время добавления - и
     * счётчик изменений стоит на месте.
     */
    @Test
    fun anIdenticalRefreshKeepsGuidsDelaysAndTheSelection() {
        AngConfigManager.parseConfigViaSub(threeLinks, sub, false)
        val before = MmkvManager.decodeServerList(sub).toList()
        MmkvManager.setSelectServer(before[1])
        MmkvManager.encodeServerTestDelayMillis(before[0], 120L)
        val addedTime = MmkvManager.decodeServerConfig(before[2])!!.addedTime
        val revision = MmkvManager.serversRevision()

        AngConfigManager.parseConfigViaSub(threeLinks, sub, false)

        assertEquals(before, MmkvManager.decodeServerList(sub))
        assertEquals(before[1], MmkvManager.getSelectServer())
        assertEquals(120L, MmkvManager.decodeServerAffiliationInfo(before[0])!!.testDelayMillis)
        assertEquals(addedTime, MmkvManager.decodeServerConfig(before[2])!!.addedTime)
        assertEquals(revision, MmkvManager.serversRevision())
    }

    /**
     * Поменялся один сервер: он получает новый guid (это другой сервер), остальные - прежние, пинг
     * неизменившегося на месте, а счётчик изменений сдвигается.
     */
    @Test
    fun onlyTheServerThatChangedGetsANewGuid() {
        AngConfigManager.parseConfigViaSub(threeLinks, sub, false)
        val before = MmkvManager.decodeServerList(sub).toList()
        MmkvManager.setSelectServer(before[0])
        MmkvManager.encodeServerTestDelayMillis(before[0], 80L)
        MmkvManager.encodeServerTestDelayMillis(before[2], 95L)
        val revision = MmkvManager.serversRevision()

        val changed = listOf(trojan("Germany", "de"), trojan("Netherlands", "nl"), trojan("Finland", "fi", 8443))
        AngConfigManager.parseConfigViaSub(changed.joinToString("\n"), sub, false)

        val after = MmkvManager.decodeServerList(sub)
        assertEquals(before[0], after[0])
        assertEquals(before[1], after[1])
        assertNotEquals(before[2], after[2])
        assertNull("прежний Finland удалён вместе с его пингом", MmkvManager.decodeServerConfig(before[2]))
        assertNull(MmkvManager.decodeServerAffiliationInfo(before[2]))
        assertEquals(80L, MmkvManager.decodeServerAffiliationInfo(before[0])!!.testDelayMillis)
        assertEquals(before[0], MmkvManager.getSelectServer())
        assertTrue(MmkvManager.serversRevision() > revision)
    }

    /** Отмеченный сервер поменялся - отметка переходит на него же по имени, как и раньше. */
    @Test
    fun aChangedSelectedServerIsFollowedByName() {
        AngConfigManager.parseConfigViaSub(threeLinks, sub, false)
        val before = MmkvManager.decodeServerList(sub).toList()
        MmkvManager.setSelectServer(before[2])

        val changed = listOf(trojan("Germany", "de"), trojan("Netherlands", "nl"), trojan("Finland", "fi", 8443))
        AngConfigManager.parseConfigViaSub(changed.joinToString("\n"), sub, false)

        val selected = MmkvManager.getSelectServer()!!
        assertNotEquals(before[2], selected)
        assertEquals("Finland", MmkvManager.decodeServerConfig(selected)!!.remarks)
    }

    /** Провайдер переставил строки: guid те же, порядок - его, и это изменение. */
    @Test
    fun aReorderKeepsGuidsAndFollowsTheProvidersOrder() {
        AngConfigManager.parseConfigViaSub(threeLinks, sub, false)
        val before = MmkvManager.decodeServerList(sub).toList()
        val revision = MmkvManager.serversRevision()

        val reordered = listOf(trojan("Finland", "fi"), trojan("Germany", "de"), trojan("Netherlands", "nl"))
        AngConfigManager.parseConfigViaSub(reordered.joinToString("\n"), sub, false)

        assertEquals(listOf(before[2], before[0], before[1]), MmkvManager.decodeServerList(sub))
        assertTrue(MmkvManager.serversRevision() > revision)
    }

    // ------------------------------------------------------------------ XRAY_JSON

    private fun xrayConfig(name: String, host: String, port: Int = 443, network: String = "tcp") = """
        {"remarks": "$name",
         "outbounds": [
           {"tag": "proxy", "protocol": "vless",
            "settings": {"vnext": [{"address": "$host", "port": $port,
              "users": [{"id": "0b6d8d5c-1111-4e2f-9d7a-000000000001", "encryption": "none"}]}]},
            "streamSettings": {"network": "$network", "security": "reality"}},
           {"tag": "direct", "protocol": "freedom"}
         ]}
    """.trimIndent()

    private fun xrayBody(vararg configs: String) = configs.joinToString(",", "[", "]")

    /** Конфиги провайдера без изменений: guid, шаблоны и счётчик на месте. */
    @Test
    fun anIdenticalXrayJsonRefreshKeepsEverything() {
        val body = xrayBody(xrayConfig("🇩🇪 Германия", "de.example.com"), xrayConfig("🇳🇱 Нидерланды", "nl.example.com"))
        assertEquals(2, AngConfigManager.parseConfigViaSub(body, sub, false))
        val before = MmkvManager.decodeServerList(sub).toList()
        val raws = before.map { MmkvManager.decodeServerRaw(it) }
        MmkvManager.setSelectServer(before[1])
        MmkvManager.encodeServerTestDelayMillis(before[1], 64L)
        val revision = MmkvManager.serversRevision()

        AngConfigManager.parseConfigViaSub(body, sub, false)

        assertEquals(before, MmkvManager.decodeServerList(sub))
        assertEquals(raws, before.map { MmkvManager.decodeServerRaw(it) })
        assertEquals(before[1], MmkvManager.getSelectServer())
        assertEquals(64L, MmkvManager.decodeServerAffiliationInfo(before[1])!!.testDelayMillis)
        assertEquals(revision, MmkvManager.serversRevision())
    }

    /**
     * Шаблон конфига провайдера поменялся, имя - нет: guid остаётся (так и на ПК), под ним лежит
     * новый шаблон, и счётчик изменений сдвигается - по нему экран перечитает список.
     */
    @Test
    fun aChangedXrayJsonTemplateKeepsItsGuidAndIsMarkedChanged() {
        AngConfigManager.parseConfigViaSub(
            xrayBody(xrayConfig("🇩🇪 Германия", "de.example.com"), xrayConfig("🇳🇱 Нидерланды", "nl.example.com")),
            sub, false,
        )
        val before = MmkvManager.decodeServerList(sub).toList()
        MmkvManager.setSelectServer(before[0])
        val revision = MmkvManager.serversRevision()

        AngConfigManager.parseConfigViaSub(
            xrayBody(
                xrayConfig("🇩🇪 Германия", "de2.example.com", 8443, network = "grpc"),
                xrayConfig("🇳🇱 Нидерланды", "nl.example.com"),
            ),
            sub, false,
        )

        assertEquals(before, MmkvManager.decodeServerList(sub))
        assertEquals(before[0], MmkvManager.getSelectServer())
        assertEquals("de2.example.com", MmkvManager.decodeServerConfig(before[0])!!.server)
        assertTrue(MmkvManager.decodeServerRaw(before[0])!!.contains("grpc"))
        assertTrue(MmkvManager.serversRevision() > revision)
    }

    /** Провайдер убрал сервер: его guid, пинг и шаблон уходят, остальные остаются. */
    @Test
    fun aServerTheProviderDroppedGoesWithItsRecords() {
        AngConfigManager.parseConfigViaSub(
            xrayBody(xrayConfig("🇩🇪 Германия", "de.example.com"), xrayConfig("🇳🇱 Нидерланды", "nl.example.com")),
            sub, false,
        )
        val before = MmkvManager.decodeServerList(sub).toList()
        MmkvManager.encodeServerTestDelayMillis(before[1], 70L)

        AngConfigManager.parseConfigViaSub(xrayBody(xrayConfig("🇩🇪 Германия", "de.example.com")), sub, false)

        assertEquals(listOf(before[0]), MmkvManager.decodeServerList(sub))
        assertNull(MmkvManager.decodeServerConfig(before[1]))
        assertNull(MmkvManager.decodeServerRaw(before[1]))
        assertNull(MmkvManager.decodeServerAffiliationInfo(before[1]))
    }
}
