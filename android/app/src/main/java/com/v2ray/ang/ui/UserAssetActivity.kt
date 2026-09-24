package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.BaseAdapterListener
import com.v2ray.ang.databinding.ActivityUserAssetBinding
import com.v2ray.ang.dto.entities.AssetUrlItem
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.component.EmptyStateBinder
import com.v2ray.ang.ui.component.RowBinder
import com.v2ray.ang.ui.component.SelectPopup
import com.v2ray.ang.ui.component.SubPage
import com.v2ray.ang.ui.component.ToolbarBinder
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.UserAssetViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A-25 `settings/assets` - «Файлы ресурсов». H3 header, R3 rhythm.
 *
 * The four ways to get a file in here were toolbar menu items - «Добавить файл», «Добавить ссылку»,
 * «Добавить по QR-коду», «Скачать» - on an ActionBar A-38 removes. Three of them are now the
 * header's one trailing action opening a sheet, and «Обновить файлы» is a visible action row,
 * because it is the thing a user actually comes here to do.
 *
 * The screen had no empty state: on a fresh install it drew a section header above nothing. It now
 * says what geo files are for and offers the one action that fixes it.
 */
class UserAssetActivity : HelperBaseActivity() {

    private val binding by lazy { ActivityUserAssetBinding.inflate(layoutInflater) }
    private val viewModel: UserAssetViewModel by viewModels()
    private lateinit var adapter: UserAssetAdapter
    private var downloading = false

