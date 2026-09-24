package com.v2ray.ang.ui

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.auth.BackendConfig
import com.v2ray.ang.databinding.ActivityProviderSettingsBinding
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.ui.component.SubPage
import com.v2ray.ang.ui.component.ToolbarBinder
import com.v2ray.ang.ui.component.onSingleClick
import com.v2ray.ang.ui.component.restoreChecked
import com.v2ray.ang.util.HttpUtil

/**
 * «Настройки подписок» — the settings that belong to the subscription feeds, not to one подписка.
 *
 * The word is the owner's: what this app used to call a «провайдер» is a **подписка** on every
 * surface, so the screen, its title and its copy say подписка.
 *
 * Groups four cards:
 *  1. ОБНОВЛЕНИЕ    — auto-update toggle + update notification toggle.
 *  2. ПРИ ЗАПУСКЕ    — update-on-launch / ping-on-launch / ping-on-update toggles.
 *  3. СЕТЬ           — HWID toggle + the User-Agent this app sends (a read-out, not a control).
 *
 * «СПИСОК СЕРВЕРОВ» БОЛЬШЕ НЕТ: «убери кнопку сортировка серверов и текст выше "список серверов"
 * вообще убери функцию». Секция состояла из одной строки, поэтому вместе со строкой ушёл и её
 * заголовок. Механика сортировки не удалена — [SettingsManager.applyServerSortOrder],
 * `AppConfig.SERVER_SORT_*` и строки `ps_sort_*` на месте и в сборке; у них просто больше нет
 * читателя на экране.
 *
 * **EVERY ROW HERE IS ONE HEIGHT, 60dp, and neither half of that was free.** Owner report 0.4.9,
 * «в настройках подписок такая же тема с высотой, надо фиксить»: four rows carried an unbounded
 * second line (two of them ran to three lines) and every switch carried Material's 48dp
 * `minTouchTargetSize` floor, 8dp above the 40dp tile — five different row heights from two
 * unrelated causes. Both are fixed in `activity_provider_settings.xml`, which explains them; the
 * dropped subtitles are still in `values/strings_provider.xml`, unread and commented as such.
 *
 * Wiring notes:
 *  - Auto-update is applied to the app's existing per-subscription auto-update mechanism
 *    ([com.v2ray.ang.dto.entities.SubscriptionItem.autoUpdate] /
 *    [com.v2ray.ang.dto.entities.SubscriptionItem.updateInterval]) across every stored
 *    subscription, then rescheduled via [SubscriptionUpdater.sync]. The INTERVAL itself is chosen
 *    on the Настройки tab and not here any more — the block at the end of the ОБНОВЛЕНИЕ section
 *    below records why that is a duplicate removed and not a capability lost.
 *  - Every other row writes an [AppConfig] preference that the rest of the app reads: HWID is
 *    consumed by the subscription fetch, the notification and the launch/update actions by
 *    [SubscriptionUpdater], the sort order by [SettingsManager.applyServerSortOrder]. Defaults
 *    live in [SettingsManager], not here, so the switch state and the behaviour can never drift
 *    apart. The User-Agent row is the exception and writes nothing: it REPORTS what
 *    [currentUserAgent] resolves, and the key behind it is still honoured by the fetch.
 */
class ProviderSettingsActivity : BaseActivity() {

    private val binding by lazy { ActivityProviderSettingsBinding.inflate(layoutInflater) }

