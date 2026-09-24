package com.v2ray.ang.ui

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.blacksquircle.ui.editorkit.utils.EditorTheme
import com.blacksquircle.ui.language.json.JsonLanguage
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityServerCustomConfigBinding
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.fmt.CustomFmt
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.template.TemplateManager
import com.v2ray.ang.ui.component.RowBinder
import com.v2ray.ang.ui.component.SubPage
import com.v2ray.ang.ui.component.ToolbarBinder
import com.v2ray.ang.ui.component.onSingleClick
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils

/**
 * The custom-config editor (part of A-13). H3 header, R4 rhythm.
 *
 * The JSON editor is the right control and stays. What changed is the frame: the remark field is
 * the library field instead of a background-less `EditText` inside a card pretending to be one, save
 * and delete come off the ActionBar A-38 removes, and the malformed-JSON failure is a message under
 * the editor rather than a toast that concatenated `e.cause?.message` - a raw parser string §9.4
 * forbids showing a customer.
 */
class ServerCustomConfigActivity : BaseActivity() {

    private val binding by lazy { ActivityServerCustomConfigBinding.inflate(layoutInflater) }

    private val editGuid by lazy { intent.getStringExtra("guid").orEmpty() }
    private val isRunning by lazy {
        intent.getBooleanExtra("isRunning", false) &&
            editGuid.isNotEmpty() &&
            editGuid == MmkvManager.getSelectServer()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        SubPage.installTransitions(this)
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        ToolbarBinder.bind(
            root = binding.toolbar.root,
            title = getString(R.string.srv_custom_title),
            activity = this,
        )

        if (!Utils.getDarkModeStatus(this)) {
            binding.editor.colorScheme = EditorTheme.INTELLIJ_LIGHT
        }
        binding.editor.language = JsonLanguage()

        val config = MmkvManager.decodeServerConfig(editGuid)
        // Managed/hidden templates must never be shown in the editor.
        if (config != null && TemplateManager.isLocked(config)) {
            toastError(R.string.template_locked_toast)
            SubPage.close(this)
            return
        }

        if (config != null) {
            binding.etRemarks.text = Utils.getEditable(config.remarks)
            binding.editor.setTextContent(Utils.getEditable(MmkvManager.decodeServerRaw(editGuid).orEmpty()))
        } else {
            binding.etRemarks.text = null
        }

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

    private fun saveServer() {
        binding.tilRemarks.error = null

        val profileItem = try {
            CustomFmt.parse(binding.editor.text.toString())
        } catch (e: Exception) {
            // The parser's own message is developer text and often English; the log gets it, the
            // user gets a sentence that says what to do (§9.4).
            LogUtil.e(AppConfig.TAG, "Failed to parse custom configuration", e)
            toastError(R.string.srv_config_invalid)
            return
        }

        // CustomFmt.parse returns a non-null ProfileItem or throws, which is why none of these
        // reads is a safe call.
        val remarks = binding.etRemarks.text.toString().trim()
            .ifEmpty { profileItem.remarks }
        if (remarks.isEmpty()) {
            binding.tilRemarks.error = getString(R.string.srv_name_required)
            binding.etRemarks.requestFocus()
            return
        }

        val config = MmkvManager.decodeServerConfig(editGuid) ?: ProfileItem.create(EConfigType.CUSTOM)
        config.remarks = remarks
        config.server = profileItem.server
        config.serverPort = profileItem.serverPort
        config.description = AngConfigManager.generateDescription(config)

        MmkvManager.encodeServerConfig(editGuid, config)
        MmkvManager.encodeServerRaw(editGuid, binding.editor.text.toString())
        if (isRunning) SettingsChangeManager.makeRestartService()
        toastSuccess(R.string.editor_saved)
        SubPage.close(this)
    }

    private fun deleteServer() {
        if (editGuid.isEmpty()) return
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
