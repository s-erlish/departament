package com.v2ray.ang.ui

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityServerGroupBinding
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.ui.component.RowBinder
import com.v2ray.ang.ui.component.SubPage
import com.v2ray.ang.ui.component.ToolbarBinder
import com.v2ray.ang.ui.component.onSingleClick
import com.v2ray.ang.util.Utils

/**
 * The policy-group editor (part of A-13). H3 header, R4 rhythm.
 *
 * Both `Spinner`s are gone - §0.5.6 leaves none in the product. The group type has two values, so it
 * cycles in place with the unfold glyph; the subscription can have any number, so it opens the
 * editors' sheet. Both rows print the current value, which the spinners did only while closed and
 * never in a form the user could read back after scrolling past.
 *
 * Save and delete were toolbar menu items on the ActionBar A-38 removes.
 */
class ServerGroupActivity : BaseActivity() {

    private val binding by lazy { ActivityServerGroupBinding.inflate(layoutInflater) }

    private val editGuid by lazy { intent.getStringExtra("guid").orEmpty() }
    private val isRunning by lazy {
        intent.getBooleanExtra("isRunning", false) &&
            editGuid.isNotEmpty() &&
            editGuid == MmkvManager.getSelectServer()
    }
    private val subscriptionId by lazy { intent.getStringExtra("subscriptionId") }

    private val groupTypes: Array<out String> by lazy {
        resources.getStringArray(R.array.policy_group_type)
    }

    /** Parallel to [subNames]; index 0 is «все подписки» and carries an empty id. */
    private val subIds = mutableListOf<String>()
    private val subNames = mutableListOf<String>()

    private var groupTypeIndex = 0
    private var subIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        SubPage.installTransitions(this)
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        ToolbarBinder.bind(
            root = binding.toolbar.root,
            title = getString(R.string.srv_group_title),
            activity = this,
        )
        ToolbarBinder.attachTo(binding.toolbar.root, binding.mainContent)

        loadSubscriptions()

        val config = MmkvManager.decodeServerConfig(editGuid)
        if (config != null) bindConfig(config) else clearForm()

        bindTypeRow()
        bindSubRow()

        binding.btnSave.onSingleClick { saveServer() }

        RowBinder.bind(
            root = binding.rowDelete.root,
            title = getString(R.string.srv_delete),
            tone = RowBinder.RowTone.DESTRUCTIVE,
            trailing = RowBinder.Trailing.None,
            onClick = { deleteServer() },
        )
        binding.rowDelete.root.isVisible = editGuid.isNotEmpty() && !isRunning
    }

    private fun loadSubscriptions() {
        subIds.clear()
        subNames.clear()
        subIds.add("")
        subNames.add(getString(R.string.srv_group_sub_all))
        MmkvManager.decodeSubscriptions().forEach { sub ->
            subIds.add(sub.guid)
            subNames.add(sub.subscription.remarks.ifBlank { sub.guid })
        }
    }

    private fun bindConfig(config: ProfileItem) {
        binding.etRemarks.text = Utils.getEditable(config.remarks)
        binding.etPolicyGroupFilter.text = Utils.getEditable(config.policyGroupFilter)
        groupTypeIndex = config.policyGroupType?.toIntOrNull()?.coerceIn(groupTypes.indices) ?: 0
        subIndex = subIds.indexOf(config.policyGroupSubscriptionId.orEmpty()).coerceAtLeast(0)
    }

    private fun clearForm() {
        binding.etRemarks.text = null
        binding.etPolicyGroupFilter.text = null
        groupTypeIndex = 0
        subIndex = if (subscriptionId.isNotNullEmpty()) {
            subIds.indexOf(subscriptionId).coerceAtLeast(0)
        } else {
            0
        }
    }

    private fun bindTypeRow() {
        RowBinder.bind(
            root = binding.rowGroupType.root,
            title = getString(R.string.srv_group_type),
            glyph = R.drawable.ic_routing_24dp,
            value = groupTypes.getOrNull(groupTypeIndex),
            trailing = RowBinder.Trailing.Glyph(
                icon = R.drawable.ic_arrow_drop_down,
                contentDescription = null,
            ),
            onClick = {
                groupTypeIndex = (groupTypeIndex + 1) % groupTypes.size
                bindTypeRow()
            },
        )
    }

    private fun bindSubRow() {
        RowBinder.bind(
            root = binding.rowGroupSub.root,
            title = getString(R.string.srv_group_sub),
            glyph = R.drawable.ic_subscriptions_24dp,
            value = subNames.getOrNull(subIndex),
            trailing = RowBinder.Trailing.Chevron,
            onClick = {
                EditorActionsSheet(this, getString(R.string.srv_group_sub)).apply {
                    subNames.forEachIndexed { index, name ->
                        action(label = name, selected = index == subIndex) {
                            subIndex = index
                            bindSubRow()
                        }
                    }
                }.show()
            },
        )
    }

    private fun saveServer() {
        binding.tilRemarks.error = null
        val remarks = binding.etRemarks.text.toString().trim()
        if (remarks.isEmpty()) {
            binding.tilRemarks.error = getString(R.string.srv_name_required)
            binding.etRemarks.requestFocus()
            return
        }

        val config = MmkvManager.decodeServerConfig(editGuid)
            ?: ProfileItem.create(EConfigType.POLICYGROUP)
        config.remarks = remarks
        config.policyGroupFilter = binding.etPolicyGroupFilter.text.toString().trim()
        config.policyGroupType = groupTypeIndex.toString()
        config.policyGroupSubscriptionId = subIds.getOrNull(subIndex)?.takeIf { it.isNotEmpty() }
        if (config.subscriptionId.isEmpty() && !subscriptionId.isNullOrEmpty()) {
            config.subscriptionId = subscriptionId.orEmpty()
        }
        config.description = listOf(
            groupTypes.getOrNull(groupTypeIndex).orEmpty(),
            subNames.getOrNull(subIndex).orEmpty(),
            config.policyGroupFilter.orEmpty(),
        ).joinToString(" - ")

        MmkvManager.encodeServerConfig(editGuid, config)
        if (isRunning) SettingsChangeManager.makeRestartService()
        toastSuccess(R.string.editor_saved)
        SubPage.close(this)
    }

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
}
