package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.WEBDAV_BACKUP_FILE_NAME
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityBackupBinding
import com.v2ray.ang.databinding.DialogWebdavBinding
import com.v2ray.ang.dto.entities.WebDavConfig
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.WebDavManager
import com.v2ray.ang.ui.component.RowBinder
import com.v2ray.ang.ui.component.SubPage
import com.v2ray.ang.ui.component.ToolbarBinder
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.ZipUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * A-29 `settings/data` - «Данные и резервные копии». H3 header, R1 rhythm.
 *
 * Two of the four rows opened an `AlertDialog.setItems` with the untranslated pair «Local» /
 * «WebDAV» to ask WHERE - a two-item picker dialog §7.4 bans, and the last English strings on a
 * Russian screen. The destination is the row now, six of them in two named groups, each saying what
 * it does.
 *
 * Three states the screen did not draw:
 *
 * - **Not configured.** «Выгрузить в облако» used to fail with a toast AFTER the tap. The cloud rows
 *   are disabled at 0.38 until WebDAV has an address, with the reason in their subtitle (R6), and
 *   the settings row states the current address as its value.
 * - **Working.** Backup and restore both hit the network and the disk with no feedback at all. The
 *   row that is working says so and cannot be tapped twice.
 * - **Restore is irreversible.** It overwrote every setting in the app with no confirmation. §7.5
 *   keeps a confirm exactly for this case, and the button carries the verb.
 */
class BackupActivity : HelperBaseActivity() {

    private val binding by lazy { ActivityBackupBinding.inflate(layoutInflater) }
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        SubPage.installTransitions(this)
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        ToolbarBinder.bind(
            root = binding.toolbar.root,
            title = getString(R.string.backup_title),
            activity = this,
            // §7's optional 13sp note: the prototype says what the copy IS before the rows say
            // what you can do with it.
            note = getString(R.string.backup_note),
        )
        ToolbarBinder.attachTo(binding.toolbar.root, binding.mainContent)