    val extDir by lazy { File(Utils.userAssetPath(this)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        SubPage.installTransitions(this)
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        ToolbarBinder.bind(
            root = binding.toolbar.root,
            title = getString(R.string.asset_title),
            activity = this,
            actionIcon = R.drawable.ic_add_24dp,
            actionDescription = getString(R.string.asset_action_add_file),
            onAction = { showAddActions() },
        )
        ToolbarBinder.attachTo(binding.toolbar.root, binding.mainContent)

        adapter = UserAssetAdapter(viewModel, extDir, ActivityAdapterListener())
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        bindSourceRow()
        bindDownloadRow()
    }

    override fun onResume() {
        super.onResume()
        refreshData()
    }

    // -------------------------------------------------------------- source

    private fun currentSource(): String =
        MmkvManager.decodeSettingsString(AppConfig.PREF_GEO_FILES_SOURCES)
            ?: AppConfig.GEO_FILES_SOURCES.first()

    private fun bindSourceRow() {
        val current = currentSource()
        RowBinder.bind(
            root = binding.rowSource.root,
            title = getString(R.string.asset_source),
            // The current source is the SUBTITLE, where the prototype puts it: these names run
            // to «Loyalsoldier/v2ray-rules-dat» and a trailing value would ellipsise all of it
            // away. Same reason «User-Agent» on Настройки провайдеров reads its value that way.
            subtitle = current,
            glyph = R.drawable.ic_globe_24dp,
            trailing = RowBinder.Trailing.Glyph(
                icon = R.drawable.ic_arrow_drop_down,
                contentDescription = getString(R.string.asset_source_cd),
            ),
            // §6's «окошко у значения», not a cycle. Cycling made the third source reachable
            // only by tapping twice past the second and never showed what the options WERE -
            // the same defect «Стратегия доменов» on Маршрутизация was carrying.
            onClick = {
                val sources = AppConfig.GEO_FILES_SOURCES
                SelectPopup.show(
                    anchor = binding.rowSource.root,
                    options = sources.toList(),
                    selectedIndex = sources.indexOf(current).coerceAtLeast(0),
                    caret = binding.rowSource.rowTrailingGlyph,
                ) { picked ->
                    MmkvManager.encodeSettings(AppConfig.PREF_GEO_FILES_SOURCES, sources[picked])
                    bindSourceRow()
                    refreshData()
                }
            },
        )
        // The card hosting the popup must not clip it, so its rows carry the corners.
        RowBinder.edge(binding.rowSource.root, RowBinder.Edge.TOP)
        RowBinder.edge(binding.rowDownload.root, RowBinder.Edge.BOTTOM)
    }

    /** «Обновить файлы» reports its own progress in place, per §15: a row that is working says so. */
    private fun bindDownloadRow() {
        RowBinder.bind(
            root = binding.rowDownload.root,
            title = getString(
                if (downloading) R.string.asset_downloading else R.string.asset_action_download
            ),
            subtitle = getString(R.string.asset_action_download_hint),
            glyph = R.drawable.ic_cloud_download_24dp,
            trailing = RowBinder.Trailing.None,
            enabled = !downloading,
            onClick = if (downloading) null else ({ downloadGeoFiles() }),
        )
    }

    // ------------------------------------------------------------- actions

    private fun showAddActions() {
        EditorActionsSheet(this, getString(R.string.editor_actions_title))
            .action(R.string.asset_action_add_file, R.drawable.ic_file_24dp) { showFileChooser() }
            .action(R.string.asset_action_add_url, R.drawable.ic_globe_24dp) {
                SubPage.open(this, Intent(this, UserAssetUrlActivity::class.java))
            }
            .action(R.string.asset_action_add_qrcode, R.drawable.ic_scan_24dp) {
                importAssetFromQRcode()
            }
            .show()
    }

    private fun showFileChooser() {
        launchFileChooser { uri ->
            if (uri == null) return@launchFileChooser

            val assetId = Utils.getUuid()
            runCatching {
                val assetItem = AssetUrlItem(getCursorName(uri) ?: uri.toString(), "file")
                val assetList = MmkvManager.decodeAssetUrls()
                if (assetList.any { it.assetUrl.remarks == assetItem.remarks && it.guid != assetId }) {
                    toastError(R.string.asset_name_duplicate)
                } else {
                    MmkvManager.encodeAsset(assetId, assetItem)
                    copyFile(uri)
                }
            }.onFailure {
                toastError(R.string.asset_copy_failed)
                MmkvManager.removeAssetUrl(assetId)
            }
        }
    }

    private fun copyFile(uri: Uri): String {
        val targetFile = File(extDir, getCursorName(uri) ?: uri.toString())
        contentResolver.openInputStream(uri).use { inputStream ->
            targetFile.outputStream().use { fileOut ->
                inputStream?.copyTo(fileOut)
                toastSuccess(R.string.editor_saved)
                refreshData()
            }
        }
        return targetFile.path
    }

    private fun getCursorName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.let { cursor ->
            cursor.run {
                if (moveToFirst()) getString(getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                else null
            }.also { cursor.close() }
        }
    } catch (e: Exception) {
        LogUtil.e(AppConfig.TAG, "Failed to get cursor name", e)
        null
    }

    private fun importAssetFromQRcode() {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) importAsset(scanResult)
        }
    }

    private fun importAsset(url: String?) {
        if (!Utils.isValidUrl(url)) {
            toastError(R.string.editor_url_invalid)
            return
        }
        SubPage.open(
            this,
            Intent(this, UserAssetUrlActivity::class.java)
                .putExtra(UserAssetUrlActivity.ASSET_URL_QRCODE, url),
        )
    }

    private fun downloadGeoFiles() {
        downloading = true
        bindDownloadRow()

        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()
        val httpPort = SettingsManager.getHttpPort()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                viewModel.downloadGeoFiles(extDir, httpPort, proxyUsername, proxyPassword)
            }
            downloading = false
            bindDownloadRow()
            // Only the failure speaks. The download row rebinds with the new file dates the
            // instant this returns, so «Скачано файлов: 3» reported a number about a list the
            // user is already looking at.
            if (result.successCount <= 0) toastError(R.string.asset_download_failed)
            refreshData()
        }
    }

    private fun initAssets() {
        lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                SettingsManager.initAssets(this@UserAssetActivity, assets)
            }
            refreshData()
        }
    }

    // -------------------------------------------------------------- states

    @SuppressLint("NotifyDataSetChanged")
    fun refreshData() {
        viewModel.reload(currentSource())
        adapter.notifyDataSetChanged()

        val isEmpty = viewModel.itemCount == 0
        binding.recyclerView.isVisible = !isEmpty
        binding.labelFiles.isVisible = !isEmpty
        if (isEmpty) {
            EmptyStateBinder.bind(
                root = binding.emptyState.root,
                glyph = R.drawable.ic_file_24dp,
                title = getString(R.string.asset_empty_title),
                line = getString(R.string.asset_empty_line),
                actionLabel = getString(R.string.asset_action_download),
                emphasis = EmptyStateBinder.Emphasis.PRIMARY,
                onAction = { downloadGeoFiles() },
            )
        } else {
            EmptyStateBinder.hide(binding.emptyState.root)
        }
    }

    private inner class ActivityAdapterListener : BaseAdapterListener {
        override fun onEdit(guid: String, position: Int) {
            SubPage.open(
                this@UserAssetActivity,
                Intent(this@UserAssetActivity, UserAssetUrlActivity::class.java)
                    .putExtra("assetId", guid),
            )
        }

        override fun onRemove(guid: String, position: Int) {
            val asset = viewModel.getAsset(position)?.takeIf { it.guid == guid }
                ?: viewModel.getAssets().find { it.guid == guid }
                ?: return
            val file = extDir.listFiles()?.find { it.name == asset.assetUrl.remarks }

            AlertDialog.Builder(this@UserAssetActivity)
                .setMessage(R.string.asset_delete_confirm)
                .setPositiveButton(R.string.editor_delete) { _, _ ->
                    file?.delete()
                    MmkvManager.removeAssetUrl(guid)
                    initAssets()
                }
                .setNegativeButton(R.string.editor_cancel, null)
                .show()
        }


        override fun onRefreshData() {
            refreshData()
        }
    }
}
