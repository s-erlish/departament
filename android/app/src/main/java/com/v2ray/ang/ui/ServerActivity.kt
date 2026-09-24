package com.v2ray.ang.ui

import android.graphics.Rect
import android.os.Bundle
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputLayout
import com.v2ray.ang.AppConfig.DEFAULT_PORT
import com.v2ray.ang.AppConfig.PREF_ALLOW_INSECURE
import com.v2ray.ang.AppConfig.REALITY
import com.v2ray.ang.AppConfig.TLS
import com.v2ray.ang.AppConfig.WIREGUARD_LOCAL_ADDRESS_V4
import com.v2ray.ang.AppConfig.WIREGUARD_LOCAL_MTU
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.ui.component.RowBinder
import com.v2ray.ang.ui.component.SubPage
import com.v2ray.ang.ui.component.ToolbarBinder
import com.v2ray.ang.ui.component.clearClick
import com.v2ray.ang.ui.component.onSingleClick
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.Utils

class ServerActivity : BaseActivity() {

    private companion object {
        /** The last port number there is. A form that accepts 70000 stores a server that cannot dial. */
        const val MAX_PORT = 65535

        /** What upstream stores when a network has no header type of its own. */
        const val NO_TRANSPORT_TYPE = "---"

        /** WireGuard stores three BYTES in the reserved field. */
        const val RESERVED_MAX = 255
    }

    private val editGuid by lazy { intent.getStringExtra("guid").orEmpty() }
    private val isRunning by lazy {
        intent.getBooleanExtra("isRunning", false)
                && editGuid.isNotEmpty()
                && editGuid == MmkvManager.getSelectServer()
    }
    private val createConfigType by lazy {
        EConfigType.fromInt(intent.getIntExtra("createConfigType", EConfigType.VMESS.value))
            ?: EConfigType.VMESS
    }
    private val subscriptionId by lazy {
        intent.getStringExtra("subscriptionId")
    }

    private val securitys: Array<out String> by lazy {
        resources.getStringArray(R.array.securitys)
    }
    private val shadowsocksSecuritys: Array<out String> by lazy {
        resources.getStringArray(R.array.ss_securitys)
    }
    private val flows: Array<out String> by lazy {
        resources.getStringArray(R.array.flows)
    }
    private val networks: Array<out String> by lazy {
        resources.getStringArray(R.array.networks)
    }
    private val tcpTypes: Array<out String> by lazy {
        resources.getStringArray(R.array.header_type_tcp)
    }
    private val kcpAndQuicTypes: Array<out String> by lazy {
        resources.getStringArray(R.array.header_type_kcp_and_quic)
    }
    private val grpcModes: Array<out String> by lazy {
        resources.getStringArray(R.array.mode_type_grpc)
    }
    private val streamSecuritys: Array<out String> by lazy {
        resources.getStringArray(R.array.streamsecurityxs)
    }
    private val allowinsecures: Array<out String> by lazy {
        resources.getStringArray(R.array.allowinsecures)
    }
    private val uTlsItems: Array<out String> by lazy {
        resources.getStringArray(R.array.streamsecurity_utls)
    }
    private val alpns: Array<out String> by lazy {
        resources.getStringArray(R.array.streamsecurity_alpn)
    }
    private val xhttpMode: Array<out String> by lazy {
        resources.getStringArray(R.array.xhttp_mode)
    }
    private val browserDialerModes: Array<out String> by lazy {
        resources.getStringArray(R.array.browser_dialer_mode)
    }