    companion object {
        /**
         * Fallback shown when nothing has an interval yet - the same default a fresh
         * [com.v2ray.ang.dto.entities.SubscriptionItem] carries.
         *
         * There is no screen-local key any more. This screen used to keep the chosen interval in a
         * private `pref_provider_update_interval`, which nothing else in the app read: the value
         * that actually schedules work is `SubscriptionItem.updateInterval`, and a second copy of it
         * could disagree with the scheduler the moment the interval was changed anywhere else. The
         * row reads the real one now.
         */
        private const val DEFAULT_INTERVAL_MINUTES = 60L

        /** 0.38 - the disabled alpha of the row grammar. */
        private const val ALPHA_DISABLED = 0.38f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        SubPage.installTransitions(this)
        super.onCreate(savedInstanceState)
        // Handoff README §7: the sub-page lekalo draws the title at 24sp/700 UNDER a 44dp
        // back control, which activity_base's 16sp MaterialToolbar cannot do, so the header
        // is @layout/view_sub_header inside this screen's own layout. Nothing else moves —
        // the screen never used the base layout's progress bar.
        setContentView(binding.root)
        ToolbarBinder.bind(
            root = binding.toolbar.root,
            title = getString(R.string.ps_title),
            activity = this,
        )
        ToolbarBinder.attachTo(binding.toolbar.root, binding.mainContent)

        // ОБНОВЛЕНИЕ
        binding.rowAutoUpdate.setOnClickListener { toggleAutoUpdate() }
        binding.rowNotify.setOnClickListener {
            toggleBool(AppConfig.PREF_SUB_NOTIFY_ON_UPDATE, binding.switchNotify, SettingsManager.isNotifyOnSubscriptionUpdate())
        }

        // ПРИ ЗАПУСКЕ
        binding.rowUpdateOnLaunch.setOnClickListener {
            toggleBool(AppConfig.PREF_SUB_UPDATE_ON_LAUNCH, binding.switchUpdateOnLaunch, SettingsManager.isUpdateSubscriptionOnLaunch())
        }
        binding.rowPingOnLaunch.setOnClickListener {
            toggleBool(AppConfig.PREF_PING_ON_LAUNCH, binding.switchPingOnLaunch, SettingsManager.isPingOnLaunch())
        }
        binding.rowPingOnUpdate.setOnClickListener {
            toggleBool(AppConfig.PREF_PING_ON_UPDATE, binding.switchPingOnUpdate, SettingsManager.isPingOnSubscriptionUpdate())
        }

        // СЕТЬ
        binding.rowSendHwid.setOnClickListener { toggleSendHwid() }
        // @id/row_user_agent IS DELIBERATELY NOT WIRED (owner 0.4.9: «убери возможность настройки
        // user agent, чтобы жил было некликабельно»). It is Row.Fact — it reports the User-Agent
        // subscription fetches actually send and leads nowhere. The layout clears its
        // clickable/focusable/press so it is not a TalkBack or D-pad stop either. [editUserAgent]
        // below is kept, unreferenced, on purpose; its own comment says why.

        // СОХРАНЁННЫЙ ПОРЯДОК ПРИВОДИТСЯ К ИСХОДНОМУ, И ЭТО НЕ УБОРКА, А ИСПРАВЛЕНИЕ.
        // Снять управление и оставить значение лежать значило бы запереть всех, у кого выбрано
        // «По пингу» или «По имени», в этом порядке навсегда: экрана, чтобы вернуть, больше нет.
        // Сбрасывается ровно один раз — при следующем заходе значение уже исходное и ветка не
        // выполняется, так что это не переписывание чужого выбора на каждом открытии.
        resetServerSortOrder()

        bindState()
    }

    /**
     * A подписка can be added or deleted while this screen sits in the back stack (the list is two
     * taps away), and that changes whether the update rows can do anything at all. Re-read on the
     * way back rather than showing what was true when the screen opened.
     */
    override fun onResume() {
        super.onResume()
        bindState()
    }

    /** Reflect all persisted values/toggle states into the rows. */
    private fun bindState() {
        // With no подписка stored there is nothing for a schedule to apply to: toggling the switch
        // wrote to an empty list and looked like it had worked. The row says so by going inert.
        // A подписка here is one there is something to fetch from — the store's own bucket for
        // servers without one is not (SubscriptionUpdater.isRealSubscription), and counting it kept
        // this row live on every install.
        val hasSubscriptions = SubscriptionUpdater.currentSchedule() != null
        setRowEnabled(binding.rowAutoUpdate, hasSubscriptions)
        binding.switchAutoUpdate.restoreChecked(hasSubscriptions && isAutoUpdateOn())
        binding.switchNotify.restoreChecked(SettingsManager.isNotifyOnSubscriptionUpdate())

        binding.switchUpdateOnLaunch.restoreChecked(SettingsManager.isUpdateSubscriptionOnLaunch())
        binding.switchPingOnLaunch.restoreChecked(SettingsManager.isPingOnLaunch())
        binding.switchPingOnUpdate.restoreChecked(SettingsManager.isPingOnSubscriptionUpdate())

        binding.switchSendHwid.restoreChecked(SettingsManager.isSendHwid())
        binding.valueUserAgent.text = currentUserAgent()
    }