        bindRows()
    }

    // ---------------------------------------------------------------- rows

    private fun webDavConfig(): WebDavConfig? =
        MmkvManager.decodeWebDavConfig()?.takeIf { it.baseUrl.isNotEmpty() }

    private fun bindRows() {
        val cloud = webDavConfig()
        val cloudReady = cloud != null && !busy

        RowBinder.bind(
            root = binding.rowBackupLocal.root,
            title = getString(R.string.backup_action_create),
            subtitle = getString(R.string.backup_action_create_hint),
            glyph = R.drawable.ic_backup_24dp,
            // The prototype gives all three data rows a chevron: each one opens the system
            // file picker, which is a screen, and a chevron is the promise of one.
            trailing = RowBinder.Trailing.Chevron,
            enabled = !busy,
            onClick = if (busy) null else ({ backupViaLocal() }),
        )
        RowBinder.bind(
            root = binding.rowRestoreLocal.root,
            title = getString(R.string.backup_action_restore),
            subtitle = getString(R.string.backup_action_restore_hint),
            glyph = R.drawable.ic_restore_24dp,
            trailing = RowBinder.Trailing.Chevron,
            enabled = !busy,
            onClick = if (busy) null else ({ confirmRestore { restoreViaLocal() } }),
        )
        RowBinder.bind(
            root = binding.rowShare.root,
            title = getString(R.string.backup_action_share),
            subtitle = getString(R.string.backup_action_share_hint),
            glyph = R.drawable.ic_share_24dp,
            trailing = RowBinder.Trailing.Chevron,
            enabled = !busy,
            onClick = if (busy) null else ({ shareBackup() }),
        )

        RowBinder.bind(
            root = binding.rowWebdavSettings.root,
            title = getString(R.string.backup_action_webdav_setup),
            glyph = R.drawable.ic_globe_24dp,
            value = cloud?.baseUrl ?: getString(R.string.backup_webdav_not_set),
            trailing = RowBinder.Trailing.Chevron,
            onClick = { showWebDavSettingsDialog() },
        )
        RowBinder.bind(
            root = binding.rowBackupWebdav.root,
            title = getString(if (busy) R.string.backup_busy else R.string.backup_action_webdav_backup),
            subtitle = if (cloud == null) getString(R.string.backup_webdav_required) else null,
            glyph = R.drawable.ic_cloud_download_24dp,
            trailing = RowBinder.Trailing.None,
            enabled = cloudReady,
            onClick = if (cloudReady) ({ backupViaWebDav() }) else null,
        )
        RowBinder.bind(
            root = binding.rowRestoreWebdav.root,
            title = getString(if (busy) R.string.backup_busy else R.string.backup_action_webdav_restore),
            subtitle = if (cloud == null) getString(R.string.backup_webdav_required) else null,
            glyph = R.drawable.ic_restore_24dp,
            trailing = RowBinder.Trailing.None,
            enabled = cloudReady,
            onClick = if (cloudReady) ({ confirmRestore { restoreViaWebDav() } }) else null,
        )
    }

    private fun setBusy(value: Boolean) {
        busy = value
        bindRows()
    }

    /**
     * Restore replaces every setting in the app and cannot be undone, which is the one shape §7.5
     * still allows a confirm dialog for. The button carries the verb, not «OK».
     */
    private fun confirmRestore(onConfirm: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(R.string.backup_restore_confirm)
            .setPositiveButton(R.string.backup_restore_confirm_action) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.editor_cancel, null)
            .show()
    }

    // ------------------------------------------------------------- local

    private fun backupConfigurationToCache(): Pair<Boolean, String> {
        val dateFormatted = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault())
            .format(System.currentTimeMillis())
        val folderName = "${getString(R.string.app_name)}_${dateFormatted}"
        val backupDir = this.cacheDir.absolutePath + "/$folderName"
        val outputZipFilePath = "${this.cacheDir.absolutePath}/$folderName.zip"

        val count = MMKV.backupAllToDirectory(backupDir)
        if (count <= 0) return Pair(false, "")

        return if (ZipUtil.zipFromFolder(backupDir, outputZipFilePath)) {
            Pair(true, outputZipFilePath)
        } else {
            Pair(false, "")
        }
    }

    private fun restoreConfiguration(zipFile: File): Boolean {
        val backupDir = this.cacheDir.absolutePath + "/${System.currentTimeMillis()}"
        if (!ZipUtil.unzipToFolder(zipFile, backupDir)) return false

        val count = MMKV.restoreAllFromDirectory(backupDir)
        SettingsChangeManager.makeSetupGroupTab()
        SettingsChangeManager.makeRestartService()
        SettingsManager.initApp(this)
        return count > 0
    }

    private fun backupViaLocal() {
        val dateFormatted = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault())
            .format(System.currentTimeMillis())
        val defaultFileName = "${getString(R.string.app_name)}_${dateFormatted}.zip"

        launchCreateDocument(defaultFileName) { uri ->
            if (uri == null) return@launchCreateDocument
            try {
                val ret = backupConfigurationToCache()
                if (ret.first) {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        File(ret.second).inputStream().use { input -> input.copyTo(output) }
                    }
                    File(ret.second).delete()
                    toastSuccess(R.string.backup_created)
                } else {
                    toastError(R.string.backup_failed)
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to backup configuration", e)
                toastError(R.string.backup_failed)
            }
        }
    }

    private fun restoreViaLocal() {
        launchFileChooser { uri ->
            if (uri == null) return@launchFileChooser
            try {
                val targetFile = File(this.cacheDir.absolutePath, "${System.currentTimeMillis()}.zip")
                contentResolver.openInputStream(uri).use { input ->
                    targetFile.outputStream().use { fileOut -> input?.copyTo(fileOut) }
                }
                if (restoreConfiguration(targetFile)) {
                    toastSuccess(R.string.backup_restored)
                } else {
                    toastError(R.string.backup_restore_failed)
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Error during file restore", e)
                toastError(R.string.backup_restore_failed)
            }
        }
    }

    private fun shareBackup() {
        val ret = backupConfigurationToCache()
        if (!ret.first) {
            toastError(R.string.backup_failed)
            return
        }
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType("application/zip")
                    .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(
                        Intent.EXTRA_STREAM,
                        FileProvider.getUriForFile(
                            this,
                            BuildConfig.APPLICATION_ID + ".cache",
                            File(ret.second),
                        )
                    ),
                getString(R.string.backup_action_share),
            )
        )
    }

    // ------------------------------------------------------------ webdav

    private fun backupViaWebDav() {
        val saved = webDavConfig() ?: return
        setBusy(true)

        lifecycleScope.launch {
            var tempFile: File? = null
            val ok = withContext(Dispatchers.IO) {
                try {
                    val ret = backupConfigurationToCache()
                    if (!ret.first) return@withContext false
                    tempFile = File(ret.second)
                    WebDavManager.init(saved)
                    WebDavManager.uploadFile(tempFile, WEBDAV_BACKUP_FILE_NAME)
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "WebDAV backup error", e)
                    false
                } finally {
                    runCatching { tempFile?.delete() }
                }
            }
            setBusy(false)
            if (ok) toastSuccess(R.string.backup_created) else toastError(R.string.backup_cloud_failed)
        }
    }

    private fun restoreViaWebDav() {
        val saved = webDavConfig() ?: return
        setBusy(true)

        lifecycleScope.launch {
            var target: File? = null
            val restored = withContext(Dispatchers.IO) {
                try {
                    target = File(cacheDir, "download_${System.currentTimeMillis()}.zip")
                    WebDavManager.init(saved)
                    val downloaded = WebDavManager.downloadFile(WEBDAV_BACKUP_FILE_NAME, target)
                    if (!downloaded) false else restoreConfiguration(target)
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "WebDAV download error", e)
                    false
                } finally {
                    runCatching { target?.delete() }
                }
            }
            setBusy(false)
            if (restored) {
                toastSuccess(R.string.backup_restored)
            } else {
                toastError(R.string.backup_cloud_failed)
            }
        }
    }

    private fun showWebDavSettingsDialog() {
        val dialogBinding = DialogWebdavBinding.inflate(layoutInflater)

        MmkvManager.decodeWebDavConfig()?.let { cfg ->
            dialogBinding.etWebdavUrl.setText(cfg.baseUrl)
            dialogBinding.etWebdavUser.setText(cfg.username ?: "")
            dialogBinding.etWebdavPass.setText(cfg.password ?: "")
            // remoteBasePath is a non-null String with its own default on WebDavConfig, so the
            // elvis that used to sit here could never fire.
            dialogBinding.etWebdavRemotePath.setText(cfg.remoteBasePath)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.backup_action_webdav_setup)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.editor_save) { _, _ ->
                val url = dialogBinding.etWebdavUrl.text.toString().trim()
                val user = dialogBinding.etWebdavUser.text.toString().trim().ifEmpty { null }
                val pass = dialogBinding.etWebdavPass.text.toString()
                val remotePath = dialogBinding.etWebdavRemotePath.text.toString().trim()
                    .ifEmpty { AppConfig.WEBDAV_BACKUP_DIR }
                MmkvManager.encodeWebDavConfig(
                    WebDavConfig(
                        baseUrl = url,
                        username = user,
                        password = pass,
                        remoteBasePath = remotePath,
                    )
                )
                // The cloud rows depend on this, so they are re-evaluated the moment it changes.
                bindRows()
                toastSuccess(R.string.editor_saved)
            }
            .setNegativeButton(R.string.editor_cancel, null)
            .show()
    }
}