    // Kotlin synthetics was used, but since it is removed in 1.8. We switch to old manual approach.
    // We don't use AndroidViewBinding because, it is better to share similar logics for different
    // protocols. Use findViewById manually ensures the xml are de-coupled with the activity logic.
    private val et_remarks: EditText by lazy { findViewById(R.id.et_remarks) }
    private val et_address: EditText by lazy { findViewById(R.id.et_address) }
    private val et_port: EditText by lazy { findViewById(R.id.et_port) }
    private val til_remarks: TextInputLayout by lazy { findViewById(R.id.til_remarks) }
    private val til_address: TextInputLayout by lazy { findViewById(R.id.til_address) }
    private val til_port: TextInputLayout by lazy { findViewById(R.id.til_port) }
    private val et_id: EditText by lazy { findViewById(R.id.et_id) }
    // Nullable while the last protocol layouts are still being ported: a screen that has not got
    // its error slot yet answers with focus alone rather than losing the message.
    private val til_id: TextInputLayout? by lazy { findViewById(R.id.til_id) }
    private val et_security: EditText? by lazy { findViewById(R.id.et_security) }
    private val til_security: TextInputLayout? by lazy { findViewById(R.id.til_security) }
    private val et_flow: EditText? by lazy { findViewById(R.id.et_flow) }
    private val til_flow: TextInputLayout? by lazy { findViewById(R.id.til_flow) }
    private val et_method: EditText? by lazy { findViewById(R.id.et_method) }
    private val til_method: TextInputLayout? by lazy { findViewById(R.id.til_method) }
    private val et_stream_security: EditText? by lazy { findViewById(R.id.et_stream_security) }
    private val til_stream_security: TextInputLayout? by lazy { findViewById(R.id.til_stream_security) }
    private val et_allow_insecure: EditText? by lazy { findViewById(R.id.et_allow_insecure) }
    private val til_allow_insecure: TextInputLayout? by lazy { findViewById(R.id.til_allow_insecure) }
    private val container_allow_insecure: LinearLayout? by lazy { findViewById(R.id.lay_allow_insecure) }
    private val et_sni: EditText? by lazy { findViewById(R.id.et_sni) }
    private val til_sni: TextInputLayout? by lazy { findViewById(R.id.til_sni) }
    private val container_sni: LinearLayout? by lazy { findViewById(R.id.lay_sni) }
    private val et_stream_fingerprint: EditText? by lazy { findViewById(R.id.et_stream_fingerprint) } //uTLS
    private val til_stream_fingerprint: TextInputLayout? by lazy { findViewById(R.id.til_stream_fingerprint) }
    private val container_fingerprint: LinearLayout? by lazy { findViewById(R.id.lay_stream_fingerprint) }
    private val et_network: EditText? by lazy { findViewById(R.id.et_network) }
    private val til_network: TextInputLayout? by lazy { findViewById(R.id.til_network) }
    private val et_header_type: EditText? by lazy { findViewById(R.id.et_header_type) }
    private val til_header_type: TextInputLayout? by lazy { findViewById(R.id.til_header_type) }
    private val tv_header_type: TextView? by lazy { findViewById(R.id.tv_header_type) }
    private val tv_request_host: TextView? by lazy { findViewById(R.id.tv_request_host) }
    private val et_request_host: EditText? by lazy { findViewById(R.id.et_request_host) }
    private val til_request_host: TextInputLayout? by lazy { findViewById(R.id.til_request_host) }
    private val tv_path: TextView? by lazy { findViewById(R.id.tv_path) }
    private val et_path: EditText? by lazy { findViewById(R.id.et_path) }
    private val til_path: TextInputLayout? by lazy { findViewById(R.id.til_path) }
    private val et_stream_alpn: EditText? by lazy { findViewById(R.id.et_stream_alpn) } //uTLS
    private val til_stream_alpn: TextInputLayout? by lazy { findViewById(R.id.til_stream_alpn) }
    private val container_alpn: LinearLayout? by lazy { findViewById(R.id.lay_stream_alpn) }
    private val et_public_key: EditText? by lazy { findViewById(R.id.et_public_key) }
    private val til_public_key: TextInputLayout? by lazy { findViewById(R.id.til_public_key) }
    private val et_preshared_key: EditText? by lazy { findViewById(R.id.et_preshared_key) }
    private val til_preshared_key: TextInputLayout? by lazy { findViewById(R.id.til_preshared_key) }
    private val container_public_key: LinearLayout? by lazy { findViewById(R.id.lay_public_key) }
    private val et_short_id: EditText? by lazy { findViewById(R.id.et_short_id) }
    private val til_short_id: TextInputLayout? by lazy { findViewById(R.id.til_short_id) }
    private val container_short_id: LinearLayout? by lazy { findViewById(R.id.lay_short_id) }
    private val et_spider_x: EditText? by lazy { findViewById(R.id.et_spider_x) }
    private val til_spider_x: TextInputLayout? by lazy { findViewById(R.id.til_spider_x) }
    private val container_spider_x: LinearLayout? by lazy { findViewById(R.id.lay_spider_x) }
    private val et_mldsa65_verify: EditText? by lazy { findViewById(R.id.et_mldsa65_verify) }
    private val til_mldsa65_verify: TextInputLayout? by lazy { findViewById(R.id.til_mldsa65_verify) }
    private val container_mldsa65_verify: LinearLayout? by lazy { findViewById(R.id.lay_mldsa65_verify) }
    private val et_reserved1: EditText? by lazy { findViewById(R.id.et_reserved1) }
    private val til_reserved1: TextInputLayout? by lazy { findViewById(R.id.til_reserved1) }
    private val et_local_address: EditText? by lazy { findViewById(R.id.et_local_address) }
    private val til_local_address: TextInputLayout? by lazy { findViewById(R.id.til_local_address) }
    private val et_local_mtu: EditText? by lazy { findViewById(R.id.et_local_mtu) }
    private val til_local_mtu: TextInputLayout? by lazy { findViewById(R.id.til_local_mtu) }
    private val et_obfs_password: EditText? by lazy { findViewById(R.id.et_obfs_password) }
    private val til_obfs_password: TextInputLayout? by lazy { findViewById(R.id.til_obfs_password) }
    private val et_port_hop: EditText? by lazy { findViewById(R.id.et_port_hop) }
    private val til_port_hop: TextInputLayout? by lazy { findViewById(R.id.til_port_hop) }
    private val et_port_hop_interval: EditText? by lazy { findViewById(R.id.et_port_hop_interval) }
    private val til_port_hop_interval: TextInputLayout? by lazy { findViewById(R.id.til_port_hop_interval) }
    private val et_bandwidth_down: EditText? by lazy { findViewById(R.id.et_bandwidth_down) }
    private val til_bandwidth_down: TextInputLayout? by lazy { findViewById(R.id.til_bandwidth_down) }
    private val et_bandwidth_up: EditText? by lazy { findViewById(R.id.et_bandwidth_up) }
    private val til_bandwidth_up: TextInputLayout? by lazy { findViewById(R.id.til_bandwidth_up) }
    private val et_kcp_mtu: EditText? by lazy { findViewById(R.id.et_kcp_mtu) }
    private val til_kcp_mtu: TextInputLayout? by lazy { findViewById(R.id.til_kcp_mtu) }
    private val et_kcp_tti: EditText? by lazy { findViewById(R.id.et_kcp_tti) }
    private val til_kcp_tti: TextInputLayout? by lazy { findViewById(R.id.til_kcp_tti) }
    private val layout_kcp: LinearLayout? by lazy { findViewById(R.id.layout_kcp) }
    private val et_extra: EditText? by lazy { findViewById(R.id.et_extra) }
    private val til_extra: TextInputLayout? by lazy { findViewById(R.id.til_extra) }
    private val et_fm: EditText? by lazy { findViewById(R.id.et_fm) }
    private val til_fm: TextInputLayout? by lazy { findViewById(R.id.til_fm) }
    private val layout_extra: LinearLayout? by lazy { findViewById(R.id.layout_extra) }
    private val et_ech_config_list: EditText? by lazy { findViewById(R.id.et_ech_config_list) }
    private val til_ech_config_list: TextInputLayout? by lazy { findViewById(R.id.til_ech_config_list) }
    private val container_ech_config_list: LinearLayout? by lazy { findViewById(R.id.lay_ech_config_list) }
    private val et_pinned_ca256: EditText? by lazy { findViewById(R.id.et_pinned_ca256) }
    private val til_pinned_ca256: TextInputLayout? by lazy { findViewById(R.id.til_pinned_ca256) }
    private val container_pinned_ca256: LinearLayout? by lazy { findViewById(R.id.lay_pinned_ca256) }
    private val layout_browser_dialer: LinearLayout? by lazy { findViewById(R.id.layout_browser_dialer) }
    private val et_browser_dialer_mode: EditText? by lazy { findViewById(R.id.et_browser_dialer_mode) }
    private val til_browser_dialer_mode: TextInputLayout? by lazy { findViewById(R.id.til_browser_dialer_mode) }
    private val btn_save: MaterialButton by lazy { findViewById(R.id.btn_save) }
    private val pb_save: CircularProgressIndicator by lazy { findViewById(R.id.pb_save) }

    /**
     * The three transport choices, held as indices instead of `Spinner.selectedItemPosition`.
     *
     * A `Spinner` kept this state for us and reported it back through `onItemSelected`, which fired
     * on its own during layout as well as on a real pick - so the code below used to run twice for
     * reasons that had nothing to do with the user. The value lives here now and moves only when
     * [bindTransportSelects] is told to move it.
     */
    private var networkIndex = 0
    private var headerTypeIndex = 0
    private var browserDialerIndex = 0

    /** The four TLS choices, held the same way and for the same reason. */
    private var streamSecurityIndex = 0
    private var fingerprintIndex = 0
    private var alpnIndex = 0
    private var allowInsecureIndex = 0