    // ---------------- ОБНОВЛЕНИЕ ----------------

    /** True if any stored subscription currently auto-updates. */
    private fun isAutoUpdateOn(): Boolean = SubscriptionUpdater.currentSchedule()?.first == true

    /**
     * The interval that is actually scheduled: the one on the first auto-updating подписка, or the
     * one the first подписка carries, or the shipped default when there is nothing to read — the
     * same reading [SubscriptionUpdater.currentSchedule] gives the Настройки tab and new подписки.
     */
    private fun storedIntervalMinutes(): Long =
        SubscriptionUpdater.currentSchedule()?.second?.takeIf { it > 0L } ?: DEFAULT_INTERVAL_MINUTES

    /**
     * Enable/disable auto-update across every stored subscription, then reschedule the
     * WorkManager task so the change takes effect immediately.
     */
    private fun toggleAutoUpdate() {
        val enable = !isAutoUpdateOn()
        val interval = storedIntervalMinutes()
        MmkvManager.decodeSubscriptions().forEach { cache ->
            val item = cache.subscription
            item.autoUpdate = enable
            if (enable) item.updateInterval = interval
            MmkvManager.encodeSubscription(cache.guid, item)
        }
        SubscriptionUpdater.sync(forceReschedule = true)
        binding.switchAutoUpdate.isChecked = enable
    }

    // «ИНТЕРВАЛ ОБНОВЛЕНИЯ» IS NOT ON THIS SCREEN ANY MORE (owner 0.4.9: «он уже и так вынесен в
    // общий список настроек»). Checked before removing rather than after: this screen's picker and
    // the Настройки tab's «Автообновление подписки» wrote THE SAME STORAGE — SubscriptionItem
    // .updateInterval and .autoUpdate on every stored подписка, followed by the same
    // SubscriptionUpdater.sync(forceReschedule = true). There is no per-screen key (see the note on
    // [DEFAULT_INTERVAL_MINUTES]), so nothing here was the sole writer of anything. The surviving
    // control is [SettingsTabFragment.pickSubAutoUpdate]; it also offers «Выключено», which this
    // list could not. The only value that stops being offerable is «2 ч» — the tab's list is
    // 1/3/6/12/24 ч and Выкл — and an interval of 120 already stored survives and reads as «120 мин»
    // there, because both screens fall back to the same settings_sub_auto_update_minutes.
    // [storedIntervalMinutes] stays: [toggleAutoUpdate] needs it to turn auto-update back on at the
    // interval that was last in force rather than at a number invented here.

    // ---------------- СЕТЬ ----------------

    /** Toggle whether the device HWID is sent when refreshing a subscription. Real behavior. */
    private fun toggleSendHwid() {
        val enabled = !SettingsManager.isSendHwid()
        MmkvManager.encodeSettings(AppConfig.PREF_SEND_HWID, enabled)
        binding.switchSendHwid.isChecked = enabled
    }

    /**
     * The User-Agent subscription fetches will actually send: the global override when set,
     * otherwise the operator default the build ships with — put through the same resolution the
     * fetch uses, so a value the fetch would replace (one an older build stored before this screen
     * validated its input) is not advertised here as if it were sent.
     */
    private fun currentUserAgent(): String = HttpUtil.resolveSubscriptionUserAgent(
        SettingsManager.getSubscriptionUserAgent() ?: BackendConfig.subscriptionUserAgent
    )

