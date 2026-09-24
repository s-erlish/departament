package com.v2ray.ang.ui

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityUserAssetUrlBinding
import com.v2ray.ang.dto.entities.AssetUrlItem
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.component.RowBinder
import com.v2ray.ang.ui.component.SubPage
import com.v2ray.ang.ui.component.ToolbarBinder
import com.v2ray.ang.ui.component.onSingleClick
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import java.io.File

/**
 * A-26 - the geo-file form. H3 header, R4 rhythm.
 *
 * Save and delete were toolbar menu items on the ActionBar A-38 removes; they are the screen's one
 * Primary.Tall CTA and a `Row.Destructive` now. The three validation failures - empty name, empty
 * URL, duplicate name - were three toasts that appeared over the form and named the field in
 * English («remarks»); each is now an error under the field it belongs to, and focus moves there.
 */
class UserAssetUrlActivity : BaseActivity() {

    companion object {
        const val ASSET_URL_QRCODE = "ASSET_URL_QRCODE"
    }

    private val binding by lazy { ActivityUserAssetUrlBinding.inflate(layoutInflater) }
    private val extDir by lazy { File(Utils.userAssetPath(this)) }
    private val editAssetId by lazy { intent.getStringExtra("assetId").orEmpty() }

    override fun onCreate(savedInstanceState: Bundle?) {
        SubPage.installTransitions(this)
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        val assetItem = MmkvManager.decodeAsset(editAssetId)

        ToolbarBinder.bind(
            root = binding.toolbar.root,
            title = getString(
                if (assetItem == null) R.string.asset_url_title_new else R.string.asset_url_title_edit
            ),
            activity = this,
        )
        ToolbarBinder.attachTo(binding.toolbar.root, binding.mainContent)

        val assetUrlQrcode = intent.getStringExtra(ASSET_URL_QRCODE)
        when {
            assetItem != null -> {
                binding.etRemarks.text = Utils.getEditable(assetItem.remarks)
                binding.etUrl.text = Utils.getEditable(assetItem.url)
            }

            assetUrlQrcode != null -> {
                binding.etRemarks.setText(File(assetUrlQrcode).name)
                binding.etUrl.setText(assetUrlQrcode)
            }

            else -> {
                binding.etRemarks.text = null
                binding.etUrl.text = null
            }
        }

        binding.btnSave.onSingleClick { saveAsset() }

        RowBinder.bind(
            root = binding.rowDelete.root,
            title = getString(R.string.asset_url_delete),
            tone = RowBinder.RowTone.DESTRUCTIVE,
            trailing = RowBinder.Trailing.None,
            onClick = { confirmDelete() },
        )
        binding.rowDelete.root.isVisible = editAssetId.isNotEmpty()
    }

    private fun saveAsset() {
        val remarks = binding.etRemarks.text.toString().trim()
        val url = binding.etUrl.text.toString().trim()

        binding.tilRemarks.error = null
        binding.tilUrl.error = null

        if (remarks.isEmpty()) {
            binding.tilRemarks.error = getString(R.string.asset_name_required)
            binding.etRemarks.requestFocus()
            return
        }
        if (url.isEmpty()) {
            binding.tilUrl.error = getString(R.string.asset_url_required)
            binding.etUrl.requestFocus()
            return
        }

        var assetItem = MmkvManager.decodeAsset(editAssetId)
        var assetId = editAssetId
        if (assetItem != null) {
            // The file on disk is named after the OLD remark, so renaming the asset orphans it.
            val file = extDir.resolve(assetItem.remarks)
            if (file.exists()) {
                runCatching { file.delete() }.onFailure {
                    LogUtil.e(AppConfig.TAG, "Failed to delete asset file: ${file.path}", it)
                }
            }
        } else {
            assetId = Utils.getUuid()
            assetItem = AssetUrlItem()
        }

        if (MmkvManager.decodeAssetUrls().any { it.assetUrl.remarks == remarks && it.guid != assetId }) {
            binding.tilRemarks.error = getString(R.string.asset_name_duplicate)
            binding.etRemarks.requestFocus()
            return
        }

        assetItem.remarks = remarks
        assetItem.url = url
        MmkvManager.encodeAsset(assetId, assetItem)
        toastSuccess(R.string.editor_saved)
        SubPage.close(this)
    }

    private fun confirmDelete() {
        if (editAssetId.isEmpty()) return
        AlertDialog.Builder(this)
            .setMessage(R.string.asset_delete_confirm)
            .setPositiveButton(R.string.editor_delete) { _, _ ->
                MmkvManager.removeAssetUrl(editAssetId)
                SubPage.close(this)
            }
            .setNegativeButton(R.string.editor_cancel, null)
            .show()
    }
}
