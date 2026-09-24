package com.v2ray.ang.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.FragmentSettingsTabBinding
import com.v2ray.ang.enums.PingMethod
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.tv.TvReceiveActivity
import com.v2ray.ang.tv.TvSendActivity
import com.v2ray.ang.ui.component.SelectPopup
import com.v2ray.ang.ui.component.onSingleClick
import com.v2ray.ang.ui.component.restoreChecked
import com.v2ray.ang.ui.component.pressFeedback
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils

/**
 * The Настройки tab: the custom Incy settings screen that replaced the old navigation drawer.
 *
 * Every row lives here — the pickers, the toggles and the launches into the sub-screens (per-app
 * proxy, routing, assets, «Дополнительно», backup, logs, about, …). Toggles and pickers read and
 * write the same MMKV keys the legacy `SettingsActivity` preference tree uses, so a value changed
 * on either surface is the same value. Switches are non-focusable in XML, so the whole row drives
 * them — there are no CheckedChange listeners, which is what keeps [bindSettingsState] from
 * feeding a change back into itself. A switch is painted from stored state through
 * [restoreChecked], which is the difference between a value being read back and a value being
 * chosen: the first must appear already settled, only the second is allowed to animate.
 *
 * **EVERY ROW ON THIS TAB IS ONE HEIGHT, 60dp, and no row carries a subtitle** (owner report
 * 0.4.9: «надо подравнять такие кнопки под обычный размер как условно у dns, у режима, чтобы была
 * единая высота правильная»). There were THREE heights and TWO independent causes — nine rows with
 * a second line whose LENGTH decided the row's height, and Material's 48dp `minTouchTargetSize`
 * floor on every switch, 8dp above the 40dp tile. Both live in `fragment_settings_tab.xml`, which
 * explains each in full; the nine dropped strings are still in values/, unread and commented so.
 * Nothing here changed, because nothing here ever set a subtitle: the rows are written in the
 * layout, and that is their call site.
 *
 * What the shell still owns: recreating the activity for a theme/language change, restarting a
 * running tunnel after a core-config change, and the result launcher that applies both when a
 * sub-screen returns ([MainHost.launchSettingsScreen]). Nothing here casts to `MainActivity`.
 *
 * The tab's fragment is added the first time the tab is opened and then only hidden and shown, so
 * the view can outlive a bind; [onResume] therefore re-reads the persisted state through
 * [isBindingInitialized], which is what keeps a value changed in a sub-screen (or in another app,
 * as with «Always-on VPN») from being left stale on a tab that is never recreated.
 */
