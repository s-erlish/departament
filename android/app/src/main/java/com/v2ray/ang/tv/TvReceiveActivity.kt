package com.v2ray.ang.tv

import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityTvReceiveBinding
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.ui.BaseActivity
import com.v2ray.ang.ui.component.SubPage
import com.v2ray.ang.ui.component.ToolbarBinder
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.QRCodeDecoder
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * TV-side "Receive subscription" screen.
 *
 * Renders a pairing QR (dvpntv://v1?ip&port&token) and runs a one-shot,
 * token-gated LAN listener ([TvHttpReceiver]). When the phone POSTs a
 * subscription URL, it is imported through the EXISTING subscription plumbing
 * ([AngConfigManager.importBatchConfig]) — no new import/storage logic.
 *
 * The listener is strictly tied to this screen's lifecycle: started in
 * [onStart], torn down in [onStop].
 */
class TvReceiveActivity : BaseActivity() {

    private val binding by lazy { ActivityTvReceiveBinding.inflate(layoutInflater) }

    private var receiver: TvHttpReceiver? = null
    private var countDownTimer: CountDownTimer? = null
    private var localIp: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        SubPage.installTransitions(this)
        super.onCreate(savedInstanceState)
        // README §7's lekalo draws the title at 24sp/700 UNDER a back control, which
        // activity_base's 16sp MaterialToolbar cannot do — so the header is @layout/view_sub_header
        // inside this screen's own layout, and the instruction paragraph is its note slot, which is
        // where §7 puts one sentence of explanation. The screen never used the base progress bar.
        setContentView(binding.root)
        ToolbarBinder.bind(
            root = binding.toolbar.root,
            title = getString(R.string.tv_receive_title),
            activity = this,
            note = getString(R.string.tv_receive_instructions),
        )
        ToolbarBinder.attachTo(binding.toolbar.root, binding.mainContent)

        binding.btnRegenerate.setOnClickListener { startPairing() }
        binding.btnRegenerate.visibility = View.GONE
    }

    override fun onStart() {
        super.onStart()
        startPairing()
    }

    override fun onStop() {
        super.onStop()
        stopPairing()
    }

    /**
     * Mints a fresh token, starts the one-shot listener and renders the QR.
     */
    private fun startPairing() {
        stopPairing()

        val ip = TvNetworkUtils.getLocalIpv4Address()
        localIp = ip
        if (ip.isNullOrEmpty()) {
            showNoNetwork()
            return
        }

        val token = TvPairingProtocol.generateToken()
        val httpReceiver = TvHttpReceiver(
            token = token,
            ttlMillis = TvPairingProtocol.TOKEN_TTL_MILLIS,
            onImport = ::handleImport
        )
        if (!httpReceiver.start()) {
            showError(getString(R.string.tv_receive_failed))
            return
        }
        receiver = httpReceiver

        val pairUri = TvPairingProtocol.buildPairUri(ip, httpReceiver.port, token)
        LogUtil.i(AppConfig.TAG, "TvReceiveActivity: listening on $ip:${httpReceiver.port}")

        binding.btnRegenerate.visibility = View.GONE
        binding.tvStatus.text = getString(R.string.tv_receive_seconds_left, TvPairingProtocol.TOKEN_TTL_MILLIS / 1000)
        renderQrCode(pairUri)
        startCountdown()
    }

    private fun stopPairing() {
        countDownTimer?.cancel()
        countDownTimer = null
        receiver?.stop()
        receiver = null
    }

    private fun renderQrCode(text: String) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                QRCodeDecoder.createQRCode(text, QR_SIZE)
            }
            if (bitmap != null) {
                binding.ivQrcode.setImageBitmap(bitmap)
                binding.ivQrcode.visibility = View.VISIBLE
            } else {
                showError(getString(R.string.tv_receive_failed))
            }
        }
    }

    private fun startCountdown() {
        countDownTimer = object : CountDownTimer(TvPairingProtocol.TOKEN_TTL_MILLIS, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                // If the import already consumed the token, stop counting.
                if (receiver?.isConsumed() == true) {
                    cancel()
                    return
                }
                binding.tvStatus.text = getString(R.string.tv_receive_seconds_left, (millisUntilFinished / 1000) + 1)
            }

            override fun onFinish() {
                if (receiver?.isConsumed() != true) {
                    showExpired()
                }
            }
        }.also { it.start() }
    }

    /**
     * Called on the listener's background thread with a validated payload.
     * Imports via the existing subscription plumbing and returns the outcome.
     */
    private fun handleImport(request: TvPairingProtocol.PairRequest): TvHttpReceiver.Outcome {
        val url = request.url
        if (!Utils.isValidSubUrl(url)) {
            runOnUiThread { showError(getString(R.string.tv_receive_failed)) }
            return TvHttpReceiver.Outcome(TvHttpReceiver.Result.INVALID_URL)
        }

        return try {
            // Reuse the EXACT path used by scan/clipboard subscription import.
            // subid = "" means "not scoped to an existing subscription tab"; a sub
            // URL is detected by parseBatchSubscription -> importUrlAsSubscription,
            // which stores the SubscriptionItem and triggers updateConfigViaSubAll().
            val (count, countSub) = AngConfigManager.importBatchConfig(url, "", true)
            if (count > 0 || countSub > 0) {
                val imported = if (countSub > 0) countSub else count
                runOnUiThread { showSuccess(imported) }
                TvHttpReceiver.Outcome(TvHttpReceiver.Result.SUCCESS, imported)
            } else {
                // Valid sub URL but nothing imported => already present (dedupe).
                runOnUiThread { showDuplicate() }
                TvHttpReceiver.Outcome(TvHttpReceiver.Result.DUPLICATE)
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "TvReceiveActivity: import failed", e)
            runOnUiThread { showError(getString(R.string.tv_receive_failed)) }
            TvHttpReceiver.Outcome(TvHttpReceiver.Result.ERROR)
        }
    }

    private fun showSuccess(count: Int) {
        countDownTimer?.cancel()
        binding.ivQrcode.visibility = View.GONE
        binding.tvStatus.text = getString(R.string.tv_receive_success, count)
        binding.btnRegenerate.visibility = View.VISIBLE
        binding.btnRegenerate.requestFocus()
    }

    private fun showDuplicate() {
        countDownTimer?.cancel()
        binding.ivQrcode.visibility = View.GONE
        binding.tvStatus.text = getString(R.string.tv_receive_duplicate)
        binding.btnRegenerate.visibility = View.VISIBLE
        binding.btnRegenerate.requestFocus()
    }

    private fun showExpired() {
        binding.ivQrcode.visibility = View.GONE
        binding.tvStatus.text = getString(R.string.tv_receive_expired)
        binding.btnRegenerate.visibility = View.VISIBLE
        binding.btnRegenerate.requestFocus()
    }

    private fun showNoNetwork() {
        binding.ivQrcode.visibility = View.GONE
        binding.tvStatus.text = getString(R.string.tv_receive_no_network)
        binding.btnRegenerate.visibility = View.VISIBLE
        binding.btnRegenerate.requestFocus()
    }

    private fun showError(message: String) {
        binding.tvStatus.text = message
        binding.btnRegenerate.visibility = View.VISIBLE
        binding.btnRegenerate.requestFocus()
    }

    companion object {
        private const val QR_SIZE = 640
    }
}
