package com.v2ray.ang.handler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.extension.toSpeedString
import com.v2ray.ang.ui.MainActivity
import com.v2ray.ang.util.FlagUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MessageUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object NotificationManager {
    private const val NOTIFICATION_ID = 1
    private const val NOTIFICATION_PENDING_INTENT_CONTENT = 0
    private const val NOTIFICATION_PENDING_INTENT_STOP_V2RAY = 1
    private const val NOTIFICATION_PENDING_INTENT_PAUSE = 2
    private const val NOTIFICATION_PENDING_INTENT_RESUME = 3
    private const val QUERY_INTERVAL_MS = 3000L

    /**
     * WHAT THE ROW IN THE SHADE IS ALLOWED TO SAY. Three states, and every one of them is a fact
     * about the tunnel rather than about what the user last pressed.
     *
     * The state is NOT stored here. [currentState] derives it from [CoreServiceManager] on every
     * render — `isRunning()` is the core's own answer and `isPaused()` is the flag the pause path
     * writes — so the label on the button and the sentence under the title cannot drift out of
     * step with the thing they describe, not even for the moment a tunnel takes to come up.
     */
    private enum class Shade { CONNECTING, RUNNING, PAUSED }

    private var lastQueryTime = 0L

    /**
     * WHAT WAS LAST ACTUALLY WRITTEN INTO THE SHADE, so an unchanged row is not re-posted.
     *
     * It used to hold the rate alone (`lastPushedSpeed`); the state joined it when the row grew a
     * second thing it can say, because the guard has to cover BOTH — a state change must always
     * post, and an identical state with an identical rate must never. Cleared whenever [mBuilder]
     * is dropped: a new row carries neither a speed line nor a state yet.
     * @see updateSpeedNotificationOnce
     */
    private var lastPushed: Posted? = null
    private data class Posted(val state: Shade, val down: Long, val up: Long)

    /**
     * The server the row is about, remembered separately from the builder.
     *
     * Rebuilding the row for a new state must not lose the title — «🇵🇱 Poland» is still the
     * answer to «which server is on pause» — and the one caller that knows the profile
     * ([showNotification] out of `doStartCoreLoop`) is not the one that flips the state.
     */
    private var shadeTitle: String? = null
    private var mBuilder: NotificationCompat.Builder? = null
    private var channelId: String? = null
    private var speedNotificationJob: Job? = null
    private var mNotificationManager: NotificationManager? = null

    /**
     * Starts the speed notification.
     * @param currentConfig The current profile configuration.
     */
    fun startSpeedNotification() {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_SPEED_ENABLED) != true) return
        if (speedNotificationJob != null || CoreServiceManager.isRunning() == false) return

        speedNotificationJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                updateSpeedNotificationOnce()
                delay(QUERY_INTERVAL_MS)
            }
        }
    }

    /**
     * Shows the notification and promotes the service to the foreground.
     *
     * A foreground service MUST call startForeground() within ~5s with a valid notification or
     * the system kills the process (which strands the UI on "Подключение…" and shows an app
     * crash). This method is therefore hardened so it can NEVER throw: if building the rich
     * notification (flag title, chronometer, actions) fails for any reason, it falls back to a
     * minimal valid notification, and startForeground() itself is guarded. Missing/invalid
     * drawables, a cleared service reference, PendingIntent issues, or a foreground-service
     * policy exception can no longer take down the VPN process.
     *
     * @param currentConfig The current profile configuration, or null when the caller does not
     *   know it yet — the remembered title and state are used instead.
     * @return true if the service was promoted to the foreground, false otherwise.
     */
    fun showNotification(currentConfig: ProfileItem?): Boolean {
        val service = getService() ?: run {
            LogUtil.e(AppConfig.TAG, "showNotification: service reference is null; cannot start foreground")
            return false
        }

        // Reset last query time to avoid querying stats too soon after showing the notification.
        //
        // ТОЛЬКО ДЛЯ НОВОГО ТУННЕЛЯ. [lastQueryTime] - начало окна, за которое копятся счётчики, и
        // сдвигать его можно только вместе с их обнулением: новое ядро приходит с нулями, а здесь
        // оно ещё не запущено. Повторный старт на живом туннеле (`CoreVpnService`, дубликат
        // команды) тоже проходит через эту строку, и сдвиг окна без обнуления отдавал следующему
        // опросу байты за прежнее окно, делённые на новое, более короткое - скачок скорости.
        if (!CoreServiceManager.isRunning()) lastQueryTime = System.currentTimeMillis()

        val channel = ensureChannel(service)

        // A START THAT ARRIVES WHILE A SESSION IS UP MUST NOT BLANK THE ROW IN THE SHADE.
        // `CoreVpnService` promotes itself to the foreground with `null` before it knows which
        // server it is about to run — the 5-second deadline does not wait for that — and with a
        // live tunnel underneath, building from `null` would replace «🇵🇱 Poland  00:11:52» with
        // a title-less notification for as long as the duplicate start took to be refused.
        //
        // It used to re-post the previous builder verbatim to avoid that. It does not need to any
        // more: the title is remembered ([shadeTitle]), the clock is read back from the session
        // rather than restarted, and the state comes from the core — so re-rendering from null
        // reproduces the same row, and ALSO says the right thing when the state has moved on (a
        // «Возобновить» row must not survive into the connect that «Возобновить» just started).
        if (currentConfig != null) shadeTitle = titleOf(currentConfig)

        val state = currentState()
        val down = lastPushed?.down ?: 0L
        val up = lastPushed?.up ?: 0L

        val notification = try {
            buildRichNotification(service, channel, state, down, up).also {
                lastPushed = Posted(state, down, up)
            }
        } catch (e: Exception) {
            // Any failure while assembling the rich notification must not prevent the mandatory
            // startForeground() call. Fall back to a minimal, always-valid notification.
            LogUtil.e(AppConfig.TAG, "showNotification: rich notification build failed, using fallback", e)
            mBuilder = null
            lastPushed = null
            buildFallbackNotification(service, channel)
        }

        return try {
            service.startForeground(NOTIFICATION_ID, notification)
            true
        } catch (e: Exception) {
            // startForeground can throw ForegroundServiceStartNotAllowedException / SecurityException /
            // InvalidForegroundServiceTypeException on newer Android. Swallow so the caller can decide
            // how to recover instead of crashing the whole service process.
            LogUtil.e(AppConfig.TAG, "showNotification: startForeground failed", e)
            false
        }
    }

    /**
     * RE-READS THE STATE AND RE-WRITES THE ROW. One call for both moments the shade has to move:
     * the core finished coming up, and the user put it on pause.
     *
     * It reads [CoreServiceManager] rather than being told what to say, so a caller cannot make
     * the shade claim something the core does not. The memo in [post] keeps a call that changes
     * nothing from costing a binder round trip.
     */
    fun refreshState() {
        val service = getService() ?: return
        val state = currentState()
        // A rate belongs to a live tunnel and to nothing else: a paused row does not carry
        // «↓ 0 КБ/с», it carries «На паузе».
        if (state == Shade.RUNNING) {
            post(service, state, lastPushed?.down ?: 0L, lastPushed?.up ?: 0L)
        } else {
            post(service, state, 0L, 0L)
        }
    }

    /**
     * The tunnel is down by the user's hand and the row stays: stop the meter, say «На паузе».
     *
     * The meter is cancelled here rather than through [stopSpeedNotification] so the pause costs
     * ONE post instead of two — that one would push a zeroed rate into a row that is about to
     * stop showing rates at all.
     */
    fun pauseNotification() {
        speedNotificationJob?.cancel()
        speedNotificationJob = null
        refreshState()
    }

    /**
     * THE ONGOING VPN NOTIFICATION. Three states, and nothing in any of them that is not asked for.
     *
     *   ПОДКЛЮЧЕНИЕ  🇵🇱 Poland
     *                Подключение…
     *                [ Остановить ]
     *
     *   РАБОТАЕТ     🇵🇱 Poland                                    00:11:52
     *                ↓ 1,2 МБ/с   ↑ 240 КБ/с            (в развёрнутом виде)
     *                [ Пауза ]  [ Остановить ]
     *
     *   ПАУЗА        🇵🇱 Poland
     *                На паузе
     *                [ Возобновить ]  [ Остановить ]
     *
     * The running shape is the owner's, to the line: «при раскрытии уведомления этого надо чтобы
     * писало скорость, больше ничего не надо». The other two are the same shape with the truth in
     * the second line instead of a rate.
     *
     * WHAT CHANGED AND WHY:
     *
     *  - **«Пауза» exists at all** because «Остановить» took the notification with it, and there
     *    was then nothing in the shade to turn the tunnel back on with: «надо в шторку сделать
     *    кнопку паузы, чтобы можно было остановить впн и потом обратно включить прям из шторки».
     *    Pause is a real pause — the service stays in the foreground with the tunnel down — and
     *    that is what keeps the row (and therefore the way back) in the shade. A notification left
     *    behind by a service that has stopped would be dismissible, and the first swipe would put
     *    the user back where he started.
     *  - **«Остановить» is untouched** and still does exactly what it did: same broadcast, same
     *    teardown, row gone. It is present in all three states, because a pause the user cannot
     *    end is a worse trap than no pause at all.
     *  - **«Перезапуск службы» is gone.** A notification action is the shortest path in the product
     *    and it was spending it on a developer's recovery gesture, one tap away from «Остановить» —
     *    the one thing a user actually reaches for here, and the one they must not miss. Restarting
     *    is still reachable everywhere it belongs; `AppConfig.MSG_STATE_RESTART` and its broadcast
     *    are untouched, only this button is.
     *  - **No empty second line.** The content text was set to `""` on the way out
     *    ([stopSpeedNotification]), which does not remove the line, it prints an empty one. The
     *    running row carries the title and the chronometer and nothing where a sentence would be;
     *    the other two carry a sentence that is worth the line.
     *  - **The speed is IN the notification now**, in the expanded view of the RUNNING state only.
     *    It used to be deliberately excluded — the comment in [updateSpeedNotificationOnce] said
     *    so — and that decision is reversed rather than left contradicting the code. It costs no
     *    new work: the stats job already ticks every [QUERY_INTERVAL_MS] for Главная, so the same
     *    tick writes the line, at the same rate, and `setOnlyAlertOnce` keeps it silent.
     *
     * The uptime is the system's own chronometer, so it counts without a single push from us, and
     * it is anchored to [CoreServiceManager.sessionStartedAt] — the instant the core came up —
     * rather than to the instant this row was built, so a re-render mid-session does not reset it.
     */
    private fun buildRichNotification(
        service: Service,
        channelId: String,
        state: Shade,
        downPerSec: Long,
        upPerSec: Long,
    ): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        val startMainIntent = Intent(service, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(service, NOTIFICATION_PENDING_INTENT_CONTENT, startMainIntent, flags)

        val builder = NotificationCompat.Builder(service, channelId)
            .setSmallIcon(R.drawable.ic_stat_name)
            // БОЛЬШОГО ЗНАЧКА ЗДЕСЬ НЕТ, И ЭТО НЕ УПУЩЕНИЕ. Он тут был ровно один заход:
            // «и в шторке уведомлений» я прочитал как «положи логотип в цвете», а цвет в шторке
            // держит только largeIcon. Но под него система резервирует место и разворачивает
            // строку в крупную раскладку — «почему в шторке теперь оно огромным стало и не
            // сворачивается, логотипа вроде раньше справа не было».
            //
            // Знак и так на месте: setSmallIcon выше — это марка, которую система рисует из
            // альфы и красит setColor'ом. Одного раза достаточно.
            .setColor(ContextCompat.getColor(service, R.color.notification_badge))
            .setContentTitle(shadeTitle)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)

        when (state) {
            Shade.RUNNING -> {
                // System-rendered live uptime stopwatch (no per-second push, battery-free), counting
                // from when the core actually came up — the same instant Главная counts from.
                val startedAt = CoreServiceManager.sessionStartedAt().takeIf { it > 0L } ?: System.currentTimeMillis()
                builder.setWhen(startedAt)
                    .setUsesChronometer(true)
                    .setShowWhen(true)
                    .addAction(
                        R.drawable.ic_notif_pause,
                        service.getString(R.string.notification_action_pause),
                        pausePendingIntent(service, flags)
                    )
                    .addAction(
                        R.drawable.ic_notif_stop,
                        service.getString(R.string.notification_action_stop_v2ray),
                        stopPendingIntent(service, flags)
                    )
                // The rate reads zero until the first tick lands, three seconds in — honest, and it
                // means the expanded height never changes under the user's thumb by growing a line.
                // The memo is set to match, so an idle tunnel's first tick has nothing new to say
                // and stays silent.
                applySpeed(builder, service, downPerSec, upPerSec)
            }

            Shade.PAUSED -> {
                // No clock: there is no session to count. The title stays, because «which server
                // comes back» is the one thing worth keeping on a paused row.
                builder.setShowWhen(false)
                    .setContentText(service.getString(R.string.notification_state_paused))
                    .addAction(
                        R.drawable.ic_notif_resume,
                        service.getString(R.string.notification_action_resume),
                        resumePendingIntent(service, flags)
                    )
                    .addAction(
                        R.drawable.ic_notif_stop,
                        service.getString(R.string.notification_action_stop_v2ray),
                        stopPendingIntent(service, flags)
                    )
            }

            Shade.CONNECTING -> {
                // NO «Пауза» HERE. There is nothing to pause yet, and a button whose press would
                // race the handshake it is trying to interrupt is worse than one that appears a
                // second later. «Остановить» is the action the connecting state has always had.
                builder.setShowWhen(false)
                    .setContentText(service.getString(R.string.notification_state_connecting))
                    .addAction(
                        R.drawable.ic_notif_stop,
                        service.getString(R.string.notification_action_stop_v2ray),
                        stopPendingIntent(service, flags)
                    )
            }
        }

        mBuilder = builder
        return builder.build()
    }

    /** «Остановить» — the broadcast the running core listens for. Unchanged, in every state. */
    private fun stopPendingIntent(service: Service, flags: Int): PendingIntent {
        val intent = Intent(AppConfig.BROADCAST_ACTION_SERVICE)
        intent.`package` = AppConfig.ANG_PACKAGE
        intent.putExtra("key", AppConfig.MSG_STATE_STOP)
        return PendingIntent.getBroadcast(service, NOTIFICATION_PENDING_INTENT_STOP_V2RAY, intent, flags)
    }

    /** «Пауза» — same channel as «Остановить», because it too only ever addresses a live core. */
    private fun pausePendingIntent(service: Service, flags: Int): PendingIntent {
        val intent = Intent(AppConfig.BROADCAST_ACTION_SERVICE)
        intent.`package` = AppConfig.ANG_PACKAGE
        intent.putExtra("key", AppConfig.MSG_STATE_PAUSE)
        return PendingIntent.getBroadcast(service, NOTIFICATION_PENDING_INTENT_PAUSE, intent, flags)
    }

    /**
     * «Возобновить» — a start command addressed to the service class, not a broadcast.
     *
     * @see AppConfig.ACTION_RESUME_SERVICE for why the way back cannot travel on the broadcast
     * channel. `getForegroundService` because the service may have to be created for this (the
     * pause survives a process kill), and the paused service is already in the foreground in every
     * other case, so the promotion is a no-op rather than a new one.
     */
    private fun resumePendingIntent(service: Service, flags: Int): PendingIntent {
        val intent = Intent(service, service.javaClass).setAction(AppConfig.ACTION_RESUME_SERVICE)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(service, NOTIFICATION_PENDING_INTENT_RESUME, intent, flags)
        } else {
            PendingIntent.getService(service, NOTIFICATION_PENDING_INTENT_RESUME, intent, flags)
        }
    }

    /** «🇵🇱 Poland». Pure string work, guarded so a malformed remark can never abort a build. */
    private fun titleOf(config: ProfileItem): String {
        return try {
            "${FlagUtil.resolveFlag(config)} ${FlagUtil.stripLeadingFlag(config.remarks)}"
        } catch (e: Exception) {
            config.remarks
        }
    }

    /**
     * What the row is allowed to say, asked of the core rather than remembered.
     *
     * THE PAUSE IS ASKED ABOUT FIRST, and the order is the whole point. `stopLoop()` runs on its
     * own thread, so for the tail of a pause the core still answers «running» while the interface
     * is already coming down and the user has already been told the tunnel is off. Reading
     * `isRunning` first would paint that tail as a live session — and, since nothing renders
     * again afterwards, it would stay painted. The flag is only ever true when a person asked for
     * it (every start clears it before any core starts), so it cannot lie in the other direction.
     */
    private fun currentState(): Shade = when {
        CoreServiceManager.isPaused() -> Shade.PAUSED
        CoreServiceManager.isRunning() -> Shade.RUNNING
        else -> Shade.CONNECTING
    }

    /**
     * Minimal, always-valid foreground notification used when the rich build fails. It only
     * needs a small icon and a title to satisfy the foreground-service requirement.
     */
    private fun buildFallbackNotification(service: Service, channelId: String): Notification {
        return NotificationCompat.Builder(service, channelId)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setColor(ContextCompat.getColor(service, R.color.notification_badge))
            .setContentTitle(service.getString(R.string.app_name))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /**
     * Cancels the notification.
     */
    fun cancelNotification() {
        val service = getService() ?: return
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)

        mBuilder = null
        lastPushed = null
        shadeTitle = null
        channelId = null
        speedNotificationJob?.cancel()
        speedNotificationJob = null
        mNotificationManager = null
    }

    /**
     * Stops the speed notification.
     *
     * The rate is returned to zero rather than blanked. Blanking it wrote `""` into the content
     * text, which prints an EMPTY line rather than removing one — the second line the owner
     * reported — and a stopped meter that reads «↓ 0 КБ/с ↑ 0 КБ/с» is also simply true.
     */
    fun stopSpeedNotification() {
        speedNotificationJob?.let {
            it.cancel()
            speedNotificationJob = null
            pushSpeed(0L, 0L)
        }
    }

    /**
     * Creates a notification channel for Android O and above.
     * @return The channel ID.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(): String {
        val channelId = AppConfig.RAY_NG_CHANNEL_ID
        val channelName = AppConfig.RAY_NG_CHANNEL_NAME
        // IMPORTANCE_LOW keeps the ongoing notification pinned and visible (with the live
        // uptime chronometer) while staying silent.
        val chan = NotificationChannel(
            channelId,
            channelName, NotificationManager.IMPORTANCE_LOW
        )
        chan.lightColor = Color.DKGRAY
        chan.setShowBadge(false)
        chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        getNotificationManager()?.createNotificationChannel(chan)
        return channelId
    }

    /**
     * The channel to post on, remembered so a state change does not pay for the channel call.
     *
     * Below O there is no channel and the ID is the empty string:
     * https://developer.android.com/reference/android/support/v4/app/NotificationCompat.Builder.html#NotificationCompat.Builder(android.content.Context)
     */
    private fun ensureChannel(service: Service): String {
        channelId?.let { return it }
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                createNotificationChannel()
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "showNotification: failed to create channel", e)
                AppConfig.RAY_NG_CHANNEL_ID
            }
        } else {
            ""
        }
        channelId = resolved
        return resolved
    }

    /**
     * Rebuilds the row for a NEW state and posts it — once, and only if it differs.
     *
     * State changes are rare (a connect, a pause, a resume), so rebuilding the builder here is
     * cheaper than keeping one mutable builder honest across three shapes — and it is the only
     * place the action PendingIntents are re-minted, which is a binder call each and must never
     * land on the three-second tick. That tick goes through [pushSpeed], which mutates.
     */
    private fun post(service: Service, state: Shade, downPerSec: Long, upPerSec: Long) {
        val target = Posted(state, downPerSec, upPerSec)
        if (lastPushed == target) return
        val channel = channelId ?: return // nothing has been shown yet; startForeground comes first
        try {
            val notification = buildRichNotification(service, channel, state, downPerSec, upPerSec)
            getNotificationManager()?.notify(NOTIFICATION_ID, notification)
            lastPushed = target
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "post: failed to update the ongoing notification", e)
        }
    }

    /**
     * Writes the live rate into the EXPANDED notification and re-posts it.
     *
     * The line goes on [NotificationCompat.BigTextStyle] alone and the content text stays unset, so
     * the collapsed row keeps carrying the server and the chronometer and nothing else. `bigText`
     * is what the expanded view renders; `setContentText` is what the collapsed one does, and the
     * two are deliberately not the same string here.
     */
    private fun pushSpeed(downPerSec: Long, upPerSec: Long) {
        // A rate is a fact about a RUNNING tunnel. A tick that lands in the gap between the pause
        // and the job noticing it must not repaint a paused row with a speed line.
        if (currentState() != Shade.RUNNING) return
        val builder = mBuilder ?: return
        val service = getService() ?: return
        applySpeed(builder, service, downPerSec, upPerSec)
        getNotificationManager()?.notify(NOTIFICATION_ID, builder.build())
        lastPushed = Posted(Shade.RUNNING, downPerSec, upPerSec)
    }

    /** The expanded view's one line: «↓ 1,2 МБ/с   ↑ 240 КБ/с». */
    private fun applySpeed(
        builder: NotificationCompat.Builder,
        context: Context,
        downPerSec: Long,
        upPerSec: Long,
    ) {
        // The same formatter Главная and every other rate in the product uses, so one speed can
        // never read two different ways in one app.
        val line = context.getString(
            R.string.notification_speed,
            downPerSec.toSpeedString(),
            upPerSec.toSpeedString(),
        )
        builder.setStyle(NotificationCompat.BigTextStyle().bigText(line))
    }

    /**
     * Gets the notification manager.
     * @return The notification manager.
     */
    private fun getNotificationManager(): NotificationManager? {
        if (mNotificationManager == null) {
            val service = getService() ?: return null
            mNotificationManager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        }
        return mNotificationManager
    }

    /**
     * Updates the speed notification once.
     * Queries traffic stats, separates proxy and direct, and updates the notification.
     *
     * ## A NOTIFICATION THAT SAYS THE SAME THING IS NOT RE-POSTED
     *
     * This runs every [QUERY_INTERVAL_MS] for as long as a tunnel is up — 1200 rounds an hour, and
     * a tunnel is up for hours — and it used to end every single one of them with a
     * `NotificationManager.notify()`, i.e. a binder round trip into system_server that re-inflates
     * the row, whether or not a digit had changed. On an idle tunnel every one of those posted the
     * identical «↓ 0 КБ/с ↑ 0 КБ/с».
     *
     * Upstream had a guard for exactly this and this fork kept only its plumbing: `lastZeroSpeed`
     * was threaded in and out of this function and never branched on. It is replaced by the
     * stronger form of the same idea — remember what was actually pushed, and post only when the
     * new one differs — which also covers a steady rate, not just a zero one. [showNotification]
     * clears the memo when it rebuilds the builder, so a fresh row always gets its line back. The
     * memo carries the STATE as well as the rate, so «Пауза» always repaints the row and a paused
     * row is never repainted twice.
     *
     * THE BROADCAST IS NOT GUARDED, and must not be: `MSG_STATE_SPEED_UPDATE` is Главная's only
     * source for the two figures, it is a local broadcast rather than a system call, and swallowing
     * it would freeze the strip on the last rate the tunnel ever saw.
     */
    private fun updateSpeedNotificationOnce() {
        val queryTime = System.currentTimeMillis()
        val sinceLastQueryIn = (queryTime - lastQueryTime)

        // If the query interval is too short, skip this round to avoid excessive CPU usage.
        //
        // THE WINDOW'S START STAYS WHERE IT IS. The counters were not read, so they still hold
        // everything since the last query; moving the start to now handed the next query those
        // bytes over a window it no longer measured, and the rate came out up to twice the truth.
        if (sinceLastQueryIn < QUERY_INTERVAL_MS) {
            LogUtil.w(AppConfig.TAG, "Query interval too short: ${sinceLastQueryIn}ms, skipping")
            return
        }

        // THE DIRECT SIDE IS NOT SUMMED ANY MORE. Upstream separates proxied from direct traffic
        // because it reports both; this product reports one rate — what went through the tunnel —
        // and the direct pair was accumulated on every tick, over every outbound the core knows,
        // and then read by nothing but a "is everything zero" test that itself had no reader.
        var proxyUplink = 0L
        var proxyDownlink = 0L

        CoreServiceManager.queryAllOutboundTrafficStats().forEach { stat ->
            if (!stat.tag.startsWith(AppConfig.TAG_PROXY)) return@forEach
            when (stat.direction) {
                AppConfig.UPLINK -> proxyUplink += stat.value
                AppConfig.DOWNLINK -> proxyDownlink += stat.value
            }
        }

        // ONE TICK, TWO CONSUMERS. The rate goes to Главная's ledger and, since the owner asked for
        // it, to the expanded notification — from the SAME measurement at the SAME interval. Adding
        // a second job or a faster push for the shade would have paid twice for one number.
        //
        // The first query after the screen comes back is a catch-up, not a rate: see [TrafficRate].
        // It still ran — it is what empties the counters — but what it publishes is zero.
        getService()?.let { svc ->
            val downPerSec = TrafficRate.perSecond(proxyDownlink, sinceLastQueryIn, QUERY_INTERVAL_MS)
            val upPerSec = TrafficRate.perSecond(proxyUplink, sinceLastQueryIn, QUERY_INTERVAL_MS)
            MessageUtil.sendMsg2UI(svc, AppConfig.MSG_STATE_SPEED_UPDATE, longArrayOf(downPerSec, upPerSec))
            if (Posted(Shade.RUNNING, downPerSec, upPerSec) != lastPushed) pushSpeed(downPerSec, upPerSec)
        }

        lastQueryTime = queryTime
    }

    /**
     * Gets the service instance.
     * @return The service instance.
     */
    private fun getService(): Service? {
        return CoreServiceManager.serviceControl?.get()?.getService()
    }
}
