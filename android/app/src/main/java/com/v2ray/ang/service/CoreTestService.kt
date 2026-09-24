package com.v2ray.ang.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.NotificationHelper
import java.util.Collections

class CoreTestService : Service() {

    // manage active batch workers so each batch is independent and cancellable
    private val activeWorkers = Collections.synchronizedList(mutableListOf<RealPingWorkerService>())

    /**
     * Initializes the V2Ray environment.
     */
    override fun onCreate() {
        super.onCreate()
        CoreNativeManager.initCoreEnv(this)
    }

    /**
     * Binds the service.
     * @param intent The intent.
     * @return The binder.
     */
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    /**
     * Cleans up resources when the service is destroyed.
     */
    override fun onDestroy() {
        LogUtil.i(AppConfig.TAG, "CoreTestService is being destroyed, cancelling ${activeWorkers.size} active workers")
        // cancel any active workers
        val snapshot = ArrayList(activeWorkers)
        snapshot.forEach { it.cancel() }
        activeWorkers.clear()
        NotificationHelper.stopForeground(this)
        super.onDestroy()
    }

    /**
     * Handles the start command for the service.
     * @param intent The intent.
     * @param flags The flags.
     * @param startId The start ID.
     * @return The start mode.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val message = intent?.serializable<TestServiceMessage>("content")
        if (message == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        when (message.key) {
            AppConfig.MSG_MEASURE_CONFIG_START -> handleMeasureStart(message, startId)
            AppConfig.MSG_MEASURE_CONFIG_CANCEL -> handleMeasureCancel()
            else -> {
                NotificationHelper.stopForeground(this); stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    /**
     * THE SHADE LEARNS NOTHING FROM THIS SERVICE, and the notification below is the smallest thing
     * Android will accept in place of one.
     *
     * The owner, on the counter this used to push there: «переделать уведомления в шторке, этого
     * так быть не должно, там просто должно быть обновление подписки и все». So the title is the
     * app's name and the body is empty — see [NotificationChannelType.CORE_TEST] for why a
     * notification has to exist at all, and what has been done to keep it out of sight.
     */
    private fun handleMeasureStart(message: TestServiceMessage, startId: Int) {
        LogUtil.i(AppConfig.TAG, "CoreTestService starting worker   subscription ${message.subscriptionId}")

        NotificationHelper.startForeground(
            this,
            NotificationChannelType.CORE_TEST,
            getString(R.string.app_name),
            ""
        )

        val guidsList = when {
            message.serverGuids.isNotEmpty() -> message.serverGuids
            message.subscriptionId.isNotEmpty() -> MmkvManager.decodeServerList(message.subscriptionId)
            else -> MmkvManager.decodeAllServerList()
        }

        if (guidsList.isNotEmpty()) {
            lateinit var worker: RealPingWorkerService
            worker = RealPingWorkerService(
                context = this,
                guids = guidsList,
                onEvent = { event -> handleWorkerEvent(event) { activeWorkers.remove(worker) } }
            )
            activeWorkers.add(worker)
            worker.start()
        } else {
            NotificationHelper.stopForeground(this)
            stopSelf(startId)
        }
    }

    private fun handleWorkerEvent(event: RealPingEvent, onWorkerDone: () -> Unit) {
        when (event) {
            // THE BATCH'S PROGRESS TALLY IS NOT REPORTED ANYWHERE, and there is no longer an event
            // for it. It went to the shade once («Запущено проверок: 10 / 10», on the lock screen
            // of a user who had not asked for a check — the провайдер refresh runs one unattended),
            // and after that it went to the app as a broadcast per finished server that no surface
            // printed: the one observer answered it with a full list rebuild it did not need, since
            // `Result` below already names the row that changed. Both the send and the count it
            // carried are gone.

            is RealPingEvent.Result -> {
                MmkvManager.encodeServerTestDelayMillis(event.guid, event.delayMillis)
                MessageUtil.sendMsg2UI(this, AppConfig.MSG_MEASURE_CONFIG_SUCCESS, event.guid)
            }

            is RealPingEvent.Finish -> {
                MessageUtil.sendMsg2UI(this, AppConfig.MSG_MEASURE_CONFIG_FINISH, event.status)
                onWorkerDone()
                if (activeWorkers.isEmpty()) {
                    NotificationHelper.stopForeground(this)
                    stopSelf()
                }
            }
        }
    }

    private fun handleMeasureCancel() {
        LogUtil.i(AppConfig.TAG, "CoreTestService received cancel message, cancelling ${activeWorkers.size} active workers")
        val snapshot = ArrayList(activeWorkers)
        snapshot.forEach { it.cancel() }
        activeWorkers.clear()
        NotificationHelper.stopForeground(this)
        stopSelf()
    }
}