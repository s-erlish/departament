package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * «DNS для прямых соединений» — Яндекс, и установки со старым умолчанием апстрима (AliDNS
 * 223.5.5.5) переезжают на него один раз. Тест гоняет настоящий [MmkvManager] поверх словаря
 * вместо нативного хранилища ([InMemoryStores]), как его видит [SettingsManager] при запуске.
 */
class DomesticDnsDefaultTest {

    private val stores = InMemoryStores()

    @Before
    fun setUp() = stores.start()

    @After
    fun tearDown() = stores.stop()

    private fun domestic(): String? = MmkvManager.decodeSettingsString(AppConfig.PREF_DOMESTIC_DNS)

    @Test
    fun theDefaultIsYandex() {
        assertEquals("77.88.8.8", AppConfig.DNS_DIRECT)
        // Ничего не записано или записан мусор — ядро получает умолчание, а не пустой список.
        assertEquals(listOf("77.88.8.8"), SettingsManager.getDomesticDnsServers())
        MmkvManager.encodeSettings(AppConfig.PREF_DOMESTIC_DNS, "not a resolver")
        assertEquals(listOf("77.88.8.8"), SettingsManager.getDomesticDnsServers())
    }

    /** Установка, где строку не трогали: там лежит то, что записал ensureDefaultValue апстрима. */
    @Test
    fun theUntouchedUpstreamDefaultMovesToYandex() {
        MmkvManager.encodeSettings(AppConfig.PREF_DOMESTIC_DNS, "223.5.5.5")

        SettingsManager.migrateDomesticDnsDefaultOnce()

        assertEquals("77.88.8.8", domestic())
        assertEquals(listOf("77.88.8.8"), SettingsManager.getDomesticDnsServers())
    }

    /** Свежая установка: значения ещё нет, миграция ничего не пишет, умолчание берётся из кода. */
    @Test
    fun aFreshInstallIsNotTouched() {
        SettingsManager.migrateDomesticDnsDefaultOnce()

        assertNull(domestic())
        assertEquals(listOf("77.88.8.8"), SettingsManager.getDomesticDnsServers())
    }

    /** Всё, что не равно умолчанию апстрима точно, — выбор человека. */
    @Test
    fun customisedValuesAreLeftAlone() {
        val chosen = listOf(
            "1.1.1.1",
            "223.5.5.5,1.1.1.1",
            " 223.5.5.5",
            "223.6.6.6",
            "https://dns.alidns.com/dns-query",
            "77.88.8.1",
        )
        for (value in chosen) {
            stores.stop()
            stores.start()
            MmkvManager.encodeSettings(AppConfig.PREF_DOMESTIC_DNS, value)

            SettingsManager.migrateDomesticDnsDefaultOnce()

            assertEquals(value, domestic())
        }
    }

    /** Один раз: вернуть себе 223.5.5.5 руками после перехода — выбор, и запуск его не отменяет. */
    @Test
    fun itRunsOnce() {
        MmkvManager.encodeSettings(AppConfig.PREF_DOMESTIC_DNS, "223.5.5.5")
        SettingsManager.migrateDomesticDnsDefaultOnce()
        assertEquals("77.88.8.8", domestic())

        MmkvManager.encodeSettings(AppConfig.PREF_DOMESTIC_DNS, "223.5.5.5")
        SettingsManager.migrateDomesticDnsDefaultOnce()
        SettingsManager.migrateDomesticDnsDefaultOnce()

        assertEquals("223.5.5.5", domestic())
    }
}
