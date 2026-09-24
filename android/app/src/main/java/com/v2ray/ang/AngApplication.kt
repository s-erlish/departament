package com.v2ray.ang

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.multidex.MultiDexApplication
import androidx.work.Configuration
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig.ANG_PACKAGE
import com.v2ray.ang.handler.SettingsManager
import java.io.File

/**
 * ЭТОТ КЛАСС СОЗДАЁТСЯ ТРИЖДЫ, А НЕ ОДИН РАЗ.
 *
 * The app runs in three processes and `Application.onCreate` is the first thing that happens in
 * each of them:
 *
 *  - the interface process (`applicationId`), started when the user opens the app;
 *  - `:RunSoLibV2RayDaemon`, started **on every connect** — it hosts the core, the службы, the
 *    плитка, the виджет, the Tasker receiver and the three shortcut trampolines;
 *  - `:bg`, started when WorkManager binds `RemoteWorkManagerService` for a подписка refresh.
 *
 * So everything written here is paid for three times, and two of those times are on paths where
 * the user is waiting for something else entirely. What each process actually needs is different,
 * and this file is now explicit about it instead of running the full first-launch routine in all
 * three.
 */
class AngApplication : MultiDexApplication(), Configuration.Provider {
    companion object {
        lateinit var application: AngApplication
    }

    /**
     * Attaches the base context to the application.
     * @param base The base context.
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        application = this
    }

    /**
     * WorkManager's configuration, handed over **on demand** instead of being installed eagerly.
     *
     * `WorkManager.initialize(this, config)` used to run in `onCreate`, i.e. in all three
     * processes, and building a `WorkManagerImpl` is not free: a Room `WorkDatabase`, a two-thread
     * task executor and a `SystemJobScheduler` (which reaches for the `JobScheduler` system
     * service over binder) per process. The daemon never touches WorkManager at all — nothing on
     * the core side enqueues work — so that whole apparatus was built at every connect and thrown
     * away at every disconnect.
     *
     * Implementing [Configuration.Provider] is what lets it be lazy: the first
     * `WorkManager.getInstance(context)` / `RemoteWorkManager.getInstance(context)` in a process
     * initialises it from here. `androidx.startup`'s own initializer is removed in the manifest, so
     * this is the only configuration source and the `:bg` default-process name still applies.
     */
    override val workManagerConfiguration: Configuration = Configuration.Builder()
        .setDefaultProcessName("${ANG_PACKAGE}:bg")
        .build()

    /**
     * Initializes the application.
     */
    override fun onCreate() {
        super.onCreate()

        // Every process reads and writes the stores, so this one is genuinely for all three.
        MMKV.initialize(this)

        // Настройки → «Оформление» is a preference of the whole install, and the daemon draws too:
        // the three shortcut trampolines are real activities and `startVServiceFromToggle` can put
        // a themed сообщение on screen from there. One read of one key — cheap enough to be right
        // everywhere rather than nearly right in one process.
        SettingsManager.setNightMode()

        // …AND THIS IS THE PART THAT BELONGS TO ONE PROCESS. `initApp` writes the first-launch
        // defaults, seeds «Российские приложения», reads the routing ruleset out of assets when the
        // store has none, and runs three one-shot migrations over the server store. It is
        // first-launch work and upgrade work; the process that has a user in front of it is the one
        // that should do it, once. Running it in the daemon and in `:bg` re-read every flag on
        // every connect and every background refresh, and had two more processes racing to write
        // the same first-run records into MULTI_PROCESS_MODE stores.
        //
        // The daemon cannot be the first process on a fresh install — it is only ever started by
        // the interface, by BootReceiver (which lives in this process), or by the плитка/виджет/
        // ярлык, and all of those need a server already chosen — so the defaults are on disk by the
        // time it runs.
        if (isInterfaceProcess()) {
            SettingsManager.initApp(this)
        }

        // NOTHING CONFIGURES `Toasty` HERE ANY MORE, because nothing uses it. The library's
        // green-tick / red-cross capsules were the upstream notification layer the owner asked to
        // have removed outright; `_Ext.toast*` now routes every one of those call sites through
        // `NoticePolicy`, onto one themed bottom surface. Re-adding a Toasty config here would put
        // the layer back without touching a single call site, which is exactly what the policy
        // object exists to prevent.
    }

    /**
     * Whether this is the interface process — the one whose name is the plain `applicationId`,
     * with no `:suffix`.
     *
     * A name that cannot be read answers YES: the first-launch defaults and the migrations must
     * never be skipped because of a failed lookup, and running them twice is harmless (every one
     * of them is guarded by its own stored flag).
     */
    private fun isInterfaceProcess(): Boolean {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            // /proc/self/cmdline is the process name followed by NULs; no binder call, no
            // ActivityManager round trip. API 27 and below only.
            runCatching {
                File("/proc/self/cmdline").readText().takeWhile { it.code != 0 }.trim()
            }.getOrNull()
        }
        return name == null || name == packageName
    }
}