    /** VLESS flow and the VMess / Shadowsocks cipher, likewise. */
    private var flowIndex = 0
    private var methodIndex = 0

    /** The server being edited, or null for a new one. Read by the pickers when they repopulate. */
    private var editedConfig: ProfileItem? = null

    /** The protocol this screen is editing. Decided once in [onCreate], read by the pickers. */
    private var editorType: EConfigType = EConfigType.VMESS


    override fun onCreate(savedInstanceState: Bundle?) {
        SubPage.installTransitions(this)
        super.onCreate(savedInstanceState)

        val config = MmkvManager.decodeServerConfig(editGuid)
        editedConfig = config
        val configType = config?.configType ?: createConfigType
        editorType = configType

        // A guid that will not decode used to fall through to a BLANK NEW FORM wearing the stored
        // server's protocol name, and «Сохранить» then wrote that empty form over the record it
        // could not read. The screen says what happened instead (00-rules.md 9.4, 15).
        if (editGuid.isNotEmpty() && config == null) {
            toastError(R.string.srv_config_unreadable)
            SubPage.close(this)
            return
        }

        val layoutId = when (configType) {
            EConfigType.VMESS -> R.layout.activity_server_vmess
            EConfigType.SHADOWSOCKS -> R.layout.activity_server_shadowsocks
            EConfigType.SOCKS, EConfigType.HTTP -> R.layout.activity_server_socks
            EConfigType.VLESS -> R.layout.activity_server_vless
            EConfigType.TROJAN -> R.layout.activity_server_trojan
            EConfigType.WIREGUARD -> R.layout.activity_server_wireguard
            EConfigType.HYSTERIA2 -> R.layout.activity_server_hysteria2
            else -> null
        }
        // THE STATE THAT USED TO BE A BLANK SCREEN. `?: return` left the activity alive with no
        // content view at all - a black rectangle with a back gesture and nothing else. A type this
        // form cannot draw is now named and the screen closes (00-rules.md 15).
        if (layoutId == null) {
            toastError(R.string.srv_editor_unavailable)
            SubPage.close(this)
            return
        }
        setContentView(layoutId)

        ToolbarBinder.bind(
            root = findViewById(R.id.toolbar),
            title = getString(R.string.srv_title_fmt, getString(protocolTitle(configType))),
            activity = this,
        )
        ToolbarBinder.attachTo(findViewById(R.id.toolbar), findViewById<NestedScrollView>(R.id.main_content))

        btn_save.onSingleClick { saveServer() }
        watchErrorSlots()

        RowBinder.bind(
            root = findViewById(R.id.row_delete),
            title = getString(R.string.srv_delete),
            tone = RowBinder.RowTone.DESTRUCTIVE,
            trailing = RowBinder.Trailing.None,
            onClick = { deleteServer() },
        )
        findViewById<View>(R.id.row_delete).isVisible = editGuid.isNotEmpty() && !isRunning

        if (config != null) {
            bindingServer(config)
        } else {
            clearServer()
        }
    }


    // ------------------------------------------------------------------ доступ

    /** The cipher list this protocol has: Shadowsocks carries eleven, VMess five. */
    private fun methodOptions(): Array<out String> =
        if (editorType == EConfigType.SHADOWSOCKS) shadowsocksSecuritys else securitys

    /** Draws the two protocol pickers - the VLESS flow and the VMess / Shadowsocks cipher. */
    private fun bindProtocolSelects() {
        bindSelect(
            layout = til_method,
            field = et_method,
            title = getString(
                if (editorType == EConfigType.SHADOWSOCKS) R.string.server_lab_security3
                else R.string.server_lab_security
            ),
            options = optionLabels(methodOptions()),
            selectedIndex = methodIndex,
        ) { picked ->
            methodIndex = picked
            bindProtocolSelects()
        }

        bindSelect(
            layout = til_flow,
            field = et_flow,
            title = getString(R.string.server_lab_flow),
            options = optionLabels(flows),
            selectedIndex = flowIndex,
        ) { picked ->
            flowIndex = picked
            bindProtocolSelects()
        }
    }

    // ------------------------------------------------------------------ шифрование канала

    /**
     * What the channel-encryption picker may offer.
     *
     * Hysteria2 has no REALITY, and its layout used to say so by reading a SECOND array
     * (`streamsecuritys`) while the code indexed a third value out of the first one
     * (`streamsecurityxs`). Two parallel tables that agree on their first two rows is not a rule,
     * it is a coincidence that held. The rule is here, in one place.
     */
    private fun streamSecurityOptions(): Array<out String> =
        if (editorType == EConfigType.HYSTERIA2) streamSecuritys.take(2).toTypedArray() else streamSecuritys

    /** Draws the four TLS pickers from the current index state. */
    private fun bindTlsSelects() {
        bindSelect(
            layout = til_stream_security,
            field = et_stream_security,
            title = getString(R.string.server_lab_stream_security),
            options = optionLabels(streamSecurityOptions()),
            selectedIndex = streamSecurityIndex,
        ) { picked -> applyStreamSecurity(picked) }

        bindSelect(
            layout = til_stream_fingerprint,
            field = et_stream_fingerprint,
            title = getString(R.string.server_lab_stream_fingerprint),
            options = optionLabels(uTlsItems),
            selectedIndex = fingerprintIndex,
        ) { picked ->
            fingerprintIndex = picked
            bindTlsSelects()
        }

        bindSelect(
            layout = til_stream_alpn,
            field = et_stream_alpn,
            title = getString(R.string.server_lab_stream_alpn),
            options = optionLabels(alpns),
            selectedIndex = alpnIndex,
        ) { picked ->
            alpnIndex = picked
            bindTlsSelects()
        }

        bindSelect(
            layout = til_allow_insecure,
            field = et_allow_insecure,
            title = getString(R.string.server_lab_allow_insecure),
            options = allowInsecureLabels(),
            selectedIndex = allowInsecureIndex,
        ) { picked ->
            allowInsecureIndex = picked
            bindTlsSelects()
        }
    }

    /**
     * «Разрешать небезопасные соединения» is a yes/no with a third answer, and the third one is the
     * default: an empty value means «whatever the app-wide setting says», which is what [saveTls]
     * reads it as. A picker that offered «», «true» and «false» made the user work that out.
     */
    private fun allowInsecureLabels(): List<CharSequence> = allowinsecures.map { value ->
        when (value) {
            "true" -> getString(R.string.srv_value_yes)
            "false" -> getString(R.string.srv_value_no)
            else -> getString(R.string.srv_value_default)
        }
    }

