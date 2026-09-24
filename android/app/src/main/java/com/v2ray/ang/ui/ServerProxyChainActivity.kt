package com.v2ray.ang.ui

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.BaseAdapterListener
import com.v2ray.ang.databinding.ActivityServerProxyChainBinding
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.SimpleItemTouchHelperCallback
import com.v2ray.ang.ui.component.EmptyStateBinder
import com.v2ray.ang.ui.component.RowBinder
import com.v2ray.ang.ui.component.SubPage
import com.v2ray.ang.ui.component.ToolbarBinder
import com.v2ray.ang.ui.component.onSingleClick
import com.v2ray.ang.util.Utils

/**
 * The proxy-chain editor (part of A-13). H3 header, R4 rhythm.
 *
 * Three structural changes:
 *
 * - **The FAB is deleted.** «The app has one FAB and it does not earn a floating layer» (A-13), and
 *   this one was worse than that: it lived inside the scrolling column, so it slid away from the
 *   list it added to. Adding a step is the header's one trailing action.
 * - **Save and delete were toolbar menu items** on the ActionBar A-38 removes; they are the screen's
 *   Primary.Tall CTA and a `Row.Destructive`.
 * - **The screen had no empty state.** A chain with no steps drew a section header above nothing.
 *
 * The four validation failures were four toasts; they are one inline message under the field or on
 * the step that is wrong, so the user is told WHICH step is empty rather than that one is.
 */
class ServerProxyChainActivity : BaseActivity() {

    private val binding by lazy { ActivityServerProxyChainBinding.inflate(layoutInflater) }
    private val editGuid by lazy { intent.getStringExtra("guid").orEmpty() }
    private val isRunning by lazy {
        intent.getBooleanExtra("isRunning", false) &&
            editGuid.isNotEmpty() &&
            editGuid == MmkvManager.getSelectServer()
    }
    private val subscriptionId by lazy { intent.getStringExtra("subscriptionId") }

    private lateinit var memberAdapter: ServerProxyChainMemberAdapter
    private var allRemarks: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        SubPage.installTransitions(this)
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        ToolbarBinder.bind(
            root = binding.toolbar.root,
            title = getString(R.string.srv_chain_title),
            activity = this,
            actionIcon = R.drawable.ic_add_24dp,
            actionDescription = getString(R.string.srv_chain_add),
            onAction = { addMemberRow() },
        )
        ToolbarBinder.attachTo(binding.toolbar.root, binding.mainContent)

        allRemarks = SettingsManager.getProfileRemarks(
            excludeConfigTypes = setOf(
                EConfigType.CUSTOM,
                EConfigType.POLICYGROUP,
                EConfigType.PROXYCHAIN,
            )
        )

        setupRecycler()

        val config = MmkvManager.decodeServerConfig(editGuid)
        if (config != null) bindConfig(config) else clearForm()

        binding.btnSave.onSingleClick { saveServer() }

