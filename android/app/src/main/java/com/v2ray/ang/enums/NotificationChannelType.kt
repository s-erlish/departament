package com.v2ray.ang.enums

import android.app.NotificationManager
import androidx.annotation.StringRes
import com.v2ray.ang.R

/**
 * The notification channels this app owns, other than the ongoing VPN one.
 *
 * THE NAME IS A STRING RESOURCE, and that is the point of the change. A channel name is not an
 * internal label: it is the row the user reads in Android's own notification settings for the app,
 * next to «departament VPN». Upstream hard-coded «Subscription Update Service» and «Core Test
 * Service» as English literals here, so a Russian-only product listed two English service names in
 * the system UI. They are resources now, so they speak the interface's language like everything else.
 *
 * **THE SHADE CARRIES ONE THING FROM THIS APP, AND IT IS THE ПОДПИСКА REFRESH.** The owner, looking
 * at «Запущено проверок: 10 / 10» on his lock screen: «переделать уведомления в шторке, этого так
 * быть не должно, там просто должно быть обновление подписки и все». That counter was the latency
 * check's internal progress, pushed to the shade several times a second by a batch the user had not
 * started — the провайдер refresh runs it unattended. It is gone; see [CORE_TEST].
 */
enum class NotificationChannelType(
    val channelId: String,
    @param:StringRes val channelNameRes: Int,
    val notificationId: Int,
    /** The channel's importance, decided per channel rather than one figure for all of them. */
    val importance: Int,
) {
    /**
     * The one notification this product means to put in front of a person: a подписка is being
     * refreshed. Low, so it is silent, but present — it is the answer to «почему приложение
     * что-то делает».
     */
    SUBSCRIPTION_UPDATE(
        channelId = "subscription_update_channel",
        channelNameRes = R.string.notification_channel_subscription,
        notificationId = 13,
        importance = NotificationManager.IMPORTANCE_LOW,
    ),

    /**
     * The latency check's foreground-service notification, reduced to the minimum Android will
     * accept and no further.
     *
     * IT CANNOT BE REMOVED OUTRIGHT, and it is worth writing down why rather than leaving the next
     * reader to rediscover it: `CoreTestService` is started with `startForegroundService`, so the
     * system kills the process unless a notification is posted within about five seconds. The check
     * has to be a foreground service, because the провайдер refresh asks for it from a WorkManager
     * worker — from the background, where an ordinary `startService` is refused outright.
     *
     * So what is left is made as close to nothing as the platform allows: **IMPORTANCE_MIN**, which
     * takes the icon out of the status bar and drops the row to the silent section of the shade;
     * **no content line at all**, because the only thing that was ever written there was the
     * counter; and the deferred foreground behaviour Android 12+ honours, which holds the row back
     * for ten seconds — longer than most batches take to finish.
     *
     * The channel id carries a `_v2` suffix on purpose. A channel's importance is fixed at
     * creation and `createNotificationChannel` will not lower it afterwards, so every install that
     * has already run the old build would have kept the old, visible channel. The legacy id is
     * deleted in [com.v2ray.ang.util.NotificationHelper.ensureChannelCreated].
     */
    CORE_TEST(
        channelId = "core_test_channel_v2",
        channelNameRes = R.string.notification_channel_core_test,
        notificationId = 12,
        importance = NotificationManager.IMPORTANCE_MIN,
    );

    /** The channel id this type used before its importance changed, or null when it never moved. */
    val legacyChannelId: String?
        get() = if (this == CORE_TEST) "core_test_channel" else null
}