    /**
     * The channel encryption changed, so the form shows the fields that belong to it.
     *
     * This is the body of the old `sp_stream_security` listener, with the same three cases: nothing
     * (no channel encryption at all, so there is nothing under it to fill in), TLS, and REALITY.
     */
    private fun applyStreamSecurity(position: Int) {
        if (et_stream_security == null) return
        streamSecurityIndex = position.coerceIn(streamSecurityOptions().indices)
        val value = streamSecuritys[streamSecurityIndex]

        val tlsOnly = listOf(container_alpn, container_allow_insecure, container_ech_config_list, container_pinned_ca256)
        val realityOnly = listOf(container_public_key, container_short_id, container_spider_x, container_mldsa65_verify)
        val always = listOf(container_sni, container_fingerprint)

        when {
            value.isBlank() -> (always + tlsOnly + realityOnly).forEach { it?.isVisible = false }

            value == TLS -> {
                (always + tlsOnly).forEach { it?.isVisible = true }
                realityOnly.forEach { it?.isVisible = false }
            }

            else -> {
                (always + realityOnly).forEach { it?.isVisible = true }
                tlsOnly.forEach { it?.isVisible = false }
            }
        }

        bindTlsSelects()
    }

    // ------------------------------------------------------------------ выбор значения

    /**
     * A value chosen from a fixed list, drawn as a FIELD and not as a `Spinner`.
     *
     * `Spinner` is not in the allowed component vocabulary (00-rules.md 11.2) and the three that
     * lived here were the last ones in the product: it draws a platform popover with its own fill,
     * corner, type and ripple, which is exactly the seam that makes a screen read as somebody
     * else's app. What replaces it is the same 56dp box with the same 16dp corner as every field
     * beside it, the value written inside where it can still be read after scrolling past, a caret
     * on the trailing edge, and [EditorActionsSheet] - the sheet «Подписка» already opens - for the
     * list itself.
     *
     * A field rather than a settings row, and that is not a matter of taste: a field carries the
     * error slot (7.4), and «Для Trojan нужно выбрать TLS» is a refusal that has to land on the
     * control it is about.
     *
     * The list marks the value in force. A picker that shows only the options makes the user close
     * it and read the field to find out what is set, which is checking your own choice by doing
     * something; the marker is a reserved slot, so opening the list never reflows it either.
     *
     * The box takes focus like any other field but never opens a keyboard: `keyListener = null`
     * makes it uneditable while leaving it in the focus order, so a keyboard, a D-pad or switch
     * access still reaches it. ENTER and the D-pad centre open the list, which is what a focused
     * control has to do for a user with no touchscreen.
     */
    private fun bindSelect(
        layout: TextInputLayout?,
        field: EditText?,
        title: CharSequence,
        options: List<CharSequence>,
        selectedIndex: Int,
        enabled: Boolean = true,
        onPick: (Int) -> Unit,
    ) {
        if (layout == null || field == null) return
        field.setText(options.getOrNull(selectedIndex) ?: "")
        field.keyListener = null
        field.isCursorVisible = false
        field.showSoftInputOnFocus = false
        layout.isEnabled = enabled
        if (!enabled) {
            field.clearClick()
            field.setOnKeyListener(null)
            layout.setEndIconOnClickListener(null)
            return
        }
        val open = {
            EditorActionsSheet(this, title).apply {
                options.forEachIndexed { index, label ->
                    action(label = label, selected = index == selectedIndex) { onPick(index) }
                }
            }.show()
        }
        field.onSingleClick { open() }
        // The caret is a separate view inside the field and swallows the touch that lands on it,
        // so it needs its own listener. `setEndIconOnClickListener` is Material's own hook and not
        // the `View.setOnClickListener` SingleClick.kt reserves; the sheet it opens is idempotent,
        // and «Подписка» wires its pickers the same way.
        layout.setEndIconOnClickListener { open() }
        field.setOnKeyListener { _, keyCode, event ->
            val opens = keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            if (opens && event.action == KeyEvent.ACTION_UP) {
                open()
                true
            } else {
                false
            }
        }
    }

    /**
     * What a stored value looks like in the field.
     *
     * Two of them are not words: the empty string, which every one of these lists uses for «not
     * set», and «---», which [transportTypes] returns for a network that has no header type at all.
     * A `Spinner` printed both of those at the user verbatim.
     */
    private fun optionLabel(value: String): CharSequence = when {
        value.isBlank() -> getString(R.string.srv_value_none)
        value == NO_TRANSPORT_TYPE -> getString(R.string.srv_value_not_applicable)
        else -> value
    }

    private fun optionLabels(values: Array<out String>): List<CharSequence> = values.map { optionLabel(it) }

    /** Draws the three transport pickers from the current index state. */
    private fun bindTransportSelects() {
        val types = transportTypes(networks.getOrNull(networkIndex))
        bindSelect(
            layout = til_network,
            field = et_network,
            title = getString(R.string.server_lab_network),
            options = optionLabels(networks),
            selectedIndex = networkIndex,
        ) { picked -> applyNetwork(picked) }

        bindSelect(
            layout = til_header_type,
            field = et_header_type,
            title = tv_header_type?.text ?: getString(R.string.server_lab_head_type),
            options = optionLabels(types),
            selectedIndex = headerTypeIndex,
            // One value is not a choice: a network with no header type leaves the box readable and
            // out of the touch order rather than opening a list of one.
            enabled = types.size > 1,
        ) { picked ->
            headerTypeIndex = picked
            bindTransportSelects()
        }

        bindSelect(
            layout = til_browser_dialer_mode,
            field = et_browser_dialer_mode,
            title = getString(R.string.server_lab_browser_dialer),
            options = optionLabels(browserDialerModes),
            selectedIndex = browserDialerIndex,
        ) { picked ->
            browserDialerIndex = picked
            bindTransportSelects()
        }
    }

