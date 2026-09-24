package com.v2ray.ang.ui

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig.BUILTIN_OUTBOUND_TAGS
import com.v2ray.ang.AppConfig.TAG_PROXY
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityRoutingEditBinding
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.component.RowBinder
import com.v2ray.ang.ui.component.SubPage
import com.v2ray.ang.ui.component.ToolbarBinder
import com.v2ray.ang.ui.component.onSingleClick
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A-21 `settings/routing/rule/{id}` - «Правило». H3 header, R4 rhythm.
 *
 * Three things this form did not have and now does:
 *
 * - **«Сохранить» and «Удалить» were toolbar menu items.** A-38 removes the ActionBar they lived on,
 *   which would have made saving a rule impossible. Save is the screen's one Primary.Tall CTA at the
 *   bottom of the form and delete is a `Row.Destructive` under it, per §7.5.
 * - **«Правило включено» had no home.** `RulesetItem.enabled` was editable only from a switch on the
 *   list row, which §4.5 does not allow beside a chevron. It is a `Row.Toggle` here.
 * - **Validation had no slot.** The empty-name case was a toast that named the wrong thing
 *   («remarks»); it is now an error under the field that says what to do, and the field takes focus.
 *
 * The two floating `ImageButton`s over the ends of two fields become the fields' own end icons, and
 * both fields stay free-text: a user may route to a server whose remark we cannot enumerate, so the
 * picker FILLS the field rather than replacing typing.
 */
class RoutingEditActivity : BaseActivity() {

    private val binding by lazy { ActivityRoutingEditBinding.inflate(layoutInflater) }
    private val position by lazy { intent.getIntExtra("position", -1) }

    private var ruleEnabled = true
    private var ruleLocked = false

    private val processPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val selected = AppPickerActivity.getSelectedPackages(result.data)
                binding.etProcess.text = Utils.getEditable(selected.joinToString(","))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        SubPage.installTransitions(this)
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        val ruleset = SettingsManager.getRoutingRuleset(position)

        ToolbarBinder.bind(
            root = binding.toolbar.root,
            title = getString(
                if (ruleset == null) R.string.routing_ed_title_new else R.string.routing_ed_title_edit
            ),
            activity = this,
        )
        ToolbarBinder.attachTo(binding.toolbar.root, binding.mainContent)

        if (ruleset != null) bindRuleset(ruleset) else clearForm()

        bindToggleRows()
        setupOutboundPicker()
        setupProcessPicker()

        binding.btnSave.onSingleClick { saveRule() }

