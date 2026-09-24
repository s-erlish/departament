package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * «АВТООБНОВЛЕНИЕ ПОДПИСКИ» - что расписание считает подпиской, какое расписание стоит сейчас и
 * когда задача не качает то, что только что скачано.
 *
 * На ПК эта строка настроек не обновляла подписку никогда. На Android она работает - выбор пишется
 * в подписки и уходит в WorkManager, - но у неё было три дыры, и тест закрепляет, что их нет:
 * служебная корзина серверов без подписки стояла в расписании (раз в час - «Обновляем подписку» в
 * шторке и пропуск), подписка, добавленная после выбора, выбор не наследовала, а задача по
 * расписанию и обновление при запуске качали одну подписку дважды.
 */
class SubscriptionUpdaterScheduleTest {

    private val stores = InMemoryStores()

    @Before
    fun setUp() = stores.start()

    @After
    fun tearDown() = stores.stop()

    private fun sub(
        guid: String,
        url: String = "https://sub.example.com/$guid",
        autoUpdate: Boolean = true,
        interval: Long = 60,
        enabled: Boolean = true,
    ) = SubscriptionCache(guid, SubscriptionItem(url = url, autoUpdate = autoUpdate, updateInterval = interval, enabled = enabled))

    private val bucket = sub(AppConfig.DEFAULT_SUBSCRIPTION_ID, url = "")

    /** Служебная корзина и подписка без адреса - не подписки, и в расписании им делать нечего. */
    @Test
    fun onlyASubscriptionWithAnAddressIsScheduled() {
        assertFalse(SubscriptionUpdater.isRealSubscription(bucket))
        assertFalse(SubscriptionUpdater.shouldAutoUpdate(bucket))
        assertFalse(SubscriptionUpdater.shouldAutoUpdate(sub("a", url = " ")))
        assertFalse(SubscriptionUpdater.shouldAutoUpdate(sub("a", enabled = false)))
        assertFalse(SubscriptionUpdater.shouldAutoUpdate(sub("a", autoUpdate = false)))
        assertTrue(SubscriptionUpdater.shouldAutoUpdate(sub("a")))
    }

    /** Нет настоящих подписок - нет и расписания: строка настроек говорит «Нет подписок». */
    @Test
    fun noRealSubscriptionMeansNoSchedule() {
        assertNull(SubscriptionUpdater.scheduleOf(emptyList()))
        assertNull(SubscriptionUpdater.scheduleOf(listOf(bucket)))
    }

    /**
     * Корзина стоит в списке первой и по умолчанию «включена, раз в час» - и раньше отвечала за
     * всё. Теперь выбор читается с настоящих подписок.
     */
    @Test
    fun theScheduleIsReadFromRealSubscriptionsOnly() {
        assertEquals(false to 720L, SubscriptionUpdater.scheduleOf(listOf(bucket, sub("a", autoUpdate = false, interval = 720))))
        assertEquals(true to 360L, SubscriptionUpdater.scheduleOf(listOf(bucket, sub("a", autoUpdate = false), sub("b", interval = 360))))
    }

    /**
     * Свежесть: подписка, скачанная минуту назад, повторно не качается; никогда не скачанная и
     * «скачанная в будущем» (часы переводили назад) - качается.
     */
    @Test
    fun aFetchMomentsAgoIsNotRepeated() {
        val now = 10_000_000L
        val window = 30 * 60_000L
        assertTrue(SubscriptionUpdater.isFresh(now - 60_000L, now, window))
        assertFalse(SubscriptionUpdater.isFresh(now - window, now, window))
        assertFalse(SubscriptionUpdater.isFresh(-1L, now, window))
        assertFalse(SubscriptionUpdater.isFresh(now + 60_000L, now, window))
    }

    /** По расписанию - половина интервала (не меньше половины минимального), при запуске - две минуты. */
    @Test
    fun theFreshWindowIsHalfTheIntervalOrTwoMinutesAtLaunch() {
        assertEquals(30 * 60_000L, SubscriptionUpdater.freshWindowMillis(force = false, intervalMinutes = 60))
        assertEquals(AppConfig.SUBSCRIPTION_MIN_INTERVAL_MINUTES * 60_000L / 2,
            SubscriptionUpdater.freshWindowMillis(force = false, intervalMinutes = 1))
        assertEquals(2 * 60_000L, SubscriptionUpdater.freshWindowMillis(force = true, intervalMinutes = 720))
    }

    /**
     * Подписка, добавленная после выбора в настройках (из буфера, по QR-коду, из аккаунта),
     * встаёт в выбранное расписание, а не в умолчание «включено, раз в час». Корзина серверов без
     * подписки выбор не подменяет.
     */
    @Test
    fun aNewSubscriptionStartsOnTheChosenSchedule() {
        MmkvManager.encodeSubscription(AppConfig.DEFAULT_SUBSCRIPTION_ID, SubscriptionItem())
        MmkvManager.encodeSubscription("a", SubscriptionItem(url = "https://sub.example.com/a", autoUpdate = false, updateInterval = 1440))

        val added = SubscriptionItem(url = "https://sub.example.com/b")
        SubscriptionUpdater.applyCurrentSchedule(added)

        assertFalse(added.autoUpdate)
        assertEquals(1440L, added.updateInterval)
    }

    /** Первая настоящая подписка выбирать не у кого - она остаётся на умолчании. */
    @Test
    fun theFirstSubscriptionKeepsTheDefaultSchedule() {
        MmkvManager.encodeSubscription(AppConfig.DEFAULT_SUBSCRIPTION_ID, SubscriptionItem(autoUpdate = false))

        val added = SubscriptionItem(url = "https://sub.example.com/a")
        SubscriptionUpdater.applyCurrentSchedule(added)

        assertTrue(added.autoUpdate)
        assertEquals(SubscriptionItem().updateInterval, added.updateInterval)
    }
}