    /**
     * Edits the global User-Agent override.
     *
     * The value is validated HERE, on entry, and a rejected one is never stored: a User-Agent that
     * cannot travel in an HTTP header is silently replaced at fetch time, so storing it would put
     * a string in this row that the app will never send — and the row exists to say what is sent.
     *
     * **NOTHING CALLS THIS SINCE 0.4.9, AND IT IS KEPT ON PURPOSE** — «убери возможность настройки
     * user agent, чтобы жил было некликабельно». What the owner asked for is that the row stop
     * being a control, and that is done where a row's behaviour belongs: @id/row_user_agent no
     * longer takes taps. Deleting the editor is a different act, and it is the one thing that could
     * not be undone in a line — [AppConfig.PREF_SUB_USER_AGENT] is a LIVE key that
     * [currentUserAgent] reads and every subscription fetch honours, so a value stored by an older
     * build still travels, and the code that can change or clear it is this. Re-wiring it is one
     * `onSingleClick`; rewriting the validation from the header rules is not.
     *
     * It also keeps `ps_user_agent_hint` and `ps_user_agent_error` attached to the thing they
     * describe, which is the same reason `PerAppProxyActivity.Mode.hintRes` was retained.
     */
    @Suppress("unused")
    private fun editUserAgent() {
        val field = TextInputLayout(this).apply {
            hint = getString(R.string.ps_user_agent_hint)
            isErrorEnabled = true
        }
        val input = TextInputEditText(field.context).apply {
            // Only the override is prefilled: confirming an untouched dialog must not freeze the
            // operator default into a permanent override that a later build could no longer change.
            // The row itself already shows the User-Agent that is actually sent.
            setText(SettingsManager.getSubscriptionUserAgent().orEmpty())
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
        }
        field.addView(input)
        val pad = resources.getDimensionPixelSize(R.dimen.space_16)
        val container = FrameLayout(this).apply {
            setPadding(pad, pad, pad, 0)
            addView(field)
        }

        // Validate on blur, and again on confirm; clear the error as soon as the user edits.
        input.doAfterTextChanged { field.error = null }
        input.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) showUserAgentError(field, input) }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.ps_user_agent)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            // Own the click so an invalid value keeps the dialog open with the error visible,
            // instead of dismissing and quietly discarding what was typed.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (showUserAgentError(field, input)) {
                    input.requestFocus()
                    return@setOnClickListener
                }
                // An empty field clears the override rather than storing a blank User-Agent.
                MmkvManager.encodeSettings(AppConfig.PREF_SUB_USER_AGENT, input.text.toString().trim())
                bindState()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /** Shows/clears the inline error. @return true when the value is rejected. */
    private fun showUserAgentError(field: TextInputLayout, input: TextInputEditText): Boolean {
        val value = input.text.toString().trim()
        val invalid = value.isNotEmpty() && !HttpUtil.isHeaderSafe(value)
        field.error = if (invalid) getString(R.string.ps_user_agent_error) else null
        return invalid
    }

    // ---------------- порядок серверов ----------------

    /**
     * Возвращает список серверов к исходному порядку, если он был изменён, и делает это один раз.
     *
     * Управление сортировкой снято с экрана по указанию владельца. Само хранимое значение при этом
     * никуда не девается, и вот это как раз и было бы дефектом: у того, кто когда-то выбрал «По
     * пингу», список остался бы отсортированным по пингу навсегда, без единого способа вернуть —
     * тихая необратимая настройка хуже отсутствующей.
     *
     * Ветка отрабатывает только когда есть что менять, поэтому со второго захода она не делает
     * ничего и не спорит с пользователем на каждом открытии экрана.
     */
    private fun resetServerSortOrder() {
        if (SettingsManager.getServerSortOrder() == AppConfig.SERVER_SORT_DEFAULT) return
        MmkvManager.encodeSettings(AppConfig.PREF_SERVER_SORT_ORDER, AppConfig.SERVER_SORT_DEFAULT)
        SettingsManager.applyServerSortOrder()
        SettingsChangeManager.makeSetupGroupTab()
    }

    // ---------------- helpers ----------------

    /**
     * A row that cannot do anything is not presented as if it could: it stops taking taps and
     * drops to the disabled alpha of the row grammar, and its value states the reason.
     */
    private fun setRowEnabled(row: View, enabled: Boolean) {
        row.isEnabled = enabled
        row.isClickable = enabled
        row.isFocusable = enabled
        row.alpha = if (enabled) 1f else ALPHA_DISABLED
    }

    /** Flip a boolean pref and reflect it on its switch. [current] carries the default. */
    private fun toggleBool(
        key: String,
        switch: com.google.android.material.materialswitch.MaterialSwitch,
        current: Boolean
    ) {
        val enabled = !current
        MmkvManager.encodeSettings(key, enabled)
        switch.isChecked = enabled
    }
}
