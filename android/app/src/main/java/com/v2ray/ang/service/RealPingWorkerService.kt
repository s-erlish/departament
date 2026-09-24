package com.v2ray.ang.service

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Worker that runs a batch of real-ping tests independently.
 * Each batch owns its own CoroutineScope/dispatcher and can be cancelled separately.
 */
class RealPingWorkerService(
    private val context: Context,
    private val guids: List<String>,
    private val onEvent: (RealPingEvent) -> Unit = {}
) {
    private val job = SupervisorJob()
    private val concurrency = SettingsManager.getRealPingConcurrency()
    private val dispatcher = Executors.newFixedThreadPool(concurrency).asCoroutineDispatcher()
    private val scope = CoroutineScope(job + dispatcher + CoroutineName("RealPingBatchWorker"))

    /**
     * THE TWO PROGRESS COUNTERS ARE GONE WITH THE EVENT THEY FED.
     *
     * They produced «$left / $count» once per finished server, and neither number was what its name
     * said: `totalCount` was incremented per job and then DECREMENTED on completion, so the
     * "total" counted down as the batch ran. It did not matter, because nothing printed the string
     * — see `CoreTestService.handleWorkerEvent`. What it cost was one cross-process broadcast per
     * server and, at the far end, a full list rebuild. Results and the finish are what the app
     * reads, and both are still sent.
     */
    fun start() {
        val jobs = guids.map { guid ->
            scope.launch {
                try {
                    val result = startRealPing(guid)
                    onEvent(RealPingEvent.Result(guid, result))
                } catch (_: Throwable) {
                    // ignore
                }
            }
        }

        scope.launch {
            try {
                joinAll(*jobs.toTypedArray())
                onEvent(RealPingEvent.Finish("0"))
            } catch (_: CancellationException) {
                onEvent(RealPingEvent.Finish("-1"))
            } finally {
                close()
            }
        }
    }

    fun cancel() {
        job.cancel()
    }

    private fun close() {
        try {
            dispatcher.close()
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun startRealPing(guid: String): Long {
        val retFailure = -1L
        val configResult = CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
        if (!configResult.status) {
            // Surface *why* the test config could not be built (unsupported transport,
            // empty outbounds, template decode failure, ...) instead of a blind -1.
            LogUtil.w(
                AppConfig.TAG,
                "Real-ping skipped for $guid: ${configResult.errorMessage.ifBlank { "test config build failed" }}"
            )
            return retFailure
        }
        val delay = CoreNativeManager.measureOutboundDelay(configResult.content, SettingsManager.getDelayTestUrl())
        if (delay < 0) {
            LogUtil.w(AppConfig.TAG, "Real-ping got no latency for $guid (native measureOutboundDelay returned $delay)")
        }
        return delay
    }
}