class SettingsTabFragment : BaseFragment<FragmentSettingsTabBinding>() {

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentSettingsTabBinding =
        FragmentSettingsTabBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyListInsets()
        setupSettings()
    }

    /**
     * The bottom navigation OVERLAYS this tab — the cards scroll under its scrim rather than
     * stopping at a solid bar — so the last row of the last card only clears the buttons if the
     * scroll CONTENT reserves their footprint. It did not: the layout's own 16dp of breathing room
     * was all there was, and «Схемы URL-адресов» sat half behind the bar with the list scrolled to
     * its end (owner's report from the device).
     *
     * The figure is the shell's ([MainHost.listBottomInset]: the window's bottom inset + the 56dp
     * bar + breathing room) and not a number written here, because the two phones the owner runs
     * disagree about it by the height of a gesture bar. Floored at the layout's own 16 so a dispatch
     * that has not happened yet cannot leave the last card flush against the edge — the same shape
     * `HomeFragment.applyListInsets` uses, deliberately, rather than a second mechanism for one
     * screen.
     *
     * Called on every inset dispatch by the shell too (`MainActivity.setupEdgeToEdge`): insets are
     * not re-dispatched just because this tab was finally opened, and this tab is attached lazily.
     */
    fun applyListInsets() {
        if (!isBindingInitialized) return
        val floor = resources.getDimensionPixelSize(R.dimen.space_16)
        binding.settingsContent.updatePadding(bottom = maxOf(mainHost.listBottomInset, floor))
    }

    /**
     * Re-reads every persisted value each time the tab comes back to the foreground: a sub-screen,
     * the system VPN settings or another entry point may have changed one while this view was up
     * but not in front. Hidden tabs stay RESUMED, so this also runs for a tab that is attached but
     * not on screen — which is exactly what makes it correct the moment it is shown again.
     */
    override fun onResume() {
        super.onResume()
        bindSettingsState()
    }

    /**
     * A flyout must not outlive the tab that opened it.
     *
     * [SelectPopup] hosts itself at the ACTIVITY's content root rather than inside this fragment —
     * that is what keeps a section card from slicing it off (README §11 grabl 4), and it is also
     * why it cannot notice that Настройки is no longer the tab in front. The shell hides tabs
     * instead of replacing them, so a hidden tab stays RESUMED and not one lifecycle callback fires
     * on its own: without this, the DNS list would still be hanging over Главная.
     *
     * The guard asks whether the open popup is one of OUR rows' before closing it. `dismiss()` is
     * static and would otherwise reach across screens — an activity started over this one stops the
     * tab underneath, and closing that screen's popup from here would be this fragment reaching
     * into a surface it does not own.
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) dismissOwnPopup()
    }

    override fun onStop() {
        super.onStop()
        dismissOwnPopup()
    }

    private fun dismissOwnPopup() {
        if (!isBindingInitialized) return
        if (rows().any { SelectPopup.isShowing(it) }) SelectPopup.dismiss()
    }

    // Context-scoped helpers so the ported (Context.toast) call sites work from a Fragment. They
    // take a STRING ID and never built text: NoticePolicy recognises a message by its id, and a
    // CharSequence is by definition something it cannot vouch for.
    private fun toast(message: Int) = requireContext().toast(message)
    private fun toastError(message: Int) = requireContext().toastError(message)

    /**
     * Wires every row. Click handlers only — nothing here reads state, so the wiring runs once per
     * view and [bindSettingsState] stays the single place that paints values.
     *
     * Every row goes through [onSingleClick] rather than `setOnClickListener`, which is the repo's
     * own R9 rule («double-press is impossible by construction», П-31): two taps 80ms apart on
     * «Журнал» used to push two LogcatActivity instances, and the guard costs one word per row.
     */
    private fun setupSettings() {
        val s = binding

        rows().forEach { it.pressFeedback(R.anim.press_row) }

        // ПОДКЛЮЧЕНИЕ
        s.rowMode.onSingleClick { pickMode() }
        s.rowPerApp.onSingleClick {
            mainHost.launchSettingsScreen(Intent(requireContext(), PerAppProxyActivity::class.java))
        }
        s.rowBypassLan.onSingleClick { toggleBypassLan() }
        s.rowIpv6.onSingleClick { toggleIpv6() }
        s.rowDns.onSingleClick { editDns() }
        s.rowPingMethod.onSingleClick { pickPingMethod() }
        // «Локальный прокси» пишет ключи, которые читает конфиг ядра (инбаунды socks/http, их
        // порт, UDP, системный HTTP-прокси, доступ по сети). Открывать его обычным
        // startActivity нельзя: флаг SettingsChangeManager.restartService, который экран
        // выставляет, потребляет только launchSettingsScreen — иначе выключенный локальный
        // прокси остаётся поднятым до следующего ручного переподключения.
        s.rowLocalProxy.onSingleClick {
            mainHost.launchSettingsScreen(Intent(requireContext(), LocalProxyActivity::class.java))
        }
        s.rowAlwaysOn.onSingleClick { openAlwaysOnSettings() }

        // ОБХОД БЛОКИРОВОК
        s.rowMux.onSingleClick { toggleMux() }
        s.rowMuxConcurrency.onSingleClick { editMuxConcurrency() }
        s.rowFragment.onSingleClick { toggleFragment() }

        // ИНТЕРФЕЙС
        s.rowAppearance.onSingleClick { pickAppearance() }
        s.rowLanguage.onSingleClick { pickLanguage() }
        s.rowBoot.onSingleClick { toggleStartOnBoot() }

        // ПОДПИСКА
        // «Список подписок» (SubSettingActivity) и «Другие способы добавления»
        // (MainHost.showAdvancedAddMethods) убраны отсюда по прямому указанию владельца
        // (2026-08-02). Что при этом стало недостижимо — записано в комментарии на их месте в
        // fragment_settings_tab.xml; ни один экран и ни одна функция не удалены.
        s.rowSubAutoUpdate.onSingleClick { pickSubAutoUpdate() }
        s.rowRouting.onSingleClick {
            mainHost.launchSettingsScreen(Intent(requireContext(), RoutingSettingActivity::class.java))
        }
        s.rowAssets.onSingleClick {
            mainHost.launchSettingsScreen(Intent(requireContext(), UserAssetActivity::class.java))
        }
        // «Настройки подписок» меняет порядок списка серверов и просит оболочку перестроить его
        // (SettingsChangeManager.setupGroupTab). Этот флаг тоже потребляет только
        // launchSettingsScreen, поэтому через startActivity новый порядок не доезжал до
        // Главной до следующего перезапуска.
        s.rowProvider.onSingleClick {
            mainHost.launchSettingsScreen(Intent(requireContext(), ProviderSettingsActivity::class.java))
        }

        // УСТРОЙСТВА
        s.rowTvSend.onSingleClick { startActivity(Intent(requireContext(), TvSendActivity::class.java)) }
        // "Принять подписку" only makes sense on an Android TV device.
        val isTv = requireContext().packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        s.rowTvReceive.isVisible = isTv
        s.dividerTvReceive.isVisible = isTv
        s.rowTvReceive.onSingleClick { startActivity(Intent(requireContext(), TvReceiveActivity::class.java)) }
        // The section card no longer clips its children (README §11 grabl 4), so its first and last
        // row carry the card's corner themselves. On a phone «Устройства» has one visible row, and a
        // row that is both first and last needs all four — @drawable/bg_row_top would leave the
        // card's bottom edge square.
        if (!isTv) s.rowTvSend.setBackgroundResource(R.drawable.bg_row_only)

        // О ПРИЛОЖЕНИИ
        // CheckUpdateActivity and LogcatActivity were declared in the manifest and referenced from
        // nowhere; a screen with no launch site is not a feature, so each one keeps its row here.
        // «Дополнительно» (SettingsActivity) was removed by the owner on 2026-08-02 — see
        // fragment_settings_tab.xml for the record of what that takes off the map.
        s.rowLogs.onSingleClick { startActivity(Intent(requireContext(), LogcatActivity::class.java)) }
        s.rowCheckUpdate.onSingleClick { startActivity(Intent(requireContext(), CheckUpdateActivity::class.java)) }
        s.rowAbout.onSingleClick { startActivity(Intent(requireContext(), AboutActivity::class.java)) }
        s.rowUrlScheme.onSingleClick { startActivity(Intent(requireContext(), UrlSchemeListActivity::class.java)) }
        s.rowBackup.onSingleClick {
            mainHost.launchSettingsScreen(Intent(requireContext(), BackupActivity::class.java))
        }
        s.valueAbout.text = BuildConfig.VERSION_NAME

        bindSettingsState()
    }

    /**
     * All 25 rows, in screen order.
     *
     * They are listed once so the press response cannot be attached to twenty-four of them. XML
     * already carries `@anim/press_row` on each — [pressFeedback] re-attaches the same animator and
     * adds the hardware layer that keeps a Russian label from twitching when the 2% rebound lands
     * (README §11 grabl 1), which is the one part of the press that cannot be declared.
     */
    private fun rows(): List<View> = with(binding) {
        listOf(
            rowMode, rowPerApp, rowBypassLan, rowIpv6, rowDns, rowPingMethod, rowLocalProxy,
            rowAlwaysOn,
            rowMux, rowMuxConcurrency, rowFragment,
            rowAppearance, rowLanguage, rowBoot,
            rowSubAutoUpdate, rowRouting, rowAssets, rowProvider,
            rowTvSend, rowTvReceive,
            rowLogs, rowCheckUpdate, rowAbout, rowBackup, rowUrlScheme,
        )
    }

    /** Reflects all persisted settings values/toggle states into the settings tab. */
    private fun bindSettingsState() {
        if (!isBindingInitialized) return
        val s = binding

        // The row states the mode that is actually in force. See [pickMode] for why the LAN-sharing
        // switch is not read here any more.
        s.valueMode.text = getString(
            when (currentMode()) {
                Mode.PROXY -> R.string.hub_mode_proxy
                Mode.TUN -> R.string.settings_mode_value_tun
            }
        )

        val perApp = MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY, false)
        s.valuePerApp.text = getString(if (perApp) R.string.settings_value_on else R.string.settings_value_off)

        s.valueDns.text = dnsLabel(MmkvManager.decodeSettingsString(AppConfig.PREF_VPN_DNS, AppConfig.DNS_VPN).orEmpty())
        s.valuePingMethod.text = getString(pingMethodLabelRes(SettingsManager.getPingMethod()))
        s.valueMuxConcurrency.text = MmkvManager.decodeSettingsString(AppConfig.PREF_MUX_CONCURRENCY, "8")

        val langEntries = resources.getStringArray(R.array.language_select)
        val langValues = resources.getStringArray(R.array.language_select_value)
        val curLang = MmkvManager.decodeSettingsString(AppConfig.PREF_LANGUAGE, langValues.firstOrNull() ?: "auto").orEmpty()
        val li = langValues.indexOf(curLang).coerceAtLeast(0)
        s.valueLanguage.text = langEntries.getOrElse(li) { langEntries.firstOrNull().orEmpty() }

        s.valueSubAutoUpdate.text = currentSubAutoUpdateLabel()

        s.switchBypassLan.restoreChecked(isBypassLanOn())
        s.switchIpv6.restoreChecked(MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED, false))

        val muxOn = MmkvManager.decodeSettingsBool(AppConfig.PREF_MUX_ENABLED, false)
        s.switchMux.restoreChecked(muxOn)
        s.rowMuxConcurrency.isVisible = muxOn
        s.dividerConcurrency.isVisible = muxOn

        s.switchFragment.restoreChecked(MmkvManager.decodeSettingsBool(AppConfig.PREF_FRAGMENT_ENABLED, false))
        s.valueAppearance.text = getString(appearanceLabelRes(currentAppearanceIndex()))
        s.switchBoot.restoreChecked(MmkvManager.decodeStartOnBoot())
    }

    // The switch-restore rule this tab was the first to need — «при входе в настройки переключатели
    // дёргаются, типа отключаются включаются очень быстро» — now lives ONCE, in
    // `ui/component/ComponentSupport.kt`, where every other screen with a switch can reach it: the
    // whole reasoning (why `setChecked` is not enough, why the guard matters) is in its doc. It
    // stayed private here for a while and every other screen kept the defect, which is the same
    // shape as the mono-theme tile that had to be found three times.
    //
    // Every switch on this tab is READ BACK through it from [bindSettingsState]; the only calls left
    // on `isChecked` are the [toggleBypassLan]-style handlers, which are the user flipping the
    // switch and must animate.

    // «Обход локальной сети» is OFF until somebody turns it on (owner report 0.4.9). The switch and
    // the tunnel have to agree on that, so this default and the one in
    // [SettingsManager.routingRulesetsBypassLan] are the same "2" and move together — the switch
    // reading one default while the core read the other is how a setting lies about itself.
    // The key is a STRING, so "never touched" (null) stays distinguishable from "turned off" ("2")
    // and from "turned on" ("1"): an install that enabled this on purpose is not disturbed.
    private fun isBypassLanOn(): Boolean =
        MmkvManager.decodeSettingsString(AppConfig.PREF_VPN_BYPASS_LAN, "2") != "2"

    private fun isMonoOn(): Boolean =
        MmkvManager.decodeSettingsString(AppConfig.PREF_COLOR_THEME, BaseActivity.THEME_BLUE) == BaseActivity.THEME_MONO

    /** Restart the tunnel so a changed core-config setting takes effect immediately. */
    private fun restartIfRunning() {
        if (mainViewModel.isRunning.value == true) mainHost.restartConnection()
    }

    /**
     * «Режим» — the first of six select popups (handoff README §6). The dialog this replaced dimmed
     * the whole screen to ask a two-word question; the flyout opens where the value already is.
     *
     * THE LIST IS THE DESIGN'S TWO, and it is now also the whole truth. The prototype's
     * `MODES = ['TUN', 'Только прокси']`, and the owner confirmed on 2026-08-04 that the list shows
     * exactly that — «интерфейс и формулировки по дизайну, возможности по репозиторию».
     *
     *   0 TUN           = VPN(tun) mode, `isVpnMode() == true`
     *   1 Только прокси = proxy-only mode, `isVpnMode() == false`
     *
     * **«TUN + Proxy» IS NOT A THIRD MODE, AND TREATING IT AS ONE BROKE A DIFFERENT SCREEN.** It was
     * this row reading `PREF_PROXY_SHARING` — which is not a mode but the «Доступ через хотспот»
     * switch on «Локальный прокси», an independent feature that adds an authenticated SOCKS inbound
     * on 0.0.0.0 and works in EITHER mode. Turning that switch on, which is an offered thing to do,
     * therefore:
     *
     *  - made this row report «TUN + Proxy» when nothing about the mode had changed;
     *  - opened the picker with NOTHING selected, over a plain TUN tunnel;
     *  - and then, on picking «TUN» — the value already in force — silently wrote
     *    `PREF_PROXY_SHARING = false`, switching the LAN share off behind the user's back. Nobody
     *    asked about sharing; they re-picked the mode they were already on.
     *
     * The mode is `PREF_MODE` and nothing else now, so the row states it, the picker pre-selects it,
     * and the hotspot switch is owned by the one screen that offers it.
     */
    private fun pickMode() {
        val entries = listOf(
            getString(R.string.settings_mode_value_tun),
            getString(R.string.hub_mode_proxy),
        )
        val idx = when (currentMode()) {
            Mode.PROXY -> 1
            Mode.TUN -> 0
        }
        SelectPopup.show(
            anchor = binding.rowMode,
            options = entries,
            selectedIndex = idx,
            widthRes = R.dimen.select_popup_w_mode,
            valueView = binding.valueMode,
            caret = binding.caretMode,
        ) { which ->
            if (which == 0) { // TUN
                MmkvManager.encodeSettings(AppConfig.PREF_MODE, AppConfig.VPN)
            } else { // Только прокси
                MmkvManager.encodeSettings(AppConfig.PREF_MODE, "Proxy only")
            }
            bindSettingsState()
            restartIfRunning()
        }
    }

    /** The two modes the prefs can be in. LAN sharing is not one of them; see [pickMode]. */
    private enum class Mode { TUN, PROXY }

    private fun currentMode(): Mode {
        val mode = MmkvManager.decodeSettingsString(AppConfig.PREF_MODE, AppConfig.VPN)
        return if (mode != AppConfig.VPN) Mode.PROXY else Mode.TUN
    }

    /**
     * The ping methods in the order the design lists them: `PINGS = ['Реальная задержка',
     * 'HTTP-запрос', 'TCP-соединение', 'ICMP (ping)']`. All four stay (П-26) — the list reordered
     * and two labels got shorter, the methods did not change.
     */
    private val pingMethods = listOf(
        PingMethod.PROXIED_REAL_DELAY,
        PingMethod.HTTP_URL,
        PingMethod.TCP_CONNECT,
        PingMethod.ICMP,
    )

    /** Maps a ping method to its short Russian label shown on the settings row. */
    private fun pingMethodLabelRes(method: PingMethod): Int = when (method) {
        PingMethod.PROXIED_REAL_DELAY -> R.string.hub_ping_real
        PingMethod.TCP_CONNECT -> R.string.settings_ping_method_tcp
        PingMethod.HTTP_URL -> R.string.settings_ping_method_http
        PingMethod.ICMP -> R.string.hub_ping_icmp
    }

    /**
     * «Пинг». Writes the same [AppConfig.PREF_PING_METHOD] key the "test all" logic reads via
     * [SettingsManager.getPingMethod], so the choice changes ping behaviour immediately — including
     * the per-row spinner on Главная, which is the same measurement seen from the other end.
     */
    private fun pickPingMethod() {
        val entries = pingMethods.map { getString(pingMethodLabelRes(it)) }
        val idx = pingMethods.indexOf(SettingsManager.getPingMethod()).coerceAtLeast(0)
        SelectPopup.show(
            anchor = binding.rowPingMethod,
            options = entries,
            selectedIndex = idx,
            widthRes = R.dimen.select_popup_w_ping,
            valueView = binding.valuePingMethod,
            caret = binding.caretPingMethod,
        ) { which ->
            MmkvManager.encodeSettings(AppConfig.PREF_PING_METHOD, pingMethods[which].prefValue)
            bindSettingsState()
        }
    }

    private fun toggleBypassLan() {
        val on = !isBypassLanOn()
        MmkvManager.encodeSettings(AppConfig.PREF_VPN_BYPASS_LAN, if (on) "1" else "2")
        binding.switchBypassLan.isChecked = on
        restartIfRunning()
    }

    private fun toggleIpv6() {
        val enabled = !MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED, false)
        MmkvManager.encodeSettings(AppConfig.PREF_IPV6_ENABLED, enabled)
        binding.switchIpv6.isChecked = enabled
        restartIfRunning()
    }

    /**
     * Deep-links into the system VPN settings screen where the user enables Android's
     * built-in "Always-on VPN" and "Block connections without VPN" (kill-switch). This is a
     * system-level toggle — the app only provides the shortcut and a one-line explainer.
     */
    private fun openAlwaysOnSettings() {
        toast(R.string.settings_always_on_hint)
        try {
            startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS))
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to open system VPN settings", e)
            // The device has no VPN settings screen to open — a dead end the row cannot show, so
            // it keeps a sentence. `toast_failure` («Ошибка») was upstream's word for every
            // outcome in the app and says nothing about this one.
            toastError(R.string.settings_always_on_unavailable)
        }
    }

    /** Maps a stored DNS value to its friendly preset name, or returns the raw value. */
    private fun dnsLabel(value: String): String {
        val names = resources.getStringArray(R.array.dns_preset_names)
        val values = resources.getStringArray(R.array.dns_preset_values)
        val i = values.indexOfFirst { it.isNotEmpty() && it == value }
        return if (i >= 0) names.getOrElse(i) { value } else value
    }

    /**
     * «DNS» — five presets plus «Свой…». The presets are a select popup; «Свой…» is the one option
     * that cannot be, because it asks for a value rather than offering one, and §6 puts free text in
     * a field. It hands off to [editDnsCustom] after the flyout has closed itself.
     *
     * Writes the selected server(s) into [AppConfig.PREF_VPN_DNS] as a comma-separated list (same
     * key and format as before).
     */
    private fun editDns() {
        val names = resources.getStringArray(R.array.dns_preset_names).toList()
        val values = resources.getStringArray(R.array.dns_preset_values)
        val current = MmkvManager.decodeSettingsString(AppConfig.PREF_VPN_DNS, AppConfig.DNS_VPN).orEmpty()
        // The last entry is the custom option (empty value).
        val customIdx = values.size - 1
        val preset = values.indexOfFirst { it.isNotEmpty() && it == current }
        // A custom DNS marks NOTHING in the list, and that is deliberate. SelectPopup does not fire
        // `onPick` when the current selection is re-picked — correct for a value, wrong for a door:
        // with «Свой…» checked, the one tap that reopens the editor would be the one tap the popup
        // swallows, and a user who had typed their own resolver could never change it again. No
        // checkmark also states the truth — the active value is none of these presets — and the row
        // itself is already showing what it is.
        val idx = preset
        SelectPopup.show(
            anchor = binding.rowDns,
            options = names,
            selectedIndex = idx,
            widthRes = R.dimen.select_popup_w_dns,
            valueView = binding.valueDns,
            caret = binding.caretDns,
        ) { which ->
            if (which == customIdx) {
                editDnsCustom(current)
            } else {
                // Write both the tun DNS (PREF_VPN_DNS) and the proxied-lookup DNS
                // (PREF_REMOTE_DNS, read by SettingsManager.getRemoteDnsServers), so
                // picking a preset like Cloudflare applies to proxied resolution too.
                MmkvManager.encodeSettings(AppConfig.PREF_VPN_DNS, values[which])
                MmkvManager.encodeSettings(AppConfig.PREF_REMOTE_DNS, values[which])
                bindSettingsState()
                restartIfRunning()
            }
        }
    }

    /**
     * Free-text DNS editor, reached via the "Свой…" preset option.
     *
     * **IT REFUSES WHAT THE READING CODE WOULD THROW AWAY.** `SettingsManager.getVpnDnsServers`
     * keeps only pure IP addresses and, when nothing survives, quietly returns the default — so any
     * other text (a bare hostname, a DoH URL, a typo) was accepted here, printed back on the row as
     * the current value, and the tunnel resolved through 1.1.1.1 instead. The screen said one thing
     * and the machine did another, which is the one thing «Дополнительно» already forbids of its own
     * fields («Ни одно не сохранит значение, которое читающий код молча выбросит»).
     *
     * Every preset in the list is a pure IP, so this is the same alphabet, not a narrower one. The
     * dialog stays open on a bad value with the reason under the field, because a dialog that closes
     * on a refusal has thrown the typing away too.
     */
    private fun editDnsCustom(current: String) {
        val field = TextInputLayout(requireContext()).apply {
            isErrorEnabled = true
            hint = getString(R.string.settings_dns_hint)
        }
        val input = TextInputEditText(requireContext()).apply {
            setText(current)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        field.addView(input)
        val pad = resources.getDimensionPixelSize(R.dimen.space_24)
        val holder = FrameLayout(requireContext()).apply {
            setPadding(pad, pad, pad, 0)
            addView(field)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_dns)
            .setView(holder)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = normalizeDnsAddresses(input.text?.toString().orEmpty())
                if (value == null) {
                    field.error = getString(R.string.settings_dns_error)
                    return@setOnClickListener
                }
                field.error = null
                MmkvManager.encodeSettings(AppConfig.PREF_VPN_DNS, value)
                MmkvManager.encodeSettings(AppConfig.PREF_REMOTE_DNS, value)
                bindSettingsState()
                restartIfRunning()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /**
     * A comma-separated list of DNS addresses, trimmed and re-joined — or null when any item is not
     * one. An empty field is not a value either: it used to mean «put the default back», silently,
     * from a field the user opened to type something into.
     */
    private fun normalizeDnsAddresses(raw: String): String? {
        val items = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (items.isEmpty()) return null
        if (items.any { !Utils.isPureIpAddress(it) }) return null
        return items.joinToString(",")
    }

    private fun toggleMux() {
        val enabled = !MmkvManager.decodeSettingsBool(AppConfig.PREF_MUX_ENABLED, false)
        MmkvManager.encodeSettings(AppConfig.PREF_MUX_ENABLED, enabled)
        val s = binding
        s.switchMux.isChecked = enabled
        s.rowMuxConcurrency.isVisible = enabled
        s.dividerConcurrency.isVisible = enabled
        restartIfRunning()
    }

    private fun editMuxConcurrency() {
        val current = MmkvManager.decodeSettingsString(AppConfig.PREF_MUX_CONCURRENCY, "8")
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(current)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_mux_concurrency)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = (input.text.toString().toIntOrNull() ?: 8).coerceIn(1, 1024)
                MmkvManager.encodeSettings(AppConfig.PREF_MUX_CONCURRENCY, value.toString())
                bindSettingsState()
                restartIfRunning()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toggleFragment() {
        val enabled = !MmkvManager.decodeSettingsBool(AppConfig.PREF_FRAGMENT_ENABLED, false)
        MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_ENABLED, enabled)
        binding.switchFragment.isChecked = enabled
        restartIfRunning()
    }

    /**
     * «Оформление» — four choices, «Как в системе» FIRST (owner 0.4.9: «также в оформлении надо
     * чтобы 1 была кнопка как в системе и чтобы оно включалось по умолчанию»):
     *   0 = Как в системе -> MODE_NIGHT_FOLLOW_SYSTEM ("0") + blue accent
     *   1 = Тёмная        -> MODE_NIGHT_YES ("2") + blue accent (night resources)
     *   2 = Светлая       -> MODE_NIGHT_NO  ("1") + blue accent (day resources, dark bar icons)
     *   3 = Чёрно-белая   -> monochrome overlay, keeping the current night mode as-is
     *
     * That is a departure from the prototype's `THEMES_OPTS = ['Тёмная', 'Светлая', 'Чёрно-белая',
     * 'Как в системе']`, and it is the owner's, recorded here so it is not "corrected" back.
     *
     * **REORDERING THIS LIST CANNOT REMAP ANYBODY'S STORED CHOICE, and that is a property of the
     * storage rather than luck.** The index below is never persisted: it is computed FROM storage
     * on every open, and what is written is [AppConfig.PREF_UI_MODE_NIGHT] as the strings "0" /
     * "1" / "2" plus [AppConfig.PREF_COLOR_THEME]. Those three strings are AppCompat's own
     * encoding, shared with `arrays.xml`'s `ui_mode_night_value`, with
     * [SettingsManager.setNightMode] and with the legacy preference tree — so an install that
     * stored «Тёмная» stored "2" and still reads back as «Тёмная» at its new index 1. No migration
     * is needed and none was written; a migration here would have been the bug, not the fix.
     *
     * The fallback below is "0" and no longer "2", so that the ONE question this function answers
     * — what is showing when nothing has been stored — gets the same answer as
     * [SettingsManager.setNightMode], which has always fallen back to "0" = FOLLOW_SYSTEM. The two
     * disagreed before: with the key absent the row said «Тёмная» while AppCompat was following the
     * system, which is the row lying about the screen it is on.
     *
     * **THE FRESH-INSTALL DEFAULT IS NOT DECIDED HERE**, and it is still «Тёмная».
     * `SettingsManager.ensureDefaultSettings()` WRITES `PREF_UI_MODE_NIGHT = "2"` into MMKV on
     * first launch, so by the time anything reads it there is no such thing as "nothing stored"
     * and every fallback in the app is dead code for this key. Making «Как в системе» the default
     * is one character there — "2" -> "0" on the `ensureDefaultValue(AppConfig.PREF_UI_MODE_NIGHT,
     * …)` line — and that file belongs to another wave, so it is reported rather than edited.
     * Note for whoever makes it: `ensureDefaultValue` only writes when the key is ABSENT, so the
     * change reaches new installs only and cannot disturb anyone who already has a value.
     *
     * Mono wins regardless of night mode, which is why it is a separate axis (PREF_COLOR_THEME)
     * and why choosing it leaves PREF_UI_MODE_NIGHT alone — the reorder does not touch it, and
     * «Чёрно-белая» over a followed system night mode is a legal, unchanged combination. Light,
     * dark and system are applied through AppCompatDelegate; the mono overlay is applied in
     * BaseActivity.onCreate. Either path is picked up with recreate().
     */
    private fun currentAppearanceIndex(): Int = when {
        isMonoOn() -> 3
        else -> when (MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, "0")) {
            "2" -> 1 // Тёмная
            "1" -> 2 // Светлая
            // "0" and anything unrecognised: AppCompat is left at FOLLOW_SYSTEM by
            // setNightMode's own `when`, so this is what the screen is actually doing.
            else -> 0 // Как в системе
        }
    }

    /** The label for an appearance index, used by both the row value and the popup. */
    private fun appearanceLabelRes(index: Int): Int = when (index) {
        1 -> R.string.settings_appearance_dark
        2 -> R.string.settings_appearance_light
        3 -> R.string.settings_appearance_mono
        else -> R.string.hub_appearance_system
    }

    private fun pickAppearance() {
        val entries = (0..3).map { getString(appearanceLabelRes(it)) }
        SelectPopup.show(
            anchor = binding.rowAppearance,
            options = entries,
            selectedIndex = currentAppearanceIndex(),
            widthRes = R.dimen.select_popup_w_appearance,
            valueView = binding.valueAppearance,
            caret = binding.caretAppearance,
        ) { which ->
            when (which) {
                1 -> { // Тёмная
                    MmkvManager.encodeSettings(AppConfig.PREF_UI_MODE_NIGHT, "2")
                    MmkvManager.encodeSettings(AppConfig.PREF_COLOR_THEME, BaseActivity.THEME_BLUE)
                    SettingsManager.setNightMode()
                }
                2 -> { // Светлая
                    MmkvManager.encodeSettings(AppConfig.PREF_UI_MODE_NIGHT, "1")
                    MmkvManager.encodeSettings(AppConfig.PREF_COLOR_THEME, BaseActivity.THEME_BLUE)
                    SettingsManager.setNightMode()
                }
                3 -> {
                    // Чёрно-белая: mono overlay, keep the current night mode.
                    MmkvManager.encodeSettings(AppConfig.PREF_COLOR_THEME, BaseActivity.THEME_MONO)
                }
                else -> { // Как в системе
                    MmkvManager.encodeSettings(AppConfig.PREF_UI_MODE_NIGHT, "0")
                    MmkvManager.encodeSettings(AppConfig.PREF_COLOR_THEME, BaseActivity.THEME_BLUE)
                    SettingsManager.setNightMode()
                }
            }
            // The night mode and mono overlay are applied at activity creation, so recreate.
            requireActivity().recreate()
        }
    }

    /**
     * «Язык». The list is `language_select` — «Системный» and «Русский».
     *
     * The prototype's `LANGS` has a third, «English», and it is NOT added here: there is no
     * `values-en/`, so the option would switch a Russian app to a Russian app and call it English.
     * That is the exact defect `docs/agents/state/DECISION-localisation.md` records removing. The
     * row is design-shaped either way; the missing locale is an owner decision, not a layout one.
     */
    private fun pickLanguage() {
        val entries = resources.getStringArray(R.array.language_select).toList()
        val values = resources.getStringArray(R.array.language_select_value)
        val current = MmkvManager.decodeSettingsString(AppConfig.PREF_LANGUAGE, values.firstOrNull() ?: "auto").orEmpty()
        val idx = values.indexOf(current).coerceAtLeast(0)
        SelectPopup.show(
            anchor = binding.rowLanguage,
            options = entries,
            selectedIndex = idx,
            widthRes = R.dimen.select_popup_w_language,
            valueView = binding.valueLanguage,
            caret = binding.caretLanguage,
        ) { which ->
            MmkvManager.encodeSettings(AppConfig.PREF_LANGUAGE, values[which])
            // Locale is applied via BaseActivity.attachBaseContext on recreate.
            requireActivity().recreate()
        }
    }

    private fun toggleStartOnBoot() {
        val enabled = !MmkvManager.decodeStartOnBoot()
        MmkvManager.encodeStartOnBoot(enabled)
        binding.switchBoot.isChecked = enabled
    }

    /**
     * Interval options (minutes) offered by the subscription auto-update picker, in the design's
     * order: `AUTOUP = ['1 час', '3 часа', '6 часов', '12 часов', 'Раз в сутки', 'Выключено']`.
     *
     * Two changes from before, both the prototype's: «3 часа» is offered, and «Выключено» moved
     * from the head of the list to its foot — a list of intervals that opens with "no interval"
     * reads as if off were the recommendation. Off itself stays, because it is a capability and not
     * a wording (П-27): without it a подписка cannot be taken off the schedule at all.
     */
    private val subAutoUpdateValues = longArrayOf(60L, 180L, 360L, 720L, 1440L, 0L)

    /** Short Russian label for a subscription auto-update interval in minutes (0 == off). */
    private fun subAutoUpdateLabel(minutes: Long): String = when (minutes) {
        0L -> getString(R.string.hub_sub_auto_update_off)
        60L -> getString(R.string.settings_sub_auto_update_1h)
        180L -> getString(R.string.hub_sub_auto_update_3h)
        360L -> getString(R.string.settings_sub_auto_update_6h)
        720L -> getString(R.string.settings_sub_auto_update_12h)
        1440L -> getString(R.string.hub_sub_auto_update_daily)
        else -> getString(R.string.settings_sub_auto_update_minutes, minutes)
    }

    /**
     * Row value: the interval of any auto-updating подписка, «Выкл» when none of them updates, or
     * «Нет подписок» when there is nothing to update at all.
     *
     * The last case matters: with no подписка stored the row used to read «Выкл», which invites a
     * tap that can only answer with a toast. It now states the reason, in the same words
     * «Настройки подписок» uses for the same state, so the two screens agree.
     */
    private fun currentSubAutoUpdateLabel(): String {
        // SubscriptionUpdater.currentSchedule, а не первая подписка в списке: первой там лежит
        // служебная корзина серверов без подписки. Она есть на каждой установке, поэтому «Нет
        // подписок» не появлялось никогда, а пересозданная с умолчанием она отвечала за всех «1 час».
        val (enabled, minutes) = SubscriptionUpdater.currentSchedule()
            ?: return getString(R.string.settings_sub_auto_update_none)
        return subAutoUpdateLabel(if (enabled) minutes else 0L)
    }

    /**
     * Global subscription auto-update picker. There is no dedicated global pref key, so the
     * choice is applied across every stored subscription: `SubscriptionItem.autoUpdate` and
     * `SubscriptionItem.updateInterval` (in minutes) are written for each one, then the
     * WorkManager scheduler is re-synced via [SubscriptionUpdater.sync] so the new interval
     * takes effect immediately. Minutes are stored so the home meta-bar can read the value.
     */
    private fun pickSubAutoUpdate() {
        // With no subscriptions the interval has nothing to apply to, so the picker would
        // silently no-op. Tell the user to add one first instead.
        val schedule = SubscriptionUpdater.currentSchedule()
        if (schedule == null) {
            toast(R.string.settings_sub_auto_update_empty)
            return
        }
        val entries = subAutoUpdateValues.map { subAutoUpdateLabel(it) }
        val currentMinutes = if (schedule.first) schedule.second else 0L
        // An interval stored by «Настройки подписок» that this list does not offer (2 ч, say) marks
        // nothing rather than snapping the checkmark onto a value the user never chose.
        val idx = subAutoUpdateValues.indexOf(currentMinutes)
        SelectPopup.show(
            anchor = binding.rowSubAutoUpdate,
            options = entries,
            selectedIndex = idx,
            widthRes = R.dimen.select_popup_w_interval,
            valueView = binding.valueSubAutoUpdate,
            caret = binding.caretSubAutoUpdate,
        ) { which ->
            val minutes = subAutoUpdateValues[which]
            MmkvManager.decodeSubscriptions().forEach { cache ->
                val item = cache.subscription
                if (minutes <= 0L) {
                    item.autoUpdate = false
                } else {
                    item.autoUpdate = true
                    item.updateInterval = minutes
                }
                MmkvManager.encodeSubscription(cache.guid, item)
            }
            // Recalculate the next run time from the freshly persisted state.
            SubscriptionUpdater.sync(forceReschedule = true)
            bindSettingsState()
        }
    }
}