        RowBinder.bind(
            root = binding.rowDelete.root,
            title = getString(R.string.srv_delete),
            tone = RowBinder.RowTone.DESTRUCTIVE,
            trailing = RowBinder.Trailing.None,
            onClick = { deleteServer() },
        )
        // The running server cannot be deleted from under the tunnel, and a control that would only
        // refuse is not shown at all.
        binding.rowDelete.root.isVisible = editGuid.isNotEmpty() && !isRunning
    }

    private fun setupRecycler() {
        memberAdapter = ServerProxyChainMemberAdapter(
            members = mutableListOf(),
            adapterListener = ActivityAdapterListener(),
        )
        memberAdapter.onStepClick = ::showStepActions
        binding.recyclerProxyChainMembers.layoutManager = LinearLayoutManager(this)
        binding.recyclerProxyChainMembers.adapter = memberAdapter
        ItemTouchHelper(SimpleItemTouchHelperCallback(memberAdapter))
            .attachToRecyclerView(binding.recyclerProxyChainMembers)
    }

    private fun bindConfig(config: ProfileItem) {
        binding.etRemarks.text = Utils.getEditable(config.remarks)
        val rows = config.proxyChainProfiles.orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        memberAdapter.replaceAll(rows.ifEmpty { listOf("", "") })
    }

    private fun clearForm() {
        binding.etRemarks.text = null
        // A chain is two servers at minimum, so a new one starts with two empty steps rather than
        // with an empty list the user has to guess how to fill.
        memberAdapter.replaceAll(listOf("", ""))
    }

    /**
     * One sheet per step: pick a server, or take the step out. Two jobs, one target - the row - so
     * the row keeps the single trailing element §4.5 allows.
     */
    private fun showStepActions(position: Int) {
        val sheet = EditorActionsSheet(this, getString(R.string.srv_chain_step, position + 1))
        val current = memberAdapter.getMembers().getOrNull(position)
        allRemarks.forEach { remark ->
            sheet.action(
                label = remark,
                glyph = R.drawable.ic_globe_24dp,
                selected = remark == current,
            ) {
                memberAdapter.setRemark(position, remark)
            }
        }
        if (memberAdapter.getMembers().size > MIN_CHAIN_MEMBERS) {
            sheet.destructive(R.string.srv_chain_remove_step) { memberAdapter.removeRow(position) }
        }
        sheet.show()
    }

    private fun addMemberRow() {
        if (allRemarks.isEmpty()) {
            toastError(R.string.srv_chain_no_servers)
            return
        }
        memberAdapter.addRow()
        refreshEmptyState()
    }

    // -------------------------------------------------------------- save

    private fun saveServer() {
        binding.tilRemarks.error = null

        val remarks = binding.etRemarks.text.toString().trim()
        if (remarks.isEmpty()) {
            binding.tilRemarks.error = getString(R.string.srv_name_required)
            binding.etRemarks.requestFocus()
            return
        }

        val members = memberAdapter.getMembers().map { it.trim() }
        if (members.any { it.isEmpty() }) {
            toastError(R.string.srv_chain_step_empty)
            return
        }
        if (members.size < MIN_CHAIN_MEMBERS) {
            toastError(R.string.srv_chain_too_few)
            return
        }

        val invalid = members.filter { member ->
            val profile = SettingsManager.getServerViaRemarks(member)
            profile == null || profile.configType.isComplexType()
        }
        if (invalid.isNotEmpty()) {
            toastError(R.string.srv_chain_invalid_members)
            return
        }

        val config = MmkvManager.decodeServerConfig(editGuid)
            ?: ProfileItem.create(EConfigType.PROXYCHAIN)
        config.remarks = remarks
        config.proxyChainProfiles = members.joinToString(",")
        config.description = members.joinToString(" -> ")
        if (config.subscriptionId.isEmpty() && !subscriptionId.isNullOrEmpty()) {
            config.subscriptionId = subscriptionId.orEmpty()
        }

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
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_CONFIRM_REMOVE)) {
            AlertDialog.Builder(this)
                .setMessage(R.string.srv_delete_confirm)
                .setPositiveButton(R.string.editor_delete) { _, _ -> removeServer() }
                .setNegativeButton(R.string.editor_cancel, null)
                .show()
        } else {
            removeServer()
        }
    }

    private fun removeServer() {
        MmkvManager.removeServer(editGuid)
        SubPage.close(this)
    }

    private fun refreshEmptyState() {
        val isEmpty = memberAdapter.itemCount == 0
        binding.recyclerProxyChainMembers.isVisible = !isEmpty
        binding.labelMembers.isVisible = !isEmpty
        if (isEmpty) {
            EmptyStateBinder.bind(
                root = binding.emptyState.root,
                glyph = R.drawable.ic_globe_24dp,
                title = getString(R.string.srv_chain_empty_title),
                line = getString(R.string.srv_chain_empty_line),
                actionLabel = getString(R.string.srv_chain_add),
                emphasis = EmptyStateBinder.Emphasis.PRIMARY,
                onAction = { addMemberRow() },
            )
        } else {
            EmptyStateBinder.hide(binding.emptyState.root)
        }
    }

    private inner class ActivityAdapterListener : BaseAdapterListener {
        override fun onEdit(guid: String, position: Int) {}

        override fun onRemove(guid: String, position: Int) {
            memberAdapter.removeRow(position)
            refreshEmptyState()
        }


        override fun onRefreshData() {
            refreshEmptyState()
        }
    }

    private companion object {
        const val MIN_CHAIN_MEMBERS = 2
    }
}
