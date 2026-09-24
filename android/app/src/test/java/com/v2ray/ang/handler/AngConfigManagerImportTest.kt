package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ИМПОРТ БЕЗ ПОДПИСКИ ТОЛЬКО ДОБАВЛЯЕТ - [AngConfigManager.replacesServers].
 *
 * Ярлык «Сканировать QR» и ссылки depv:// / «Поделиться» звали импорт с `append = false` и без
 * подписки, и это заменяло корзину серверов, добавленных руками: отсканировал один сервер -
 * остальные пропали вместе с пингами. Тест гоняет настоящий [AngConfigManager.importBatchConfig] по
 * хранилищу в памяти ([InMemoryStores]) и закрепляет, что добавленное руками остаётся, а подписка
 * по-прежнему заменяется своим обновлением.
 */
class AngConfigManagerImportTest {

    private val stores = InMemoryStores()

    @Before
    fun setUp() = stores.start()

    @After
    fun tearDown() = stores.stop()

    private fun trojan(name: String, host: String) =
        "trojan://pass-$host@$host.example.com:443?security=tls&sni=$host.example.com&type=tcp#$name"

    /** Сервер, добавленный руками: без подписки, то есть в корзине `__default_subscription__`. */
    private fun handAdded(name: String, host: String): String =
        MmkvManager.encodeServerConfig(
            "",
            ProfileItem(configType = EConfigType.TROJAN, remarks = name, server = "$host.example.com",
                serverPort = "443", password = "pass-$host"),
        )

    private fun localNames(): List<String> =
        MmkvManager.decodeServerList("").mapNotNull { MmkvManager.decodeServerConfig(it)?.remarks }

    /** Заменять можно только серверы подписки; без неё и в корзине - только добавлять. */
    @Test
    fun onlyASubscriptionCanBeReplaced() {
        assertTrue(AngConfigManager.replacesServers("sub-1", append = false))
        assertFalse(AngConfigManager.replacesServers("sub-1", append = true))
        assertFalse(AngConfigManager.replacesServers("", append = false))
        assertFalse(AngConfigManager.replacesServers("  ", append = false))
        assertFalse(AngConfigManager.replacesServers(AppConfig.DEFAULT_SUBSCRIPTION_ID, append = false))
    }

    /**
     * То, что делали ярлык сканера и ссылки: импорт без подписки с `append = false`. Добавленные
     * руками серверы, их пинг и отметка остаются, новый встаёт рядом.
     */
    @Test
    fun aScanWithoutSubscriptionKeepsTheHandAddedServers() {
        val mine = handAdded("Мой сервер", "mine")
        val other = handAdded("Второй", "other")
        MmkvManager.setSelectServer(other)
        MmkvManager.encodeServerTestDelayMillis(mine, 42L)

        val result = AngConfigManager.importBatchConfig(trojan("Scanned", "scanned"), "", false)

        assertEquals(1, result.count)
        assertEquals(setOf("Мой сервер", "Второй", "Scanned"), localNames().toSet())
        assertNotNull(MmkvManager.decodeServerConfig(mine))
        assertEquals(42L, MmkvManager.decodeServerAffiliationInfo(mine)!!.testDelayMillis)
        assertEquals(other, MmkvManager.getSelectServer())
    }

    /** Та же ссылка второй раз не заводит второй строки: дубликат в корзине убирается. */
    @Test
    fun scanningTheSameLinkTwiceAddsItOnce() {
        handAdded("Мой сервер", "mine")

        AngConfigManager.importBatchConfig(trojan("Scanned", "scanned"), "", true)
        AngConfigManager.importBatchConfig(trojan("Scanned", "scanned"), "", true)

        assertEquals(listOf("Мой сервер", "Scanned").sorted(), localNames().sorted())
    }

    /** Подписка своим обновлением по-прежнему заменяется целиком. */
    @Test
    fun aSubscriptionIsStillReplacedByItsOwnUpdate() {
        AngConfigManager.importBatchConfig(listOf(trojan("A", "a"), trojan("B", "b")).joinToString("\n"), "sub-1", false)
        AngConfigManager.importBatchConfig(trojan("C", "c"), "sub-1", false)

        assertEquals(listOf("C"), MmkvManager.decodeServerList("sub-1").map { MmkvManager.decodeServerConfig(it)!!.remarks })
    }
}