    /**
     * The network changed, so everything downstream of it is re-read from the stored server.
     *
     * This is the body of the old `sp_network` listener, unchanged in what it does: the header
     * type, the host, the path and the XHTTP extra all mean different things per network, so each
     * one is re-labelled and re-filled from the field of [editedConfig] that the new network reads.
     * mKCP, XHTTP Extra and the browser dialer appear only where they apply - a form that shows a
     * mKCP MTU box on a WebSocket server is asking for a value nothing will read.
     */
    private fun applyNetwork(position: Int) {
        if (et_network == null) return
        networkIndex = position.coerceIn(networks.indices)
        val network = networks[networkIndex]
        val config = editedConfig
        val types = transportTypes(network)

        headerTypeIndex = Utils.arrayFind(
            types,
            when (network) {
                NetworkType.GRPC.type -> config?.mode
                NetworkType.XHTTP.type -> config?.xhttpMode
                else -> config?.headerType
            }.orEmpty()
        ).coerceAtLeast(0)

        tv_header_type?.text = getString(
            when (network) {
                NetworkType.GRPC.type -> R.string.server_lab_mode_type
                NetworkType.XHTTP.type -> R.string.server_lab_xhttp_mode
                else -> R.string.server_lab_head_type
            }
        )

        et_request_host?.text = Utils.getEditable(
            when (network) {
                NetworkType.GRPC.type -> config?.authority
                else -> config?.host
            }.orEmpty()
        )
        et_path?.text = Utils.getEditable(
            when (network) {
                NetworkType.KCP.type -> config?.seed
                NetworkType.GRPC.type -> config?.serviceName
                else -> config?.path
            }.orEmpty()
        )

        tv_request_host?.text = getString(
            when (network) {
                NetworkType.TCP.type -> R.string.server_lab_request_host_http
                NetworkType.WS.type -> R.string.server_lab_request_host_ws
                NetworkType.HTTP_UPGRADE.type -> R.string.server_lab_request_host_httpupgrade
                NetworkType.XHTTP.type -> R.string.server_lab_request_host_xhttp
                NetworkType.H2.type -> R.string.server_lab_request_host_h2
                NetworkType.GRPC.type -> R.string.server_lab_request_host_grpc
                else -> R.string.server_lab_request_host
            }
        )
        tv_path?.text = getString(
            when (network) {
                NetworkType.KCP.type -> R.string.server_lab_path_kcp
                NetworkType.WS.type -> R.string.server_lab_path_ws
                NetworkType.HTTP_UPGRADE.type -> R.string.server_lab_path_httpupgrade
                NetworkType.XHTTP.type -> R.string.server_lab_path_xhttp
                NetworkType.H2.type -> R.string.server_lab_path_h2
                NetworkType.GRPC.type -> R.string.server_lab_path_grpc
                else -> R.string.server_lab_path
            }
        )

        et_extra?.text = Utils.getEditable(
            when (network) {
                NetworkType.XHTTP.type -> config?.xhttpExtra
                else -> null
            }.orEmpty()
        )
        et_fm?.text = Utils.getEditable(config?.finalMask)

        layout_kcp?.isVisible = network == NetworkType.KCP.type
        et_kcp_mtu?.text = Utils.getEditable(config?.kcpMtu?.toString().orEmpty())
        et_kcp_tti?.text = Utils.getEditable(config?.kcpTti?.toString().orEmpty())

        layout_extra?.isVisible = network == NetworkType.XHTTP.type
        layout_browser_dialer?.isVisible =
            network == NetworkType.WS.type || network == NetworkType.XHTTP.type

        bindTransportSelects()
    }

    /**
     * binding selected server config
     */
    private fun bindingServer(config: ProfileItem): Boolean {

        et_remarks.text = Utils.getEditable(config.remarks)
        et_address.text = Utils.getEditable(config.server.orEmpty())
        et_port.text = Utils.getEditable(config.serverPort ?: DEFAULT_PORT.toString())
        et_id.text = Utils.getEditable(config.password.orEmpty())

        if (config.configType == EConfigType.SOCKS || config.configType == EConfigType.HTTP) {
            et_security?.text = Utils.getEditable(config.username.orEmpty())
        } else if (config.configType == EConfigType.VLESS) {
            et_security?.text = Utils.getEditable(config.method.orEmpty())
            flowIndex = Utils.arrayFind(flows, config.flow.orEmpty()).coerceAtLeast(0)
        } else if (config.configType == EConfigType.WIREGUARD) {
            et_id.text = Utils.getEditable(config.secretKey.orEmpty())
            et_public_key?.text = Utils.getEditable(config.publicKey.orEmpty())
            et_preshared_key?.text = Utils.getEditable(config.preSharedKey.orEmpty())
            et_reserved1?.text = Utils.getEditable(config.reserved ?: "0,0,0")
            et_local_address?.text = Utils.getEditable(
                config.localAddress ?: WIREGUARD_LOCAL_ADDRESS_V4
            )
            et_local_mtu?.text = Utils.getEditable(config.mtu?.toString() ?: WIREGUARD_LOCAL_MTU)
        } else if (config.configType == EConfigType.HYSTERIA2) {
            et_obfs_password?.text = Utils.getEditable(config.obfsPassword)
            et_port_hop?.text = Utils.getEditable(config.portHopping)
            et_port_hop_interval?.text = Utils.getEditable(config.portHoppingInterval)
            et_bandwidth_down?.text = Utils.getEditable(config.bandwidthDown)
            et_bandwidth_up?.text = Utils.getEditable(config.bandwidthUp)
        }
        methodIndex = Utils.arrayFind(methodOptions(), config.method.orEmpty()).coerceAtLeast(0)
        bindProtocolSelects()

        et_sni?.text = Utils.getEditable(config.sni)
        config.fingerPrint?.let { fingerPrint ->
            fingerprintIndex = Utils.arrayFind(uTlsItems, fingerPrint).coerceAtLeast(0)
        }
        config.alpn?.let { alpn ->
            alpnIndex = Utils.arrayFind(alpns, alpn).coerceAtLeast(0)
        }
        if (config.security == TLS) {
            allowInsecureIndex =
                Utils.arrayFind(allowinsecures, config.insecure.toString()).coerceAtLeast(0)
            et_ech_config_list?.text = Utils.getEditable(config.echConfigList)
            et_pinned_ca256?.text = Utils.getEditable(config.pinnedCA256)
        } else if (config.security == REALITY) {
            et_public_key?.text = Utils.getEditable(config.publicKey.orEmpty())
            et_short_id?.text = Utils.getEditable(config.shortId.orEmpty())
            et_spider_x?.text = Utils.getEditable(config.spiderX.orEmpty())
            et_mldsa65_verify?.text = Utils.getEditable(config.mldsa65Verify.orEmpty())
        }
        // Unconditional, and that is the point: `arrayFind` answers -1 for a stored value this
        // build does not know, and the old code then left the picker unbound. Every field below it
        // starts `gone` in the layout, so an unbound picker is an empty box over an empty screen.
        // Falling back to the first value draws a form the user can see and correct.
        applyStreamSecurity(Utils.arrayFind(streamSecuritys, config.security.orEmpty()).coerceAtLeast(0))

        browserDialerIndex =
            Utils.arrayFind(browserDialerModes, config.browserDialerMode.orEmpty()).coerceAtLeast(0)
        // Last, because it repopulates the host, the path and the extra from the network it lands on.
        applyNetwork(Utils.arrayFind(networks, config.network.orEmpty()).coerceAtLeast(0))

        return true
    }

