package com.v2ray.ang.handler

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.NotificationHelper
import com.v2ray.ang.ui.CheckUpdateActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Замечает новую версию приложения без того, чтобы человек сам шёл в «Проверить обновления».
 *
 * Владелец: «чтобы при заходе показывало, что вышло обновление и в уведомлениях тоже чтобы
 * приходило раз проверяет раз в час с гитхаба», и потом: «чтобы была не плашка сверху, а появлялось
 * как бы целое окошко во весь экран, которое предлагает обновиться … но и мог закрыть это окно».
 * Отсюда три пути к одной и той же ленте ([UpdateCheckerManager.checkForUpdate]):
 *
 *  - **при входе в приложение** — [install]: окно «Вышла версия X» на весь экран (UpdateOfferActivity,
 *    один раз за запуск приложения, см. [takeOffer]), подпись у «Проверить обновления» и точка на
 *    вкладке «Настройки»;
 *  - **раз в час в фоне** — [CheckTask] через WorkManager, с сетью: если приложение этой версии ещё
 *    не показывало, приходит уведомление;
 *  - **экран «Проверить обновления»** — [remember] записывает то, что нашёл он.
 *
 * Найденная версия хранится в MMKV ([AppConfig.PREF_APP_UPDATE_VERSION]) и читается всеми
 * поверхностями через [availableVersion]. Хранимое сверяется с запущенной версией при каждом чтении,
 * поэтому после установки обновления плашка и точка пропадают сами, до всякой новой проверки.
 *
 * Ошибка сети или пустая лента тут не ошибка: фоновая проверка молчит, а экран обновлений, где
 * человек спросил сам, по-прежнему объясняет причину.
 */
object AppUpdateWatcher {

    private const val WORK_NAME = "app_update_check"
    private const val CHECK_INTERVAL_MINUTES = 60L

    /** Не больше одной проверки при входе за раз. */
    private val entryCheckInFlight = AtomicBoolean(false)

    /** Сколько окон приложения сейчас на экране (onStart − onStop). Только главный поток. */
    private var startedActivities = 0

    /** Последнее ушедшее окно ушло из-за поворота экрана: его возвращение — не вход. */
    private var stoppedForConfigChange = false

    /** Кому сказать, что проверка при входе ответила: главному окну, пока оно живо. */
    @Volatile
    private var entryListener: (() -> Unit)? = null

    /** Постановка работы не на главном потоке: RemoteWorkManager строит свою базу и будит `:bg`. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Новее запущенной найденная версия, или null. */
    fun availableVersion(): String? {
        val version = MmkvManager.decodeSettingsString(AppConfig.PREF_APP_UPDATE_VERSION)
            ?.takeIf { it.isNotBlank() } ?: return null
        return version.takeIf { UpdateCheckerManager.compareVersions(it, BuildConfig.VERSION_NAME) > 0 }
    }

    /** Описание найденной версии, как его прислал GitHub. */
    fun notes(): String? = MmkvManager.decodeSettingsString(AppConfig.PREF_APP_UPDATE_NOTES)

    /** Какую версию окно уже предлагало в этом запуске приложения. */
    private val offeredInProcess = AtomicReference<String?>(null)

    /**
     * Версия, которую пора предложить окном, — или null. Окно показывается раз за запуск приложения:
     * «Не сейчас» закрывает его до следующего запуска, а точка на «Настройках» и подпись у «Проверить
     * обновления» остаются. Раз окно показано, уведомление об этой версии уже лишнее.
     */
    fun takeOffer(): String? {
        val version = availableVersion() ?: return null
        if (offeredInProcess.getAndSet(version) == version) return null
        MmkvManager.encodeSettings(AppConfig.PREF_APP_UPDATE_ANNOUNCED, version)
        return version
    }

    /**
     * Ставит часовую проверку в фоне. Уже стоящую не трогает (KEEP), так что каждый запуск приложения
     * её не сдвигает.
     */
    fun schedule(context: Context = AngApplication.application) {
        scope.launch {
            try {
                val request = PeriodicWorkRequestBuilder<CheckTask>(CHECK_INTERVAL_MINUTES, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInitialDelay(CHECK_INTERVAL_MINUTES, TimeUnit.MINUTES)
                    .build()
                RemoteWorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "AppUpdateWatcher: could not schedule the hourly check", e)
            }
        }
    }

