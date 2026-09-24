package com.v2ray.ang.handler

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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

/**
 * Замечает новую версию приложения без того, чтобы человек сам шёл в «Проверить обновления».
 *
 * Владелец: «чтобы при заходе показывало, что вышло обновление и в уведомлениях тоже чтобы
 * приходило раз проверяет раз в час с гитхаба». Отсюда три пути к одной и той же ленте
 * ([UpdateCheckerManager.checkForUpdate]):
 *
 *  - **при заходе** — [checkOnLaunch], не чаще раза в час: плашка «Вышла версия X» на главной, подпись
 *    у «Проверить обновления» и точка на вкладке «Настройки»;
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
    private const val CHECK_INTERVAL_MS = CHECK_INTERVAL_MINUTES * 60_000L

    /** Постановка работы не на главном потоке: RemoteWorkManager строит свою базу и будит `:bg`. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Новее запущенной найденная версия, или null. */
    fun availableVersion(): String? {
        val version = MmkvManager.decodeSettingsString(AppConfig.PREF_APP_UPDATE_VERSION)
            ?.takeIf { it.isNotBlank() } ?: return null
        return version.takeIf { UpdateCheckerManager.compareVersions(it, BuildConfig.VERSION_NAME) > 0 }
    }

    /** Показывать ли плашку на главной: версия есть и её плашку не закрывали. */
    fun bannerVersion(): String? =
        availableVersion()?.takeIf { it != MmkvManager.decodeSettingsString(AppConfig.PREF_APP_UPDATE_DISMISSED) }

    /** Крестик на плашке: до следующей версии она не вернётся. Подпись и точка в настройках остаются. */
    fun dismissBanner(version: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_APP_UPDATE_DISMISSED, version)
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
     * Проверка при заходе в приложение. Раньше чем через час после прошлой проверки в сеть не ходит:
     * найденное тогда уже лежит в [availableVersion]. [onChecked] зовётся на главном потоке после
     * проверки — чтобы экраны перерисовались.
     */
    fun checkOnLaunch(scope: CoroutineScope, onChecked: () -> Unit) {
        val last = MmkvManager.decodeSettingsLong(AppConfig.PREF_APP_UPDATE_CHECKED_AT, 0L)
        val now = System.currentTimeMillis()
        if (last in 1..now && now - last < CHECK_INTERVAL_MS) return
        scope.launch {
            val found = check()
            // Приложение открыто и само покажет плашку — уведомление об этой версии уже лишнее.
            if (found != null) MmkvManager.encodeSettings(AppConfig.PREF_APP_UPDATE_ANNOUNCED, found)
            withContext(Dispatchers.Main) { onChecked() }
        }
    }

    /** Экран «Проверить обновления» нашёл версию (или не нашёл) — остальные поверхности узнают то же. */
    fun remember(latestVersion: String?) {
        MmkvManager.encodeSettings(AppConfig.PREF_APP_UPDATE_VERSION, latestVersion.orEmpty())
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
        remember(version)
        return version
    }

    /** Часовая проверка в фоне. Работает в `:bg`, как и обновление подписки. */
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