    /**
     * clear or init server config
     */
    private fun clearServer(): Boolean {
        et_remarks.text = null
        et_address.text = null
        et_port.text = Utils.getEditable(DEFAULT_PORT.toString())
        et_id.text = null
        methodIndex = 0
        flowIndex = 0
        bindProtocolSelects()

        browserDialerIndex = 0
        applyNetwork(0)
        et_request_host?.text = null
        et_path?.text = null
        allowInsecureIndex = 0
        fingerprintIndex = 0
        alpnIndex = 0
        applyStreamSecurity(0)
        et_sni?.text = null

        //et_security.text = null
        et_public_key?.text = null
        et_reserved1?.text = Utils.getEditable("0,0,0")
        et_local_address?.text =
            Utils.getEditable(WIREGUARD_LOCAL_ADDRESS_V4)
        et_local_mtu?.text = Utils.getEditable(WIREGUARD_LOCAL_MTU)
        return true
    }

    /**
     * save server config
     */
    /**
     * **THIS FORM USED TO REFUSE IN COMPLETE SILENCE, AND THEN IT REFUSED OVER THE WHOLE SCREEN.**
     *
     * Round one: every check answered with `toast(R.string.server_lab_…)` — the FIELD LABEL
     * («Название», «Адрес», «Порт», «Пароль») used as a message, which is upstream's habit — and not
     * one of those ids is on [com.v2ray.ang.ui.NoticePolicy.ALLOWED]. Pressing «Сохранить» with an
     * empty address did nothing at all: no message, no movement, no closed screen.
     *
     * Round two put a sentence in a toast. Better, but a toast floats over the middle of a form
     * twenty fields long and never says WHICH field it means; by the time the user has scrolled to
     * look, it has gone. 00-rules.md 7.4 puts the refusal under the field it is about, in the slot
     * the field already reserves, and 15 makes it a state of the screen rather than a notification.
     *
     * So: [clearErrors] wipes the previous answer, the first failing check writes one sentence into
     * its own field, the caret goes there, and the field scrolls into view. Nothing floats.
     */
    private fun saveServer(): Boolean {
        clearErrors()

        if (TextUtils.isEmpty(et_remarks.text.toString())) {
            refuse(til_remarks, et_remarks, R.string.srv_name_required)
            return false
        }
        val address = et_address.text.toString().trim()
        if (TextUtils.isEmpty(address)) {
            refuse(til_address, et_address, R.string.srv_address_required)
            return false
        }
        // A pasted link («https://host/path») and a stray space are the two ways this field is
        // filled wrong, and both produce an outbound the core throws away without a word.
        if (address.any { it.isWhitespace() } || address.contains('/')) {
            refuse(til_address, et_address, R.string.srv_address_invalid)
            return false
        }
        // Hysteria2 used to be exempt here, on the argument that port hopping replaces the port.
        // It does not: `mport` is a query parameter on the URI, and the outbound is still built as
        // `serverPort.orEmpty().toInt()` - so a Hysteria2 server saved without one threw on the way
        // to the tunnel and the connection failed with nothing to read. The exemption let the form
        // save a server that could never produce a config.
        val port = Utils.parseInt(et_port.text.toString())
        if (port <= 0 || port > MAX_PORT) {
            refuse(til_port, et_port, R.string.srv_port_required)
            return false
        }
        val config =
            MmkvManager.decodeServerConfig(editGuid) ?: ProfileItem.create(createConfigType)
        if (config.configType != EConfigType.SOCKS
            && config.configType != EConfigType.HTTP
            && TextUtils.isEmpty(et_id.text.toString())
        ) {
            // The message names the field the user is looking at. This box is «Пароль» on three
            // protocols, «Закрытый ключ» on WireGuard and «ID» on the rest, and a refusal that says
            // «идентификатор пользователя» over a box labelled «Закрытый ключ» sends them hunting.
            refuse(
                til_id, et_id,
                when (config.configType) {
                    EConfigType.TROJAN, EConfigType.SHADOWSOCKS, EConfigType.HYSTERIA2 ->
                        R.string.srv_password_required

                    EConfigType.WIREGUARD -> R.string.srv_secret_key_required
                    else -> R.string.srv_id_required
                },
            )
            return false
        }
        // VLESS carries an encryption on the user, and the core takes «none» rather than nothing:
        // an empty box was written into the outbound verbatim and rejected there.
        if (config.configType == EConfigType.VLESS &&
            et_security != null &&
            TextUtils.isEmpty(et_security?.text?.toString()?.trim())
        ) {
            refuse(til_security, et_security, R.string.srv_encryption_required)
            return false
        }
        // WireGuard's peer key. `peer.publicKey = publicKey.orEmpty()` puts an empty string in the
        // config and the handshake then has nothing to answer, so the tunnel never comes up.
        if (config.configType == EConfigType.WIREGUARD &&
            TextUtils.isEmpty(et_public_key?.text?.toString()?.trim())
        ) {
            refuse(til_public_key, et_public_key, R.string.srv_public_key_required)
            return false
        }
        if (et_stream_security != null &&
            config.configType == EConfigType.TROJAN &&
            TextUtils.isEmpty(streamSecuritys.getOrNull(streamSecurityIndex))
        ) {
            refuse(til_stream_security, et_stream_security, R.string.srv_tls_required)
            return false
        }
        // REALITY verifies the server against its public key; `nullIfBlank()` drops an empty one
        // and leaves the handshake with nothing to check. Scoped to REALITY: on plain TLS the same
        // box is not used at all, and on «Нет» it is not even on screen.
        if (et_stream_security != null &&
            streamSecuritys.getOrNull(streamSecurityIndex) == REALITY &&
            TextUtils.isEmpty(et_public_key?.text?.toString()?.trim())
        ) {
            refuse(til_public_key, et_public_key, R.string.srv_public_key_required_reality)
            return false
        }
        if (et_extra?.text?.toString().isNotNullEmpty()) {
            if (JsonUtil.parseString(et_extra?.text?.toString()) == null) {
                refuse(til_extra, et_extra, R.string.srv_json_invalid)
                return false
            }
        }

        if (et_fm?.text?.toString().isNotNullEmpty()) {
            if (JsonUtil.parseString(et_fm?.text?.toString()) == null) {
                refuse(til_fm, et_fm, R.string.srv_json_invalid)
                return false
            }
        }
        // mKCP MTU and TTI went through `toIntOrNull()` on the way out, so anything that was not a
        // number was dropped in silence and the field kept showing it. A discarded value that still
        // looks stored is the same defect as a silent refusal.
        if (!checkOptionalPositiveNumber(til_kcp_mtu, et_kcp_mtu)) return false
        if (!checkOptionalPositiveNumber(til_kcp_tti, et_kcp_tti)) return false
        if (!checkOptionalPositiveNumber(til_local_mtu, et_local_mtu)) return false
        if (!checkOptionalPositiveNumber(til_port_hop_interval, et_port_hop_interval)) return false
        // Hysteria2 reads the bandwidth as a number with a unit. A string with no digit in it at
        // all cannot be one, and was stored verbatim for the core to ignore.
        if (!checkBandwidth(til_bandwidth_down, et_bandwidth_down)) return false
        if (!checkBandwidth(til_bandwidth_up, et_bandwidth_up)) return false
        // Reserved is three bytes WireGuard puts in front of every packet. Anything else went to
        // `config.reserved` as typed and came back out of the tunnel as nothing.
        val reserved = et_reserved1?.text?.toString()?.trim().orEmpty()
        if (et_reserved1 != null && reserved.isNotEmpty() && !isReservedTriple(reserved)) {
            refuse(til_reserved1, et_reserved1, R.string.srv_reserved_invalid)
            return false
        }

        // Everything above answered; from here the screen is WRITING. The button keeps its size,
        // drops its label and spins (00-rules.md 7.1) - and the write itself waits one frame so
        // that state is on screen before it starts rather than after it has finished.
        setSaving(true)
        btn_save.post { commit(config) }
        return true
    }