    /**
     * Проверка при каждом входе в приложение — владелец: «надо, чтобы при входе в приложение как раз
     * был запрос, даже если ты подключен». Вход — это когда на экране появляется первое окно
     * приложения: запуск, возвращение с рабочего стола, из недавних или из другого приложения.
     * Переходы между экранами самого приложения и поворот экрана входом не считаются.
     *
     * Раньше проверку делало главное окно в onResume, сначала не чаще раза в час, потом раз в пять
     * минут, — и владелец на телефоне оба раза видел, что вернувшись в приложение, он о новой версии
     * не узнаёт. Туннель тут не помеха: лента спрашивается напрямую, а не ответила — через
     * локальный прокси (UpdateCheckerManager.fetch).
     */
    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                if (startedActivities == 1 && !stoppedForConfigChange) checkOnEntry()
                stoppedForConfigChange = false
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = maxOf(0, startedActivities - 1)
                stoppedForConfigChange = activity.isChangingConfigurations
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /**
     * Главное окно подписывается, чтобы после ответа перерисовать точку на «Настройках» и предложить
     * новую версию окном.
     */
    fun setEntryListener(listener: () -> Unit) {
        entryListener = listener
    }

    /** Отписывает [listener], если подписан именно он. */
    fun clearEntryListener(listener: () -> Unit) {
        if (entryListener === listener) entryListener = null
    }

    private fun checkOnEntry() {
        if (!entryCheckInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                check()
                withContext(Dispatchers.Main) { entryListener?.invoke() }
            } finally {
                entryCheckInFlight.set(false)
            }
        }
    }

    /** Экран «Проверить обновления» нашёл версию (или не нашёл) — остальные поверхности узнают то же. */
    fun remember(latestVersion: String?, notes: String? = null) {
        MmkvManager.encodeSettings(AppConfig.PREF_APP_UPDATE_VERSION, latestVersion.orEmpty())
        MmkvManager.encodeSettings(AppConfig.PREF_APP_UPDATE_NOTES, if (latestVersion == null) "" else notes.orEmpty())
        MmkvManager.encodeSettings(AppConfig.PREF_APP_UPDATE_CHECKED_AT, System.currentTimeMillis())
    }

    /**
     * Спрашивает ленту и записывает ответ. Возвращает новую версию или null — и когда её нет, и когда
     * ответа не было: тогда записанное прошлой проверкой остаётся как есть.
     */
    private suspend fun check(): String? {
        val includePreRelease = MmkvManager.decodeSettingsBool(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, false)
        val result = try {
            UpdateCheckerManager.checkForUpdate(includePreRelease)
        } catch (e: Exception) {
            LogUtil.i(AppConfig.TAG, "AppUpdateWatcher: no answer from the feed (${e.javaClass.simpleName})")
            return null
        }
        val version = result.latestVersion?.takeIf { result.hasUpdate }
        remember(version, result.releaseNotes)
        return version
    }

    /**
     * Часовая проверка в фоне. Работает в `:bg`, как и обновление подписки. Молчит о версии, которую
     * уже предложило окно при заходе (PREF_APP_UPDATE_ANNOUNCED).
     */
    class CheckTask(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

        override suspend fun doWork(): Result {
            val version = check()
            if (version == null) {
                // Обновление поставили (или выпуск убрали) — висящее уведомление больше не о чем.
                if (availableVersion() == null) {
                    NotificationHelper.cancel(NotificationChannelType.APP_UPDATE, applicationContext)
                }
                return Result.success()
            }
            if (version == MmkvManager.decodeSettingsString(AppConfig.PREF_APP_UPDATE_ANNOUNCED)) {
                return Result.success()
            }
            MmkvManager.encodeSettings(AppConfig.PREF_APP_UPDATE_ANNOUNCED, version)
            val context = applicationContext
            val tap = PendingIntent.getActivity(
                context,
                0,
                Intent(context, CheckUpdateActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            NotificationHelper.notifyAppUpdate(
                context,
                context.getString(R.string.app_update_notification_title, version),
                context.getString(R.string.app_update_notification_text),
                tap,
            )
            LogUtil.i(AppConfig.TAG, "AppUpdateWatcher: announced $version")
            return Result.success()
        }
    }
}
