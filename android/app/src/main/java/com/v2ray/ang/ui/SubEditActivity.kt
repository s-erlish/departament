package com.v2ray.ang.ui

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivitySubEditBinding
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.ui.component.RowBinder
import com.v2ray.ang.ui.component.SubPage
import com.v2ray.ang.ui.component.ToolbarBinder
import com.v2ray.ang.ui.component.onSingleClick
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.SubscriptionGuard
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A-14 `servers/provider/{id}` - the subscription form. H3 header, R4 rhythm.
 *
 * Save and delete were toolbar menu items on the ActionBar A-38 removes, so they are the screen's
 * Primary.Tall CTA and a `Row.Destructive`. The three switches whose labels were separate TextViews
 * - so the label was inert and only the 32dp thumb was tappable - are `Row.Toggle`s where the whole
 * 56dp row toggles.
 *
 * Every validation failure moves from a toast into the field's own error slot: an empty name, a
 * non-departament link, a malformed URL, an insecure scheme, a too-small interval, and the
 * non-ASCII User-Agent (whose message was a Kotlin literal with a TODO on it and is now a string
 * resource). A toast that names a field the user cannot see is not an error message.
 */
class SubEditActivity : BaseActivity() {

    private val binding by lazy { ActivitySubEditBinding.inflate(layoutInflater) }
    private val editSubId by lazy { intent.getStringExtra("subId").orEmpty() }

    private var enabled = true
    private var autoUpdate = false
    private var allowInsecureUrl = false
    private var locked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        SubPage.installTransitions(this)
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        SettingsChangeManager.makeSetupGroupTab()
        val subItem = MmkvManager.decodeSubscription(editSubId)

        ToolbarBinder.bind(
            root = binding.toolbar.root,
            title = getString(
                if (subItem == null) R.string.subs_ed_title_new else R.string.subs_ed_title_edit
            ),
            activity = this,
        )
        ToolbarBinder.attachTo(binding.toolbar.root, binding.mainContent)

        if (subItem != null) bindSubscription(subItem) else clearForm()

        bindToggleRows()
        setupProfilePickers()

        binding.btnSave.onSingleClick { saveSubscription() }