    /** The write, once every check has passed. */
    private fun commit(config: ProfileItem) {
        saveCommon(config)
        saveStreamSettings(config)
        saveTls(config)

        config.description = AngConfigManager.generateDescription(config)

        if (config.subscriptionId.isEmpty() && !subscriptionId.isNullOrEmpty()) {
            config.subscriptionId = subscriptionId.orEmpty()
        }
        MmkvManager.encodeServerConfig(editGuid, config)
        if (isRunning) {
            SettingsChangeManager.makeRestartService()
        }
        toastSuccess(R.string.editor_saved)
        SubPage.close(this)
    }

    /**
     * The saving state, and the disabled state it is NOT.
     *
     * R8: the control holds its exact width, the label goes to alpha 0 rather than to nothing, a
     * 20dp arc turns in its place, and the button is not drawn at the 0.38 a disabled control gets
     * - it is busy, not unavailable. Everything the form can still be edited with goes quiet too,
     * so a value cannot change between the checks and the write.
     */
    private fun setSaving(saving: Boolean) {
        pb_save.isVisible = saving
        btn_save.isClickable = !saving
        if (saving) {
            // The LABEL goes, not the button: alpha on the control would take its fill with it and
            // draw the disabled look R6 reserves for a control that cannot be used at all.
            btn_save.contentDescription = getString(R.string.srv_saving_cd)
            btn_save.text = ""
        } else {
            btn_save.contentDescription = null
            btn_save.setText(R.string.editor_save)
        }
        errorSlots().forEach { it?.isEnabled = !saving }
    }

