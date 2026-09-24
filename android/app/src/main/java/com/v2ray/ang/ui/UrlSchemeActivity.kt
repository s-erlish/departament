package com.v2ray.ang.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityLogcatBinding
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder

class UrlSchemeActivity : BaseActivity() {
    private val binding by lazy { ActivityLogcatBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        try {
            intent.apply {
                if (action == Intent.ACTION_SEND) {
                    if ("text/plain" == type) {
                        intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                            parseUri(it, null)
                        }
                    }
                } else if (action == Intent.ACTION_VIEW) {
                    val uri: Uri? = intent.data
                    if (uri?.scheme.equals("depv", ignoreCase = true)) {
                        handleDepvScheme(uri)
                    } else {
                        when (uri?.host) {
                            "install-config" -> {
                                val shareUrl = uri.getQueryParameter("url").orEmpty()
                                parseUri(shareUrl, uri.fragment)
                            }

                            "install-sub" -> {
                                val shareUrl = uri.getQueryParameter("url").orEmpty()
                                parseUri(shareUrl, uri.fragment)
                            }

                            else -> {
                                toastError(R.string.editor_failed)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Error processing URL scheme", e)
        } finally {
            // THE HAND-OFF IS IN `finally`, AND THAT IS THE WHOLE OF THE SECOND HALF OF THIS FIX.
            //
            // This Activity is a trampoline: it has no interface of its own (it inflates
            // `activity_logcat` purely to have a content view) and its entire job is to act on the
            // link and then open Главная. Both of those lines used to sit INSIDE the `try`, after
            // the dispatch — so any throw above them was caught, logged, and left the trampoline
            // standing: the user, who had just shared a piece of text to departament, was looking
            // at an empty «Журнал» screen with no toolbar, no content and no way forward except
            // the back gesture, and the app never opened.
            //
            // A link we could not act on still ends where every link ends. The failure is reported
            // by the branch that hit it (`toastError`), and Главная is where the user can see what
            // did or did not arrive.
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    /**
     * Dispatch a depv:// deeplink by host / path.
     *
     * Supported:
     *  - connect | open            -> start the VPN service
     *  - disconnect | close        -> stop the VPN service
     *  - toggle                    -> stop if running, otherwise start
     *  - import/{base64}           -> Base64 -> batch import (auto-detect)
     *  - add/{url}                 -> import subscription / config by URL
     *  - routing/add/{base64}      -> Base64 JSON -> import routing rulesets
     *  - routing/onadd/{base64}    -> import routing rulesets and apply (restart if running)
     */
    private fun handleDepvScheme(uri: Uri?) {
        if (uri == null) {
            toastError(R.string.editor_failed)
            return
        }
        when (uri.host) {
            "connect", "open" -> CoreServiceManager.startVService(this)

            "disconnect", "close" -> CoreServiceManager.stopVService(this)

            // `isTunnelUp`, NEVER `isRunning`: this Activity runs in the UI process, where the
            // core controller has never started anything and answers «не подключено» for the life
            // of the app. So `depv://toggle` only ever connected — it could not see the tunnel it
            // was being asked to switch off. @see CoreServiceManager.isTunnelUp
            "toggle" -> {
                if (CoreServiceManager.isTunnelUp()) {
                    CoreServiceManager.stopVService(this)
                } else {
                    CoreServiceManager.startVServiceFromToggle(this)
                }
            }

            "import" -> {
                val decoded = Utils.decode(uri.lastPathSegment)
                if (decoded.isNotEmpty()) {
                    importDecodedConfig(decoded)
                } else {
                    toastError(R.string.editor_failed)
                }
            }

            "add" -> {
                val raw = uri.toString().substringAfter("://add/", "")
                if (raw.isNotEmpty()) {
                    parseUri(raw, null)
                } else {
                    toastError(R.string.editor_failed)
                }
            }

            "routing" -> {
                val segments = uri.pathSegments
                if (segments.size >= 2) {
                    val op = segments.first()
                    val json = Utils.decode(segments.last())
                    if ((op == "add" || op == "onadd") && json.isNotEmpty()) {
                        importRoutingRules(json, apply = op == "onadd")
                    } else {
                        toastError(R.string.editor_failed)
                    }
                } else {
                    toastError(R.string.editor_failed)
                }
            }

            else -> toastError(R.string.editor_failed)
        }
    }

    /**
     * Import already-decoded config content (a config / share-url payload) via the batch importer.
     *
     * Appends, like the in-app add menu: a link adds a server to the ones the user already has and
     * never replaces them. @see AngConfigManager.replacesServers
     */
    private fun importDecodedConfig(content: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val (count, countSub) = AngConfigManager.importBatchConfig(content, "", true)
            withContext(Dispatchers.Main) {
                if (count + countSub > 0) {
                    toast(R.string.scheme_import_done)
                } else {
                    toastError(R.string.scheme_import_failed)
                }
            }
        }
    }

    /**
     * Import routing rulesets from a JSON payload; optionally apply by restarting a running service.
     */
    private fun importRoutingRules(json: String, apply: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = SettingsManager.resetRoutingRulesets(json)
            // Same cross-process reading as `toggle` above: asked with `isRunning` this was always
            // false, so `onadd` saved the rules and never applied them to the live core — the one
            // thing that distinguishes it from `add`. @see CoreServiceManager.isTunnelUp
            if (result && apply && CoreServiceManager.isTunnelUp()) {
                MessageUtil.sendMsg2Service(this@UrlSchemeActivity, AppConfig.MSG_STATE_RESTART, "")
            }
            withContext(Dispatchers.Main) {
                if (result) {
                    toastSuccess(R.string.editor_done)
                } else {
                    toastError(R.string.editor_failed)
                }
            }
        }
    }

    private fun parseUri(uriString: String?, fragment: String?) {
        if (uriString.isNullOrEmpty()) {
            return
        }
        LogUtil.i(AppConfig.TAG, uriString)

        // A SHARED STRING IS NOT A PERCENT-ENCODED ONE, and `URLDecoder.decode` is not merciful
        // about the difference: a lone `%` — or one followed by anything that is not two hex
        // digits — is an IllegalArgumentException, not a passthrough. This method is reached from
        // ACTION_SEND, i.e. from «Поделиться» in any app, so «Скидка 50% на тариф» is enough to
        // throw; it is also reached from `depv://add/…`, where an operator's link can carry the
        // same character. The throw took the whole of `onCreate` with it (see the `finally`
        // there), so the share ended in a blank screen instead of an answer.
        //
        // Text that is not encoded is used exactly as it arrived, which is what the importer wants
        // anyway: `vless://…#Имя` is a valid link and decoding it was never load-bearing.
        var decodedUrl = runCatching { URLDecoder.decode(uriString, "UTF-8") }.getOrElse {
            LogUtil.i(AppConfig.TAG, "URL scheme: the payload is not percent-encoded, using it as it arrived")
            uriString
        }
        val uri = Uri.parse(decodedUrl)
        if (uri != null) {
            if (uri.fragment.isNullOrEmpty() && !fragment.isNullOrEmpty()) {
                decodedUrl += "#${fragment}"
            }
            LogUtil.i(AppConfig.TAG, decodedUrl)
            // Appends, like the in-app add menu — see [importDecodedConfig].
            lifecycleScope.launch(Dispatchers.IO) {
                val (count, countSub) = AngConfigManager.importBatchConfig(decodedUrl, "", true)
                withContext(Dispatchers.Main) {
                    if (count + countSub > 0) {
                        toast(R.string.scheme_import_done)
                    } else {
                        toastError(R.string.scheme_import_failed)
                    }
                }
            }
        }
    }
}