        RowBinder.bind(
            root = binding.rowDelete.root,
            title = getString(R.string.subs_ed_delete),
            tone = RowBinder.RowTone.DESTRUCTIVE,
            trailing = RowBinder.Trailing.None,
            onClick = { confirmDelete() },
        )
        binding.rowDelete.root.isVisible = editSubId.isNotEmpty()
    }

    // ---------------------------------------------------------------- form

    private fun bindSubscription(subItem: SubscriptionItem) {
        locked = subItem.locked
        binding.etRemarks.text = Utils.getEditable(subItem.remarks)

        if (locked) {
            // Managed subscription: the operator URL carries the account token and is never shown.
            binding.etUrl.text = Utils.getEditable(MASKED_URL)
            binding.etUrl.isEnabled = false
            binding.tilUrl.helperText = getString(R.string.subs_ed_url_locked)
        } else {
            binding.etUrl.text = Utils.getEditable(subItem.url)
        }

        binding.etUserAgent.text = Utils.getEditable(subItem.userAgent)
        binding.etFilter.text = Utils.getEditable(subItem.filter)
        binding.etUpdateInterval.text = Utils.getEditable(subItem.updateInterval.toString())
        binding.etPreProfile.text = Utils.getEditable(subItem.prevProfile)
        binding.etNextProfile.text = Utils.getEditable(subItem.nextProfile)

        enabled = subItem.enabled
        autoUpdate = subItem.autoUpdate
        // The fetch does NOT honour this switch on an operator-managed subscription
        // (AngConfigManager.resolveSecureSubUrl refuses cleartext and retries over https), so on a
        // locked subscription it reads off and disabled rather than looking live.
        allowInsecureUrl = subItem.allowInsecureUrl && !locked
    }

    private fun clearForm() {
        binding.etRemarks.text = null
        binding.etUrl.text = null
        binding.etFilter.text = null
        binding.etUpdateInterval.text = null
        binding.etPreProfile.text = null
        binding.etNextProfile.text = null
        enabled = true
        autoUpdate = false
        allowInsecureUrl = false
        locked = false
    }

    private fun bindToggleRows() {
        RowBinder.bind(
            root = binding.rowEnable.root,
            title = getString(R.string.subs_ed_enabled),
            subtitle = getString(R.string.subs_ed_enabled_hint),
            trailing = RowBinder.Trailing.Toggle(enabled) { enabled = it },
        )
        RowBinder.bind(
            root = binding.rowAutoUpdate.root,
            title = getString(R.string.subs_ed_auto_update),
            subtitle = getString(R.string.subs_ed_auto_update_hint),
            trailing = RowBinder.Trailing.Toggle(autoUpdate) {
                autoUpdate = it
                binding.tilUpdateInterval.isEnabled = it
            },
        )
        binding.tilUpdateInterval.isEnabled = autoUpdate

        RowBinder.bind(
            root = binding.rowAllowInsecure.root,
            title = getString(R.string.subs_ed_allow_insecure),
            subtitle = getString(
                if (locked) R.string.subs_ed_allow_insecure_locked
                else R.string.subs_ed_allow_insecure_hint
            ),
            trailing = RowBinder.Trailing.Toggle(allowInsecureUrl) { allowInsecureUrl = it },
            enabled = !locked,
        )
    }

    private fun setupProfilePickers() {
        val suggestions = SettingsManager.getProfileRemarks(
            excludeConfigTypes = setOf(
                EConfigType.CUSTOM,
                EConfigType.POLICYGROUP,
                EConfigType.PROXYCHAIN,
            )
        )
        bindProfilePicker(binding.tilPreProfile, binding.etPreProfile, suggestions)
        bindProfilePicker(binding.tilNextProfile, binding.etNextProfile, suggestions)
    }

    /**
     * The end icon lists the profiles we know about and writes the chosen one into the field. When
     * there are none it says so rather than opening an empty sheet - the old dropdown button simply
     * did nothing in that case, which reads as a broken control.
     */
    private fun bindProfilePicker(
        layout: TextInputLayout,
        field: TextInputEditText,
        suggestions: List<String>,
    ) {
        layout.setEndIconOnClickListener {
            if (suggestions.isEmpty()) {
                layout.helperText = getString(R.string.subs_ed_profile_none)
                return@setEndIconOnClickListener
            }
            val current = field.text.toString()
            EditorActionsSheet(this, getString(R.string.subs_ed_profile_pick)).apply {
                suggestions.forEach { remark ->
                    action(label = remark, selected = remark == current) {
                        field.text = Utils.getEditable(remark)
                    }
                }
            }.show()
        }
    }

    // -------------------------------------------------------------- save

    private fun saveSubscription() {
        clearErrors()

        val subItem = MmkvManager.decodeSubscription(editSubId) ?: SubscriptionItem()
        val remarks = binding.etRemarks.text.toString().trim()
        if (remarks.isEmpty()) {
            fail(binding.tilRemarks, binding.etRemarks, R.string.subs_ed_name_required)
            return
        }
        subItem.remarks = remarks

        if (!subItem.locked) {
            subItem.url = binding.etUrl.text.toString().trim()
        }

        // Validated on entry: a User-Agent that cannot travel in an HTTP header is silently replaced
        // at fetch time, so storing it would leave this field showing a string the app never sends.
        val userAgent = binding.etUserAgent.text.toString().trim()
        if (userAgent.isNotEmpty() && !HttpUtil.isHeaderSafe(userAgent)) {
            fail(binding.tilUserAgent, binding.etUserAgent, R.string.subs_ed_user_agent_error)
            return
        }
        subItem.userAgent = userAgent
        subItem.filter = binding.etFilter.text.toString()
        subItem.enabled = enabled
        subItem.autoUpdate = autoUpdate

        val intervalMinutes = binding.etUpdateInterval.text.toString().trim().toLongOrNull()
        if (autoUpdate) {
            when {
                intervalMinutes == null -> subItem.updateInterval = SubscriptionItem().updateInterval
                intervalMinutes < AppConfig.SUBSCRIPTION_MIN_INTERVAL_MINUTES -> {
                    fail(
                        binding.tilUpdateInterval,
                        binding.etUpdateInterval,
                        R.string.subs_ed_interval_too_small,
                    )
                    return
                }

                else -> subItem.updateInterval = intervalMinutes
            }
        } else if (intervalMinutes != null &&
            intervalMinutes >= AppConfig.SUBSCRIPTION_MIN_INTERVAL_MINUTES
        ) {
            subItem.updateInterval = intervalMinutes
        }

        subItem.prevProfile = binding.etPreProfile.text.toString()
        subItem.nextProfile = binding.etNextProfile.text.toString()
        // Locked: the switch is shown off and disabled because it governs nothing here, so reading
        // it back would overwrite the stored preference with that display state.
        if (!subItem.locked) {
            subItem.allowInsecureUrl = allowInsecureUrl
        }

        if (subItem.url.isNotEmpty() && !validateUrl(subItem)) return

        MmkvManager.encodeSubscription(editSubId, subItem)
        SubscriptionUpdater.syncOne(subId = editSubId)
        toastSuccess(R.string.editor_saved)
        SubPage.close(this)
    }

    private fun validateUrl(subItem: SubscriptionItem): Boolean {
        if (!SubscriptionGuard.isAllowed(subItem.url)) {
            fail(binding.tilUrl, binding.etUrl, R.string.subs_ed_url_foreign)
            return false
        }
        if (!Utils.isValidUrl(subItem.url)) {
            fail(binding.tilUrl, binding.etUrl, R.string.subs_ed_url_invalid)
            return false
        }
        if (!Utils.isValidSubUrl(subItem.url) && !subItem.allowInsecureUrl) {
            fail(binding.tilUrl, binding.etUrl, R.string.subs_ed_url_insecure)
            return false
        }
        return true
    }

    private fun clearErrors() {
        binding.tilRemarks.error = null
        binding.tilUrl.error = null
        binding.tilUserAgent.error = null
        binding.tilUpdateInterval.error = null
    }

    private fun fail(layout: TextInputLayout, field: TextInputEditText, messageRes: Int) {
        layout.error = getString(messageRes)
        field.requestFocus()
    }

    /**
     * Deleting from the form, always confirmed, with the подписка named in the title.
     *
     * It used to ask only when `PREF_CONFIRM_REMOVE` was set - a key nothing in this app writes, so
     * the branch was dead and «Удалить подписку» destroyed the подписка and its servers on the first
     * tap, with no way back. A destructive action names what it will destroy (00-rules.md 7.5).
     */
    private fun confirmDelete() {
        if (editSubId.isEmpty()) return
        // The STORED name, not the one being typed: what gets destroyed is the подписка as it is
        // saved, and an unsaved rename in the field would name something that does not exist.
        val stored = MmkvManager.decodeSubscription(editSubId)
        val name = stored
            ?.let { SubSettingRecyclerAdapter.displayName(this, it) }
            ?.trim()
            .orEmpty()
            .ifEmpty { getString(R.string.subs_ed_title_edit) }
        AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage(R.string.subs_delete_confirm)
            .setPositiveButton(R.string.editor_delete) { _, _ -> removeSubscription() }
            .setNegativeButton(R.string.editor_cancel, null)
            .show()
    }

    private fun removeSubscription() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                SettingsManager.removeSubscriptionWithDefault(editSubId)
            }
            toastSuccess(R.string.home_sub_deleted)
            SubPage.close(this@SubEditActivity)
        }
    }

    private companion object {
        const val MASKED_URL = "••••••••"
    }
}