    /**
     * Says what to fix, in the field that has to change. See [saveServer].
     *
     * The caret moves there (7.4, «after a failed submit, focus moves to the first invalid field»)
     * and the field is brought into view, because a message written 900dp down a scrolling form is
     * the silent refusal again in a different costume.
     */
    private fun refuse(layout: TextInputLayout?, field: EditText?, @StringRes message: Int) {
        layout?.error = getString(message)
        field?.requestFocus()
        val target = layout ?: field ?: return
        target.post {
            target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), false)
        }
    }

    /**
     * A bandwidth: a number and a unit. Deliberately permissive - «100 m», «100m», «100 mbps» and
     * «1g» are all forms Hysteria2 takes, so the only thing refused is a value with no digit in it.
     */
    private fun checkBandwidth(layout: TextInputLayout?, field: EditText?): Boolean {
        val typed = field?.text?.toString()?.trim().orEmpty()
        if (typed.isEmpty() || typed.any { it.isDigit() }) return true
        refuse(layout, field, R.string.srv_bandwidth_invalid)
        return false
    }

    /** Three numbers 0-255, separated by commas, and nothing else. */
    private fun isReservedTriple(value: String): Boolean {
        val parts = value.split(',')
        return parts.size == 3 && parts.all { part ->
            part.trim().toIntOrNull()?.let { it in 0..RESERVED_MAX } == true
        }
    }

    /**
     * An optional number: empty is fine, anything that is not a whole number above zero is not.
     * Returns false once it has written the refusal into the field.
     */
    private fun checkOptionalPositiveNumber(layout: TextInputLayout?, field: EditText?): Boolean {
        val typed = field?.text?.toString()?.trim().orEmpty()
        if (typed.isEmpty()) return true
        val value = typed.toIntOrNull()
        if (value == null || value <= 0) {
            refuse(layout, field, R.string.srv_number_invalid)
            return false
        }
        return true
    }

    /**
     * A refusal stops being true the moment the user starts fixing it, so it leaves then rather
     * than at the next «Сохранить». Wired once, over the same list [clearErrors] walks.
     */
    private fun watchErrorSlots() {
        errorSlots().forEach { layout ->
            val field = layout?.editText ?: return@forEach
            field.doAfterTextChanged { if (layout.error != null) layout.error = null }
        }
    }

    /** Every field's error slot, emptied before a fresh pass. */
    private fun clearErrors() {
        errorSlots().forEach { it?.error = null }
    }

    /** Every field on every one of the eight forms that has an error slot to write into. */
    private fun errorSlots(): List<TextInputLayout?> = listOf(
        til_remarks, til_address, til_port, til_id, til_security, til_flow, til_method,
        til_network, til_header_type, til_request_host, til_path,
        til_kcp_mtu, til_kcp_tti, til_extra, til_fm, til_browser_dialer_mode,
        til_stream_security, til_sni, til_stream_fingerprint, til_stream_alpn,
        til_preshared_key, til_reserved1, til_local_address, til_local_mtu,
        til_obfs_password, til_port_hop, til_port_hop_interval,
        til_bandwidth_down, til_bandwidth_up,
        til_allow_insecure, til_ech_config_list, til_pinned_ca256,
        til_public_key, til_short_id, til_spider_x, til_mldsa65_verify,
    )

    private fun saveCommon(config: ProfileItem) {
        config.remarks = et_remarks.text.toString().trim()
        config.server = et_address.text.toString().trim()
        config.serverPort = et_port.text.toString().trim()
        config.password = et_id.text.toString().trim()

        if (config.configType == EConfigType.VMESS) {
            config.method = securitys[methodIndex.coerceIn(securitys.indices)]
        } else if (config.configType == EConfigType.VLESS) {
            config.method = et_security?.text.toString().trim()
            config.flow = flows[flowIndex.coerceIn(flows.indices)]
        } else if (config.configType == EConfigType.SHADOWSOCKS) {
            config.method = shadowsocksSecuritys[methodIndex.coerceIn(shadowsocksSecuritys.indices)]
        } else if (config.configType == EConfigType.SOCKS || config.configType == EConfigType.HTTP) {
            if (!TextUtils.isEmpty(et_security?.text) || !TextUtils.isEmpty(et_id.text)) {
                config.username = et_security?.text.toString().trim()
            }
        } else if (config.configType == EConfigType.TROJAN) {
        } else if (config.configType == EConfigType.WIREGUARD) {
            config.secretKey = et_id.text.toString().trim()
            config.publicKey = et_public_key?.text.toString().trim()
            config.preSharedKey = et_preshared_key?.text.toString().trim()
            config.reserved = et_reserved1?.text.toString().trim()
            config.localAddress = et_local_address?.text.toString().trim()
            config.mtu = Utils.parseInt(et_local_mtu?.text.toString())
        } else if (config.configType == EConfigType.HYSTERIA2) {
            config.obfsPassword = et_obfs_password?.text?.toString()
            config.portHopping = et_port_hop?.text?.toString()
            config.portHoppingInterval = et_port_hop_interval?.text?.toString()?.trim()
            config.bandwidthDown = et_bandwidth_down?.text?.toString()
            config.bandwidthUp = et_bandwidth_up?.text?.toString()
        }
    }


    private fun saveStreamSettings(profileItem: ProfileItem) {
        // The guard the `Spinner`s used to give for free: a screen without the transport group
        // (SOCKS, WireGuard, Hysteria2) has none of these views and writes none of these fields.
        if (et_network == null) return
        val requestHost = et_request_host?.text?.toString()?.trim() ?: return
        val path = et_path?.text?.toString()?.trim() ?: return

        val network = networks[networkIndex]
        val types = transportTypes(network)
        val headerType = types.getOrElse(headerTypeIndex) { types.first() }

        profileItem.network = network
        profileItem.headerType = headerType
        profileItem.host = requestHost
        profileItem.path = path
        profileItem.seed = path
        profileItem.quicSecurity = requestHost
        profileItem.quicKey = path
        profileItem.mode = headerType
        profileItem.serviceName = path
        profileItem.authority = requestHost
        profileItem.xhttpMode = headerType
        profileItem.xhttpExtra = et_extra?.text?.toString()?.trim().nullIfBlank()
        profileItem.finalMask = et_fm?.text?.toString()?.trim()?.nullIfBlank()
        profileItem.kcpMtu = et_kcp_mtu?.text?.toString()?.toIntOrNull()
        profileItem.kcpTti = et_kcp_tti?.text?.toString()?.toIntOrNull()
        if (network == NetworkType.WS.type || network == NetworkType.XHTTP.type) {
            val browserDialerMode = browserDialerModes[browserDialerIndex]
            if (browserDialerMode != browserDialerModes[0]) {
                profileItem.browserDialerMode = browserDialerMode
            } else {
                profileItem.browserDialerMode = null
            }
        } else {
            profileItem.browserDialerMode = null
        }
    }

    private fun saveTls(config: ProfileItem) {
        // Same guard as [saveStreamSettings]: no picker on screen, no TLS fields to write.
        if (et_stream_security == null) return
        val sniField = et_sni?.text?.toString()?.trim()
        val allowInsecureField = et_allow_insecure?.let { allowInsecureIndex }
        val publicKey = et_public_key?.text?.toString()
        val shortId = et_short_id?.text?.toString()
        val spiderX = et_spider_x?.text?.toString()
        val mldsa65Verify = et_mldsa65_verify?.text?.toString()
        val echConfigList = et_ech_config_list?.text?.toString()
        val pinnedCA256 = et_pinned_ca256?.text?.toString()

        val allowInsecure =
            if (allowInsecureField == null || allowinsecures[allowInsecureField].isBlank()) {
                MmkvManager.decodeSettingsBool(PREF_ALLOW_INSECURE)
            } else {
                allowinsecures[allowInsecureField].toBoolean()
            }

        config.security = streamSecuritys[streamSecurityIndex]
        config.insecure = allowInsecure
        config.sni = sniField
        config.fingerPrint = uTlsItems[fingerprintIndex]
        config.alpn = alpns[alpnIndex]
        config.publicKey = publicKey
        config.shortId = shortId
        config.spiderX = spiderX
        config.mldsa65Verify = mldsa65Verify
        config.echConfigList = echConfigList
        config.pinnedCA256 = pinnedCA256
    }

    private fun transportTypes(network: String?): Array<out String> {
        return when (network) {
            NetworkType.TCP.type -> {
                tcpTypes
            }

            NetworkType.KCP.type -> {
                kcpAndQuicTypes
            }

            NetworkType.GRPC.type -> {
                grpcModes
            }

            NetworkType.XHTTP.type -> {
                xhttpMode
            }

            else -> {
                arrayOf(NO_TRANSPORT_TYPE)
            }
        }
    }

    /**
     * delete server config
     */
    /**
     * Deleting from the form, always confirmed, in the words the neighbouring editors use.
     *
     * It used to ask only when `PREF_CONFIRM_REMOVE` was set - a key nothing in this app writes, so
     * the branch was dead and the toolbar's bin glyph destroyed the server on the first tap with no
     * way back (00-rules.md 7.5). The refusal on the running server was `toast_action_not_allowed`,
     * a string that says a thing is not allowed without saying which thing.
     */
    private fun deleteServer() {
        if (editGuid.isEmpty()) return
        if (editGuid == MmkvManager.getSelectServer()) {
            toastError(R.string.srv_delete_selected)
            return
        }
        AlertDialog.Builder(this)
            .setMessage(R.string.srv_delete_confirm)
            .setPositiveButton(R.string.editor_delete) { _, _ ->
                MmkvManager.removeServer(editGuid)
                SubPage.close(this)
            }
            .setNegativeButton(R.string.editor_cancel, null)
            .show()
    }

    /**
     * The protocol half of the screen title, dropped into «Сервер %s».
     *
     * The name itself is a technical identifier and stays Latin (00-rules.md 1.4.10), but the
     * SUBJECT of the title is Russian: the three editors beside this one are «Группа серверов»,
     * «Своя конфигурация» and «Цепочка прокси», and a bare «VLESS» falls out of that row.
     * `EConfigType.toString()` printed the enum constant - «SHADOWSOCKS», «WIREGUARD» - which is
     * neither how those two projects spell their own names nor allowed by the no-caps rule (0.4.3).
     */
    @StringRes
    private fun protocolTitle(type: EConfigType): Int = when (type) {
        EConfigType.VMESS -> R.string.srv_proto_vmess
        EConfigType.VLESS -> R.string.srv_proto_vless
        EConfigType.TROJAN -> R.string.srv_proto_trojan
        EConfigType.SHADOWSOCKS -> R.string.srv_proto_shadowsocks
        EConfigType.SOCKS -> R.string.srv_proto_socks
        EConfigType.HTTP -> R.string.srv_proto_http
        EConfigType.WIREGUARD -> R.string.srv_proto_wireguard
        else -> R.string.srv_proto_hysteria2
    }
}
