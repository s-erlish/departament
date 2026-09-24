package com.v2ray.ang.handler

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ЧЕЙ ЭТО СЕРВЕР ПОСЛЕ ОБНОВЛЕНИЯ ПОДПИСКИ - правила [SubscriptionRefreshIdentity].
 *
 * Владелец на ПК: обновление подписки «перегружало» всё - пинги пропадали, выбор прыгал, туннель
 * перезапускался, хотя в подписке не поменялось ни байта. На Android та же причина: каждый импорт
 * выдавал каждому серверу новый guid. Тест закрепляет правила узнавания, те же, что на ПК
 * (ConfigHandler.IsSameServerAcrossRefresh): обычный сервер - по настройкам и имени, конфиг
 * провайдера - по имени.
 */
class SubscriptionRefreshIdentityTest {

    private fun trojan(remarks: String, host: String, port: String = "443", password: String = "p-$host") =
        ProfileItem(configType = EConfigType.TROJAN, remarks = remarks, server = host, serverPort = port,
            password = password, network = "tcp", security = "tls", sni = host)

    private fun custom(remarks: String, host: String, port: String = "443") =
        ProfileItem(configType = EConfigType.CUSTOM, remarks = remarks, server = host, serverPort = port)

    /** Ничего не поменялось - каждый сервер получает свой прежний guid, в любом порядке ответа. */
    @Test
    fun unchangedServersKeepTheirGuids() {
        val previous = listOf("g1" to trojan("Германия", "de"), "g2" to trojan("Нидерланды", "nl"))
        assertEquals(listOf("g1", "g2"),
            SubscriptionRefreshIdentity.assign(previous, listOf(trojan("Германия", "de"), trojan("Нидерланды", "nl"))))
        // Провайдер переставил строки - серверы те же.
        assertEquals(listOf("g2", "g1"),
            SubscriptionRefreshIdentity.assign(previous, listOf(trojan("Нидерланды", "nl"), trojan("Германия", "de"))))
    }

    /** Другой порт, другой пароль, другое имя - это другой обычный сервер, и guid у него новый. */
    @Test
    fun aChangedOrRenamedServerIsANewServer() {
        val previous = listOf("g1" to trojan("Германия", "de"))
        assertEquals(listOf<String?>(null), SubscriptionRefreshIdentity.assign(previous, listOf(trojan("Германия", "de", port = "8443"))))
        assertEquals(listOf<String?>(null), SubscriptionRefreshIdentity.assign(previous, listOf(trojan("Германия", "de", password = "new"))))
        assertEquals(listOf<String?>(null), SubscriptionRefreshIdentity.assign(previous, listOf(trojan("Германия 2", "de"))))
    }

    /**
     * Поля, которых [ProfileItem.equals] не сравнивает, а ПК сравнивает: `extra` у XHTTP и
     * `finalMask`. Их смена - тоже другой сервер.
     */
    @Test
    fun xhttpExtraAndFinalMaskAreComparedLikeOnTheDesktop() {
        val old = trojan("Германия", "de")
        assertFalse(SubscriptionRefreshIdentity.isSameServer(old, trojan("Германия", "de").apply { xhttpExtra = "{}" }))
        assertFalse(SubscriptionRefreshIdentity.isSameServer(old, trojan("Германия", "de").apply { finalMask = "x" }))
    }

    /** Конфиг провайдера узнаётся по имени, даже если в шаблоне сменился адрес. */
    @Test
    fun aProviderConfigIsRecognisedByItsName() {
        val previous = listOf("c1" to custom("🇩🇪 Германия", "de-1"))
        assertEquals(listOf("c1"), SubscriptionRefreshIdentity.assign(previous, listOf(custom("🇩🇪 Германия", "de-2", "8443"))))
    }

    /**
     * Два конфига с одним именем не меняются guid-ами, пока их адреса различимы: сначала ищется
     * совпадение имени и адреса, только потом - одного имени.
     */
    @Test
    fun twoProviderConfigsWithOneNameAreToldApartByAddress() {
        val previous = listOf("c1" to custom("Авто", "a"), "c2" to custom("Авто", "b"))
        assertEquals(listOf("c2", "c1"),
            SubscriptionRefreshIdentity.assign(previous, listOf(custom("Авто", "b"), custom("Авто", "a"))))
    }

    /** Каждый прежний guid отдаётся один раз: третьему одинаковому серверу guid достаётся новый. */
    @Test
    fun aPreviousGuidIsGivenOutOnlyOnce() {
        val previous = listOf("g1" to trojan("Германия", "de"), "g2" to trojan("Германия", "de"))
        val fresh = listOf(trojan("Германия", "de"), trojan("Германия", "de"), trojan("Германия", "de"))
        assertEquals(listOf("g1", "g2", null), SubscriptionRefreshIdentity.assign(previous, fresh))
    }

    /** Конфиг провайдера и ссылка друг в друга не превращаются, и безымянный конфиг не узнаётся. */
    @Test
    fun providerConfigsAndLinksNeverMatchEachOther() {
        assertFalse(SubscriptionRefreshIdentity.isSameServer(custom("Германия", "de"), trojan("Германия", "de")))
        assertFalse(SubscriptionRefreshIdentity.isSameServer(trojan("Германия", "de"), custom("Германия", "de")))
        assertFalse(SubscriptionRefreshIdentity.isSameServer(custom("", "de"), custom("", "de")))
        assertTrue(SubscriptionRefreshIdentity.isSameServer(custom("Германия", "de"), custom("Германия", "de"), strict = true))
    }

    /**
     * Отпечаток не видит времени добавления - его каждый импорт пишет своё - и видит всё, из чего
     * собирается туннель, включая шаблон конфига провайдера.
     */
    @Test
    fun theFingerprintIgnoresTheImportTimeAndSeesEverythingElse() {
        val a = trojan("Германия", "de").apply { addedTime = 1L }
        val b = trojan("Германия", "de").apply { addedTime = 2L }
        assertEquals(SubscriptionRefreshIdentity.fingerprint(a, null), SubscriptionRefreshIdentity.fingerprint(b, null))
        assertNotEquals(SubscriptionRefreshIdentity.fingerprint(a, null),
            SubscriptionRefreshIdentity.fingerprint(trojan("Германия", "de").apply { spiderX = "/x" }, null))
        val c = custom("Германия", "de")
        assertNotEquals(SubscriptionRefreshIdentity.fingerprint(c, "{\"a\":1}"), SubscriptionRefreshIdentity.fingerprint(c, "{\"a\":2}"))
    }
}
