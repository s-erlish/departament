package com.v2ray.ang.handler

import android.annotation.SuppressLint
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import androidx.work.workDataOf
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object SubscriptionUpdater {

    /** Launch-time work runs once per process, not on every activity recreate. */
    private val launchTasksRun = AtomicBoolean(false)

    /**
     * Where the scheduling runs, which is NOT the caller's thread.
     *
     * Both callers hand this the main thread at the worst possible moment: `MainActivity.onCreate`,
     * before the first frame, and `BootReceiver.onReceive`, inside a broadcast's ten-second budget.
     * And the work is not cheap — every подписка is read and parsed out of MMKV, then
     * `RemoteWorkManager.getInstance()` builds WorkManager's whole apparatus (a Room database, a
     * two-thread executor, a `JobScheduler` binder call) and each enqueue binds the
     * `RemoteWorkManagerService`, which STARTS THE `:bg` PROCESS. None of that has a deadline, and
     * none of it draws anything.
     *
     * The process-wide object owns the scope on purpose: the schedule must survive the activity
     * that asked for it, and a Routine cancelled halfway leaves a подписка with no timer behind it.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // -------------------------------------------------------------------------
    // Public API — the only methods external callers should ever use
    // -------------------------------------------------------------------------

    /**
     * Sync all subscription tasks with current settings.
     *
     * Startup/boot callers should use the default mode so existing periodic work is kept.
     * Use forceReschedule=true only when the next run time needs to be recalculated from
     * the latest persisted subscription state (for example after a manual refresh); that mode also
     * skips the once-per-process launch tasks in [runLaunchTasks].
     * Call from: MainActivity.onCreate(), BootReceiver.onReceive().
     */
    fun sync(
        context: Context = AngApplication.application,
        forceReschedule: Boolean = false
    ) {
        val existingWorkPolicy =
            if (forceReschedule) {
                ExistingPeriodicWorkPolicy.REPLACE
            } else {
                ExistingPeriodicWorkPolicy.KEEP
            }

        // The default mode is the app's launch/boot entry point (see above), which is also where
        // the provider screen's "at launch" promises have to be kept. A settings change reschedules
        // with forceReschedule=true and must not replay them.
        val launchTasks = !forceReschedule && launchTasksRun.compareAndSet(false, true)

        // THE ORDERING STAYS ON THE CALLER'S THREAD, and only the ordering. `MainActivity.onCreate`
        // loads the server list on the line after this call, so a sort that landed later would
        // repaint a list the user had already been shown. On the default order — which is what the
        // provider screen now leaves every install on — it is one settings read and a return.
        if (launchTasks) {
            SettingsManager.applyServerSortOrder()
        }

        scope.launch {
            MmkvManager.decodeSubscriptions().forEach { sub ->
                scheduleOne(
                    context = context,
                    subId = sub.guid,
                    shouldRun = shouldAutoUpdate(sub),
                    existingWorkPolicy = existingWorkPolicy
                )
            }
            LogUtil.i(
                AppConfig.TAG,
                "SubscriptionUpdater: sync complete forceReschedule=$forceReschedule"
            )

            if (launchTasks) {
                if (SettingsManager.isUpdateSubscriptionOnLaunch()) {
                    updateAllNow(context)
                }
                if (SettingsManager.isPingOnLaunch()) {
                    requestLatencyTest(context)
                }
            }
        }
    }

    /**
     * Sync a single subscription's task.
     * Call from: SubEditActivity after saving, after a manual update (to reset the timer).
     */
    fun syncOne(context: Context = AngApplication.application, subId: String) {
        val subItem = MmkvManager.decodeSubscription(subId) ?: return
        scheduleOne(
            context = context,
            subId = subId,
            shouldRun = shouldAutoUpdate(SubscriptionCache(subId, subItem)),
            existingWorkPolicy = ExistingPeriodicWorkPolicy.REPLACE
        )
    }

    // -------------------------------------------------------------------------
    // What counts as a подписка, and what schedule a new one starts on
    // -------------------------------------------------------------------------

    /**
     * НАСТОЯЩАЯ ПОДПИСКА - та, которую есть откуда качать.
     *
     * В списке подписок лежит ещё и служебная корзина `__default_subscription__`
     * (`SettingsManager.ensureDefaultSubscription`) - место для серверов без подписки, без адреса,
     * с `autoUpdate = true` по умолчанию. Расписание её не отличало: на каждой установке раз в час
     * просыпался `:bg`, в шторке мелькало «Обновляем подписку», обновление пропускалось за
     * отсутствием адреса, а следом, по «Проверять пинг после обновления», запрашивалась проверка
     * задержки. И она же, первая в списке, отвечала экрану настроек, «какое сейчас автообновление».
     */
    internal fun isRealSubscription(sub: SubscriptionCache): Boolean =
        sub.guid != AppConfig.DEFAULT_SUBSCRIPTION_ID && sub.subscription.url.isNotBlank()

    /** Обновлять ли [sub] по расписанию: настоящая, включённая и с включённым автообновлением. */
    internal fun shouldAutoUpdate(sub: SubscriptionCache): Boolean =
        isRealSubscription(sub) && sub.subscription.enabled && sub.subscription.autoUpdate

    /**
     * Расписание, в котором сейчас стоит «Настройки → Автообновление подписки»: включено ли и раз
     * в сколько минут, или null, когда настоящих подписок нет.
     *
     * Отдельного ключа у настройки нет нарочно (см. `ProviderSettingsActivity`): оба экрана пишут
     * выбор в каждую подписку, и вторая копия могла бы разойтись с тем, по чему работает расписание.
     * Значит, и прочитать выбор можно только с подписок: любая, что обновляется сама, - её
     * интервал; ни одной - выключено (с интервалом первой, чтобы включение вернуло прежний).
     */
    internal fun scheduleOf(subs: List<SubscriptionCache>): Pair<Boolean, Long>? {
        val real = subs.filter { isRealSubscription(it) }
        val source = real.firstOrNull { it.subscription.autoUpdate } ?: real.firstOrNull() ?: return null
        return source.subscription.autoUpdate to source.subscription.updateInterval
    }

    /** [scheduleOf] для того, что лежит в хранилище. */
    fun currentSchedule(): Pair<Boolean, Long>? = scheduleOf(MmkvManager.decodeSubscriptions())

    /**
     * НОВАЯ ПОДПИСКА ВСТАЁТ В РАСПИСАНИЕ, КОТОРОЕ ЧЕЛОВЕК УЖЕ ВЫБРАЛ.
     *
     * Выбор в настройках записывается в подписки, которые есть в этот момент. Добавленная после
     * него - из буфера, по QR-коду, с телевизора, из аккаунта - получала умолчание
     * `SubscriptionItem`: включено, раз в час. Кто выбрал «Выключено» или «Раз в сутки», получал
     * ещё одну подписку, которая качается каждый час, а строка настроек при этом показывала первую
     * попавшуюся. Когда настоящих подписок ещё нет, остаётся умолчание.
     */
    fun applyCurrentSchedule(item: SubscriptionItem) {
        val (enabled, minutes) = currentSchedule() ?: return
        item.autoUpdate = enabled
        item.updateInterval = minutes
    }

    /**
     * Cancel the auto-update task for a single subscription.
     * Call from: when a subscription is deleted.
     */
    fun cancelOne(context: Context = AngApplication.application, subId: String) {
        RemoteWorkManager.getInstance(context)
            .cancelUniqueWork(taskName(subId))
    }

    // -------------------------------------------------------------------------
    // Internal scheduling logic
    // -------------------------------------------------------------------------

    private fun taskName(subId: String) = "${AppConfig.SUBSCRIPTION_UPDATE_TASK_NAME}_$subId"

    /**
     * Enqueues an immediate one-shot refresh of every enabled subscription, leaving the periodic
     * schedule untouched. It ignores per-subscription auto-update on purpose: "update on launch" is
     * its own promise to the user, not a second switch on top of that one.
     */
    private fun updateAllNow(context: Context) {
        val rw = RemoteWorkManager.getInstance(context)
        MmkvManager.decodeSubscriptions()
            .filter { isRealSubscription(it) && it.subscription.enabled }
            .forEach { sub ->
                val request = OneTimeWorkRequestBuilder<UpdateTask>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .setInputData(workDataOf(KEY_SUB_ID to sub.guid, KEY_FORCE to true))
                    .addTag(AppConfig.SUBSCRIPTION_UPDATE_TASK_NAME)
                    .build()

                rw.enqueueUniqueWork(
                    "${taskName(sub.guid)}_launch",
                    ExistingWorkPolicy.KEEP,
                    request
                )
                LogUtil.i(AppConfig.TAG, "SubscriptionUpdater: launch refresh enqueued for ${sub.guid}")
            }
    }

    /**
     * Runs the batch latency test for [subId] (all servers when empty) through the same test
     * service the servers screen uses, so results land in the one place every screen reads.
     *
     * Android can refuse to start that foreground service from the background, in which case
     * [MessageUtil] logs it and the previous results simply stand.
     */
    private fun requestLatencyTest(context: Context, subId: String = "") {
        MessageUtil.sendMsg2TestService(
            context,
            TestServiceMessage(key = AppConfig.MSG_MEASURE_CONFIG_START, subscriptionId = subId)
        )
    }

    private fun scheduleOne(
        context: Context,
        subId: String,
        shouldRun: Boolean,
        existingWorkPolicy: ExistingPeriodicWorkPolicy
    ) {
        val rw = RemoteWorkManager.getInstance(context)
        if (!shouldRun) {
            rw.cancelUniqueWork(taskName(subId))
            LogUtil.d(AppConfig.TAG, "SubscriptionUpdater: cancelled task for $subId")
            return
        }

        val subItem = MmkvManager.decodeSubscription(subId) ?: return

        val intervalMinutes = maxOf(
            AppConfig.SUBSCRIPTION_MIN_INTERVAL_MINUTES,
            subItem.updateInterval
        )

        // Base initial delay on the last successful update time persisted in subscription.
        val lastUpdated = subItem.lastUpdated
        val intervalMillis = intervalMinutes * 60 * 1000L
        val now = System.currentTimeMillis()
        val initialDelayMillis = if (lastUpdated <= 0L) {
            0L
        } else {
            maxOf(0L, lastUpdated + intervalMillis - now)
        }

        val request = PeriodicWorkRequestBuilder<UpdateTask>(intervalMinutes, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(workDataOf(KEY_SUB_ID to subId))
            .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
            .addTag(AppConfig.SUBSCRIPTION_UPDATE_TASK_NAME)
            .build()

        rw.enqueueUniquePeriodicWork(
            taskName(subId),
            existingWorkPolicy,
            request
        )

        LogUtil.i(
            AppConfig.TAG,
            "SubscriptionUpdater: scheduled [$subId] interval=${intervalMinutes}min " +
                    "initialDelay=${initialDelayMillis / 1000}s policy=$existingWorkPolicy"
        )
    }

    // -------------------------------------------------------------------------
    // Worker
    // -------------------------------------------------------------------------

    private const val KEY_SUB_ID = "subId"

    /** Set by [updateAllNow]: refresh even when this subscription does not auto-update. */
    private const val KEY_FORCE = "force"

    /**
     * Сколько обновление при запуске считает подписку только что скачанной. Две минуты - это
     * «одновременно» для двух путей, которые сходятся на старте, и ничего сверх того: обещание
     * «обновлять при запуске» - это свежие данные на каждом запуске.
     */
    private const val LAUNCH_FRESH_WINDOW_MS = 2 * 60_000L

    /**
     * Скачана ли подписка так недавно, что ещё одна загрузка ничего не добавит.
     *
     * Время из будущего (часы переводили назад) свежим не считается, иначе подписка не
     * обновлялась бы, пока часы его не догонят.
     */
    internal fun isFresh(lastUpdated: Long, now: Long, windowMillis: Long): Boolean =
        lastUpdated in 1..now && now - lastUpdated < windowMillis

    /**
     * Окно свежести для задачи: для обновления при запуске - [LAUNCH_FRESH_WINDOW_MS], для
     * расписания - половина интервала. Задача по расписанию просыпается раз в интервал, и
     * обновление, случившееся в первой половине этого интервала (вручную, импортом аккаунта,
     * при запуске), свою работу за этот период уже сделало. Дольше полутора интервалов подписка
     * от этого без обновления не остаётся.
     */
    internal fun freshWindowMillis(force: Boolean, intervalMinutes: Long): Long =
        if (force) {
            LAUNCH_FRESH_WINDOW_MS
        } else {
            maxOf(AppConfig.SUBSCRIPTION_MIN_INTERVAL_MINUTES, intervalMinutes) * 60_000L / 2
        }

    /**
     * ОДНА ПОДПИСКА - ОДНО ОБНОВЛЕНИЕ ЗА РАЗ, в пределах `:bg`.
     *
     * Задача по расписанию и обновление при запуске - две разные задачи WorkManager, и на старте
     * они сходятся: та, что по расписанию, просрочена, пока телефон спал, а эта поставлена только
     * что. Обе читали `lastUpdated` до того, как хоть одна его обновила, и обе качали одну и ту же
     * подписку. Под замком вторая ждёт первую, перечитывает подписку и по [isFresh] видит, что
     * качать уже нечего.
     */
    private val refreshLocks = ConcurrentHashMap<String, Mutex>()

    private fun refreshLock(subId: String): Mutex = refreshLocks.computeIfAbsent(subId) { Mutex() }

    class UpdateTask(context: Context, params: WorkerParameters) :
        CoroutineWorker(context, params) {

        override suspend fun doWork(): Result {
            val subId = inputData.getString(KEY_SUB_ID)
            LogUtil.i(AppConfig.TAG, "SubscriptionUpdater automatic update starting: $subId")

            if (subId.isNullOrEmpty()) {
                LogUtil.w(AppConfig.TAG, "SubscriptionUpdater: missing subId in worker input")
                return Result.success()
            }

            return refreshLock(subId).withLock { refresh(subId, inputData.getBoolean(KEY_FORCE, false)) }
        }

        @SuppressLint("MissingPermission")
        private fun refresh(subId: String, force: Boolean): Result {
            val subItem = MmkvManager.decodeSubscription(subId)
            if (subItem == null) {
                LogUtil.w(AppConfig.TAG, "SubscriptionUpdater: no subscription found for $subId")
                return Result.success()
            }

            // До уведомления и до проверки пинга: подписке без адреса или выключенной обновляться
            // нечем, и мигать в шторке «Обновляем подписку» ради пропуска незачем. См.
            // [isRealSubscription]; задачи на такие подписки [sync] уже снимает, это - для тех, что
            // стояли в очереди до этой сборки.
            if (!isRealSubscription(SubscriptionCache(subId, subItem)) || !subItem.enabled) {
                LogUtil.i(AppConfig.TAG, "SubscriptionUpdater: $subId has nothing to fetch, skip")
                return Result.success()
            }

            if (!subItem.autoUpdate && !force) {
                LogUtil.i(AppConfig.TAG, "SubscriptionUpdater: auto-update disabled for $subId, skip")
                return Result.success()
            }

            if (isFresh(subItem.lastUpdated, System.currentTimeMillis(), freshWindowMillis(force, subItem.updateInterval))) {
                LogUtil.i(AppConfig.TAG, "SubscriptionUpdater: $subId was fetched moments ago, skip")
                return Result.success()
            }

            // The global User-Agent fallback is NOT applied here: updateConfigViaSub writes this
            // item back to storage (lastUpdated, provider directives), so anything set on it now
            // would be persisted as that subscription's own User-Agent and would survive clearing
            // the global one. It resolves per-sub → global → operator default at the fetch itself.
            val sub = SubscriptionCache(subId, subItem)

            // Notify about update start.
            //
            // THE NAME IS RESOLVED, NEVER THE RAW REMARK. `subItem.remarks` is storage, and older
            // builds stored upstream's «import sub» in it — which this line then formatted into the
            // shade verbatim, so the user's phone told them «Обновляем «import sub»».
            // [SubscriptionNaming] is the one place that answers what a подписка is called, and it
            // refuses every placeholder; when it has nothing real to offer, the copy becomes a whole
            // sentence instead of quoting an empty string.
            if (SettingsManager.isNotifyOnSubscriptionUpdate()) {
                val name = SubscriptionNaming.nameOf(subItem)
                NotificationHelper.notify(
                    NotificationChannelType.SUBSCRIPTION_UPDATE,
                    applicationContext,
                    applicationContext.getString(R.string.notification_subscription_title),
                    if (name == null) {
                        applicationContext.getString(R.string.msg_updating_subscription_unnamed)
                    } else {
                        applicationContext.getString(R.string.msg_updating_subscription, name)
                    }
                )
            }
            val outcome = AngConfigManager.updateConfigViaSub(sub)

            // Clear notification — unconditional, so one posted before the switch was turned off
            // still goes away.
            NotificationHelper.cancel(NotificationChannelType.SUBSCRIPTION_UPDATE, applicationContext)

            // THE UI IS TOLD, and it has to be: a refresh deletes every profile of this провайдер it
            // does not recognise and mints a new guid for what is new, while the screen keeps a
            // cache of the old ones. Tapping a row from that stale cache stored a guid that no
            // longer exists as the selection, and Главная then said «Выберите сервер в списке ниже»
            // over a full list with the connect object disabled. The card's traffic and «обновлено»
            // moved too. This worker runs in its own process, so a broadcast is the only way to
            // reach the Activity — and it is sent only when something was imported. A refresh that
            // recognised every server rebuilds the same rows under the same guids, so the screen
            // repaints the card and nothing else visibly moves.
            // ЭТО ПОРЯДОК, А НЕ ПОСЛЕДОВАТЕЛЬНОСТЬ СТРОК: сортировка идёт ДО объявления.
            //
            // The refresh rewrote this subscription's server list in the провайдер's order, so a
            // user who chose «по пингу» or «по имени» needs [applyServerSortOrder] to put it back.
            // The broadcast used to go out FIRST, and it is what makes the interface process
            // re-read the list — so the screen was rebuilt from the провайдер's order, and this
            // line then rewrote the store behind it. The chosen sort did not appear until something
            // else reloaded the list, which in practice meant leaving the app and coming back
            // (`MainActivity.onResume` -> `reloadServerListIfStale`, whose list comparison is
            // order-sensitive and so notices). Sorting first makes the announcement describe the
            // store as it will actually be read.
            //
            // …AND IT ONLY RUNS WHEN SOMETHING WAS ACTUALLY IMPORTED, on the same condition as the
            // broadcast. `applyServerSortOrder` reads and REWRITES every подписка's server list, in
            // `:bg`, over MULTI_PROCESS_MODE stores; a refresh that imported nothing — an expired
            // подписка, a disabled one, a провайдер that answered with the same list — has nothing
            // to re-order, and a write from the background process is the one thing that can lose a
            // concurrent write from the interface one.
            if (outcome.configCount > 0) {
                SettingsManager.applyServerSortOrder()
                MessageUtil.sendMsg2UI(applicationContext, AppConfig.MSG_STATE_SERVERS_CHANGED, "")
            }

            // ПИНГ - ПОСЛЕ ОБНОВЛЕНИЯ, А НЕ ПОСЛЕ ПОПЫТКИ. Проверка задержки - это запуск службы
            // переднего плана из фона, и после отказа сети, истёкшей подписки или пропуска мерить
            // нечего нового: серверы те же, что были.
            if (outcome.configCount > 0 && SettingsManager.isPingOnSubscriptionUpdate()) {
                requestLatencyTest(applicationContext, subId)
            }

            return Result.success()
        }
    }
}