        RowBinder.bind(
            root = binding.rowDelete.root,
            title = getString(R.string.routing_ed_delete),
            tone = RowBinder.RowTone.DESTRUCTIVE,
            trailing = RowBinder.Trailing.None,
            onClick = { confirmDelete() },
        )
        // A rule that has not been saved yet has nothing to delete.
        binding.rowDelete.root.isVisible = position >= 0
    }

    // ---------------------------------------------------------------- form

    private fun bindRuleset(ruleset: RulesetItem) {
        binding.etRemarks.text = Utils.getEditable(ruleset.remarks)
        binding.etDomain.text = Utils.getEditable(ruleset.domain?.joinToString(","))
        binding.etIp.text = Utils.getEditable(ruleset.ip?.joinToString(","))
        binding.etProcess.text = Utils.getEditable(ruleset.process?.joinToString(","))
        binding.etPort.text = Utils.getEditable(ruleset.port)
        binding.etProtocol.text = Utils.getEditable(ruleset.protocol?.joinToString(","))
        binding.etNetwork.text = Utils.getEditable(ruleset.network)
        binding.etOutboundTag.text = Utils.getEditable(ruleset.outboundTag)
        ruleEnabled = ruleset.enabled
        ruleLocked = ruleset.locked == true
    }

    private fun clearForm() {
        binding.etRemarks.text = null
        binding.etOutboundTag.text = Utils.getEditable(BUILTIN_OUTBOUND_TAGS.first())
        ruleEnabled = true
        ruleLocked = false
    }

    private fun bindToggleRows() {
        RowBinder.bind(
            root = binding.rowEnabled.root,
            title = getString(R.string.routing_ed_enabled),
            subtitle = getString(R.string.routing_ed_enabled_hint),
            trailing = RowBinder.Trailing.Toggle(ruleEnabled) { ruleEnabled = it },
        )
        RowBinder.bind(
            root = binding.rowLocked.root,
            title = getString(R.string.routing_ed_locked),
            subtitle = getString(R.string.routing_ed_locked_hint),
            trailing = RowBinder.Trailing.Toggle(ruleLocked) { ruleLocked = it },
        )
    }

    /**
     * The end icon offers the outbound tags we know about - the three built-ins plus every stored
     * profile remark - and writes the chosen one into the field. Typing is untouched.
     */
    private fun setupOutboundPicker() {
        binding.tilOutboundTag.setEndIconOnClickListener {
            val suggestions = (BUILTIN_OUTBOUND_TAGS.toList() + SettingsManager.getProfileRemarks())
                .distinct()
            val current = binding.etOutboundTag.text.toString()
            EditorActionsSheet(this, getString(R.string.routing_ed_outbound)).apply {
                suggestions.forEach { tag ->
                    action(label = tag, selected = tag == current) {
                        binding.etOutboundTag.text = Utils.getEditable(tag)
                    }
                }
            }.show()
        }
    }

    private fun setupProcessPicker() {
        val canUse = SettingsManager.canUseProcessRouting()
        binding.etProcess.isEnabled = canUse
        binding.tilProcess.isEnabled = canUse
        if (!canUse) {
            // A disabled control states WHY it is disabled, in the slot the form already reserves.
            binding.tilProcess.helperText = getString(R.string.routing_ed_process_unavailable)
            return
        }
        binding.tilProcess.setEndIconOnClickListener {
            SubPage.open(
                this,
                processPickerLauncher,
                AppPickerActivity.createIntent(
                    context = this,
                    selectedPackages = selectedProcessPackages(),
                    title = getString(R.string.routing_ed_process),
                ),
            )
        }
    }

    private fun selectedProcessPackages(): List<String> = binding.etProcess.text
        .toString()
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    // -------------------------------------------------------------- save

    private fun saveRule() {
        val remarks = binding.etRemarks.text.toString().trim()
        if (remarks.isEmpty()) {
            binding.tilRemarks.error = getString(R.string.routing_ed_name_required)
            binding.etRemarks.requestFocus()
            return
        }
        binding.tilRemarks.error = null

        val ruleset = (SettingsManager.getRoutingRuleset(position) ?: RulesetItem()).apply {
            this.remarks = remarks
            enabled = ruleEnabled
            locked = ruleLocked
            domain = binding.etDomain.text.splitList()
            ip = binding.etIp.text.splitList()
            process = binding.etProcess.text.splitList()
            protocol = binding.etProtocol.text.splitList()
            port = binding.etPort.text.toString().nullIfBlank()
            network = binding.etNetwork.text.toString().nullIfBlank()
            outboundTag = binding.etOutboundTag.text.toString().trim().ifEmpty { TAG_PROXY }
        }

        SettingsManager.saveRoutingRuleset(position, ruleset)
        toastSuccess(R.string.editor_saved)
        SubPage.close(this)
    }

    /** «a, b , ,c» -> [a, b, c]; null when the user left the field empty. */
    private fun CharSequence?.splitList(): List<String>? = this?.toString()
        ?.nullIfBlank()
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.takeIf { it.isNotEmpty() }

    private fun confirmDelete() {
        if (position < 0) return
        AlertDialog.Builder(this)
            .setMessage(R.string.routing_ed_delete_confirm)
            .setPositiveButton(R.string.editor_delete) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    SettingsManager.removeRoutingRuleset(position)
                    withContext(Dispatchers.Main) { SubPage.close(this@RoutingEditActivity) }
                }
            }
            .setNegativeButton(R.string.editor_cancel, null)
            .show()
    }
}
