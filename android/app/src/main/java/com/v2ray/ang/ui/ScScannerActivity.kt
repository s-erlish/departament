package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The launcher shortcut «Сканировать QR»: scan a подписка's QR without opening the app first.
 *
 * It is one of the four ways to ADD a подписка and it must behave exactly like the add menu's own
 * QR route — same importer, same outcomes, same words.
 */
class ScScannerActivity : HelperBaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_none)
        importQRcode()
    }

    private fun importQRcode() {
        launchQRCodeScanner { scanResult ->
            if (scanResult == null) {
                // A cancelled scan is not a failure: nothing is reported and nothing is imported.
                finish()
                return@launchQRCodeScanner
            }
            // OFF THE MAIN THREAD, AND THAT IS THE WHOLE POINT OF THIS COROUTINE.
            //
            // `AngConfigManager.importBatchConfig` fetches the подписка over HTTP the moment the
            // scanned text is a subscription link — importBatchConfig -> updateConfigViaSub ->
            // `HttpUtil.getUrlContentWithUserAgentEx` — and Android answers a network call made on
            // the main thread with NetworkOnMainThreadException, a RuntimeException nothing on this
            // path catches. Every QR the departament bot hands out IS a subscription link, so this
            // shortcut crashed on the only input it exists for.
            //
            // The in-app add menu has always imported on Dispatchers.IO
            // (`MainActivity.importBatchConfig`); this route was the one that did not.
            //
            // И ДОБАВЛЯЕТ, КАК ТО ЖЕ МЕНЮ (append = true). Здесь стояло `false` - «заменить», а без
            // подписки заменялась корзина серверов, добавленных руками: сканирование одного сервера
            // удаляло все остальные. @see AngConfigManager.replacesServers
            lifecycleScope.launch(Dispatchers.IO) {
                val outcome = runCatching { AngConfigManager.importBatchConfig(scanResult, "", true) }
                withContext(Dispatchers.Main) {
                    outcome
                        .onSuccess { showImportResult(it) }
                        .onFailure {
                            LogUtil.e(AppConfig.TAG, "Failed to import scanned config", it)
                            toastError(R.string.notice_add_failed)
                        }
                    startActivity(Intent(this@ScScannerActivity, MainActivity::class.java))
                    finish()
                }
            }
        }
    }

    /**
     * The same outcomes, and the same silence on success, as `MainActivity.showImportResult` — the
     * QR route is the one the owner was looking at when he asked for the layer to go, so the two
     * paths cannot answer differently.
     */
    private fun showImportResult(result: AngConfigManager.ImportResult) {
        when {
            // Added, but the подписка fetched nothing: Главная will open on an empty state, which
            // does not explain itself. Everything else that succeeded is visible on that screen.
            result.countSub > 0 ->
                if ((result.subFetch?.configCount ?: 0) <= 0) toastError(R.string.import_sub_empty)

            result.count > 0 -> Unit
            result.subDuplicate -> toast(R.string.import_sub_duplicate)
            result.subRejected -> toast(R.string.import_sub_foreign)
            else -> toastError(R.string.notice_add_failed)
        }
    }
}
