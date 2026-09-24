package com.v2ray.ang.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.databinding.ActivityCheckUpdateBinding
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.UpdateFailure
import com.v2ray.ang.extension.toast
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.UpdateCheckerManager
import com.v2ray.ang.ui.component.EmptyStateBinder
import com.v2ray.ang.ui.component.Haptic
import com.v2ray.ang.ui.component.RowBinder
import com.v2ray.ang.ui.component.SubPage
import com.v2ray.ang.ui.component.ToolbarBinder
import com.v2ray.ang.ui.component.clearClick
import com.v2ray.ang.ui.component.onSingleClick
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * A-36 - «Обновления». H3 header, R1 rhythm.
 *
 * departament is distributed as a direct APK, so this screen is the whole update path: it asks
 * **our** release feed ([AppConfig.APP_RELEASE_REPO]) what the newest build is, downloads it, and
 * hands it to the system installer. It used to poll `2dust/v2rayNG` and offer the customer a
 * stranger's application (M-07); `UpdateCheckerManager` owns that half now and refuses anything
 * that is not this package.
 *
 * EVERY OUTCOME IS DRAWN HERE, not announced. The result used to arrive as three toasts, one of
 * them carrying `e.message` raw to a customer; then as one sentence, «Проверьте сеть и повторите»,
 * for eight different dead ends. Now each state names itself and carries its own next step in the
 * same place (the owner's G2 rule), which is also why nothing on this screen goes through
 * `NoticePolicy`: a notice would put the reason somewhere other than the retry.
 *
 * - **checking** - a 20dp inline arc with «Проверяем обновления…», announced politely;
 * - **up to date** - «Обновлений нет», no action;
 * - **available** - «Доступна версия X», the release notes, one «Обновить»;
 * - **downloading** - a determinate bar with the real figures, and «Отменить»;
 * - **ready** - «Обновление готово» and «Установить»;
 * - **permission** - «Нужно разрешение на установку», routed to the system screen that grants it;
 * - **failed** - the mapped cause and the retry for THAT cause; the exception goes to the log.
 */
class CheckUpdateActivity : BaseActivity() {

    private val binding by lazy { ActivityCheckUpdateBinding.inflate(layoutInflater) }

    private val actionPrimary: MaterialButton get() = binding.actionPrimary.actionButton
    private val actionTertiary: MaterialButton get() = binding.actionTertiary.actionButton

    /** The offer currently on screen, kept so «Установить» and a retried download know what for. */
    private var offer: CheckUpdateResult? = null

    /** The verified APK, kept so a permission grant can resume straight to the installer. */
    private var downloaded: File? = null

    /**
     * True only between sending the user to «установка неизвестных приложений» and their return.
     * Without it, [onResume] would re-launch the installer every time the user backs out of it —
     * a screen the user cannot leave is worse than one that asks twice.
     */
    private var awaitingInstallPermission = false

    /** The in-flight check or download. Cancelled by «Отменить» and by leaving the screen. */
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        SubPage.installTransitions(this)
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        ToolbarBinder.bind(
            root = binding.toolbar.root,
            title = getString(R.string.upd_title),
            activity = this,
        )
        ToolbarBinder.attachTo(binding.toolbar.root, binding.mainContent)

        // §7's caption, not a row: «departament 2.2.1». It still copies on tap — the row it
        // replaced carried a copy action and a bug report needs the core build too.
        val version = "${BuildConfig.VERSION_NAME} (${CoreNativeManager.getLibVersion()})"
        binding.tvVersion.text = getString(R.string.upd_version_line, version)
        binding.tvVersion.contentDescription = getString(R.string.about_version_cd)
        binding.tvVersion.onSingleClick {
            Utils.setClipboard(this, version)
            toast(R.string.notice_copied)
        }

        bindPreReleaseRow()
        checkForUpdates()
    }

    /**
     * «Проверить обновление» — §7's accent action row. The name is the affordance, so no chevron
     * and no button; the tile carries the refresh glyph the prototype shows.
     *
     * It repeats what opening the screen already does, and that is the point: a check that only
     * happens on entry leaves the user with no way to ask again after fixing their network.
     */
    private fun bindCheckRow() {
        RowBinder.bind(
            root = binding.rowCheck.root,
            title = getString(R.string.upd_action_check),
            glyph = R.drawable.ic_refresh_24dp,
            tone = RowBinder.RowTone.ACCENT,
            trailing = RowBinder.Trailing.None,
            onClick = { checkForUpdates() },
        )
    }

    /**
     * Coming back from the system's «установка неизвестных приложений» screen is the one way the
     * answer changes while this activity is stopped. If the grant is there now, the user gets the
     * installer instead of being asked to press «Установить» again; if they declined, the screen
     * keeps saying what is missing rather than pretending the download failed.
     */
    override fun onResume() {
        super.onResume()
        if (!awaitingInstallPermission) return
        awaitingInstallPermission = false
        val file = downloaded ?: return
        if (canInstall()) install(file)
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    private fun bindPreReleaseRow() {
        bindCheckRow()
        RowBinder.bind(
            root = binding.rowPreRelease.root,
            title = getString(R.string.upd_prerelease),
            subtitle = getString(R.string.upd_prerelease_hint),
            glyph = R.drawable.ic_source_code_24dp,
            trailing = RowBinder.Trailing.Toggle(
                checked = MmkvManager.decodeSettingsBool(
                    AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE,
                    false,
                ),
            ) { checked ->
                MmkvManager.encodeSettings(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, checked)
                // The answer depends on this switch, so the answer is re-fetched rather than left
                // on screen contradicting the control above it.
                checkForUpdates()
            },
        )
    }

    private fun includePreRelease(): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, false)

    // ------------------------------------------------------------------ work

    private fun checkForUpdates() {
        job?.cancel()
        offer = null
        downloaded = null
        showChecking()
        job = lifecycleScope.launch {
            try {
                val result = UpdateCheckerManager.checkForUpdate(includePreRelease())
                if (result.hasUpdate) {
                    offer = result
                    showUpdateAvailable(result)
                } else {
                    showUpToDate()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isActive) return@launch
                logCheckOutcome(e)
                showFailure(e, retry = ::checkForUpdates)
            }
        }
    }

    /**
     * A DEAD END IS NOT ALWAYS A FAULT, and «Журнал» has to be able to tell the difference.
     *
     * Every outcome of the check used to be written as an ERROR with a stack trace under it —
     * including «релизов ещё нет», which is the honest reply of a repository that has published
     * none. The owner opened the log, saw a red stack and reported the app as broken. The screen is
     * unchanged (it says the same sentence, with the same next step); what changes is that an
     * expected answer is one INFO line naming it, and only a real failure keeps the error and the
     * trace. See [UpdateFailure.Reason.expected].
     */
    private fun logCheckOutcome(e: Exception) {
        val reason = (e as? UpdateFailure)?.reason
        if (reason != null && reason.expected) {
            LogUtil.i(AppConfig.TAG, "Update check: nothing to offer (${reason.name})")
        } else {
            LogUtil.e(AppConfig.TAG, "Update check failed", e)
        }
    }

    private fun startDownload() {
        val result = offer ?: return checkForUpdates()
        job?.cancel()
        showDownloading()
        job = lifecycleScope.launch {
            try {
                val file = UpdateCheckerManager.downloadUpdate(
                    context = this@CheckUpdateActivity,
                    result = result,
                    onProgress = progressReporter(),
                )
                downloaded = file
                if (canInstall()) install(file) else showNeedsInstallPermission()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A download the user cancelled is not a download that failed: the state it should
                // land on was already painted by «Отменить».
                if (!isActive) return@launch
                LogUtil.e(AppConfig.TAG, "Update download failed", e)
                showFailure(e, retry = ::startDownload)
            }
        }
    }

    /**
     * Bridges the IO-thread byte counter to the bar without one coroutine per 8 KiB chunk: a 30 MB
     * APK would post ~4000 of them, all to repaint the same two views. The report is throttled to
     * a change the user can actually see — one per percent, or per half-megabyte when the server
     * sent no length to divide by.
     */
    private fun progressReporter(): (Long, Long) -> Unit {
        var lastTick = -1L
        return { read, total ->
            val tick = if (total > 0) read * 100 / total else read / PROGRESS_STEP_BYTES
            if (tick != lastTick) {
                lastTick = tick
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) renderProgress(read, total)
                }
            }
        }
    }

    /**
     * Since API 26 an app may only launch the package installer if the user has allowed it as an
     * install source. Asking after the download rather than before is deliberate: the permission
     * screen is a detour, and a user who has not decided to update yet should not be sent on one.
     */
    private fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

    private fun openInstallPermissionSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:$packageName"))
        try {
            awaitingInstallPermission = true
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            awaitingInstallPermission = false
            LogUtil.e(AppConfig.TAG, "No unknown-app-sources screen on this device", e)
            showReason(UpdateFailure.Reason.INSTALLER_UNAVAILABLE, retry = ::startDownload)
        }
    }

    /**
     * Hands the verified APK to the system installer through the FileProvider the app already
     * declares (`${applicationId}.cache`, `@xml/cache_paths`) - a `file://` URI is refused with
     * `FileUriExposedException` on API 24 and above, which is every device this app supports.
     */
    private fun install(apk: File) {
        val uri = try {
            FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.cache", apk)
        } catch (e: IllegalArgumentException) {
            LogUtil.e(AppConfig.TAG, "Update file is outside the shared paths", e)
            showReason(UpdateFailure.Reason.INSTALLER_UNAVAILABLE, retry = ::startDownload)
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
            showReadyToInstall()
        } catch (e: ActivityNotFoundException) {
            LogUtil.e(AppConfig.TAG, "No package installer on this device", e)
            showReason(UpdateFailure.Reason.INSTALLER_UNAVAILABLE, retry = ::startDownload)
        }
    }

    // -------------------------------------------------------------- states

    private fun showChecking() {
        clearStates()
        binding.checking.isVisible = true
    }

    private fun showUpToDate() {
        clearStates()
        EmptyStateBinder.bind(
            root = binding.emptyState.root,
            glyph = R.drawable.ic_check_update_24dp,
            title = getString(R.string.upd_none_title),
            line = getString(R.string.upd_none_line),
        )
    }

    private fun showUpdateAvailable(result: CheckUpdateResult) {
        clearStates()
        EmptyStateBinder.bind(
            root = binding.emptyState.root,
            glyph = R.drawable.ic_cloud_download_24dp,
            title = getString(R.string.upd_found_title, result.latestVersion.orEmpty()),
            line = getString(R.string.upd_found_line),
        )
        showReleaseNotes(result.releaseNotes)
        primaryAction(R.string.upd_action_download) { startDownload() }
    }

    private fun showDownloading() {
        clearStates()
        // The bar starts indeterminate because the size is not known until the first chunk, and it
        // is set here — while `downloading` is still GONE — on purpose: Material's
        // BaseProgressIndicator throws IllegalStateException if it is put back into indeterminate
        // mode while it is visible to the user. Going the other way, once the length arrives, is
        // allowed at any time.
        binding.downloadProgress.isIndeterminate = true
        binding.downloadDetail.text = ""
        binding.downloading.isVisible = true
        // Cancelling is not the screen's job, so it is the quiet tier - but it is always offered,
        // because a progress bar with no way out is a trap on a metered connection.
        tertiaryAction(R.string.upd_action_cancel) {
            job?.cancel()
            offer?.let { showUpdateAvailable(it) } ?: checkForUpdates()
        }
    }

    /**
     * The real figures when the server sent a length, and honest silence when it did not: a
     * percentage with no denominator is decoration, so the bar keeps moving without claiming to
     * know how far along it is.
     */
    private fun renderProgress(read: Long, total: Long) {
        val bar = binding.downloadProgress
        if (total > 0) {
            if (bar.isIndeterminate) bar.isIndeterminate = false
            bar.setProgressCompat(((read * 100) / total).toInt().coerceIn(0, 100), true)
            binding.downloadDetail.text =
                getString(R.string.upd_downloading_size, megabytes(read), megabytes(total))
        } else {
            binding.downloadDetail.text =
                getString(R.string.upd_downloading_done, megabytes(read))
        }
    }

    private fun showReadyToInstall() {
        clearStates()
        EmptyStateBinder.bind(
            root = binding.emptyState.root,
            glyph = R.drawable.ic_cloud_download_24dp,
            title = getString(R.string.upd_ready_title),
            line = getString(R.string.upd_ready_line),
        )
        primaryAction(R.string.upd_action_install) { downloaded?.let { install(it) } }
    }

    private fun showNeedsInstallPermission() {
        clearStates()
        EmptyStateBinder.bind(
            root = binding.emptyState.root,
            glyph = R.drawable.ic_dl_info,
            title = getString(R.string.upd_permission_title),
            line = getString(R.string.upd_permission_line),
        )
        primaryAction(R.string.upd_action_permission) { openInstallPermissionSettings() }
    }

    private fun showFailure(e: Exception, retry: () -> Unit) {
        val reason = (e as? UpdateFailure)?.reason ?: UpdateFailure.Reason.UNREACHABLE
        showReason(reason, retry)
    }

    /**
     * One place where a cause becomes a screen. Each row is a real dead end with its own next step:
     * a retry where retrying can work, the download page where it cannot, and nothing at all where
     * there is genuinely nothing for the user to do.
     */
    private fun showReason(reason: UpdateFailure.Reason, retry: () -> Unit) {
        clearStates()
        val glyph: Int
        val title: Int
        val line: Int
        when (reason) {
            UpdateFailure.Reason.NO_CHANNEL -> {
                glyph = R.drawable.ic_dl_info
                title = R.string.upd_error_no_channel_title
                line = R.string.upd_error_no_channel
            }
            UpdateFailure.Reason.UNREACHABLE -> {
                glyph = R.drawable.ic_dl_info
                title = R.string.upd_error_title
                line = R.string.upd_error_unreachable
            }
            UpdateFailure.Reason.NO_RELEASE -> {
                glyph = R.drawable.ic_check_update_24dp
                title = R.string.upd_none_title
                line = R.string.upd_error_no_release
            }
            UpdateFailure.Reason.NO_ASSET -> {
                glyph = R.drawable.ic_dl_info
                title = R.string.upd_error_no_channel_title
                line = R.string.upd_error_no_asset
            }
            UpdateFailure.Reason.DOWNLOAD_FAILED -> {
                glyph = R.drawable.ic_dl_info
                title = R.string.upd_error_download_title
                line = R.string.upd_error_download
            }
            UpdateFailure.Reason.FOREIGN_PACKAGE -> {
                glyph = R.drawable.ic_dl_info
                title = R.string.upd_error_foreign_title
                line = R.string.upd_error_foreign
            }
            UpdateFailure.Reason.NOT_NEWER -> {
                glyph = R.drawable.ic_dl_info
                title = R.string.upd_error_not_newer_title
                line = R.string.upd_error_not_newer
            }
            UpdateFailure.Reason.INSTALLER_UNAVAILABLE -> {
                glyph = R.drawable.ic_dl_info
                title = R.string.upd_error_installer_title
                line = R.string.upd_error_installer
            }
        }
        bindState(glyph, title, line)

        when (reason) {
            // Retrying can genuinely change the answer.
            UpdateFailure.Reason.UNREACHABLE,
            UpdateFailure.Reason.NO_RELEASE,
            UpdateFailure.Reason.DOWNLOAD_FAILED ->
                tertiaryAction(R.string.editor_retry) { retry() }

            // Retrying cannot; the release page can. Offering it here is what keeps the answer
            // and the next step in the same place.
            UpdateFailure.Reason.NO_ASSET,
            UpdateFailure.Reason.FOREIGN_PACKAGE,
            UpdateFailure.Reason.INSTALLER_UNAVAILABLE ->
                tertiaryAction(R.string.upd_action_open_releases) {
                    Utils.openUri(this, AppConfig.APP_RELEASES_URL)
                }

            // Nothing the user can do, so nothing is offered. A button that cannot work is worse
            // than no button.
            UpdateFailure.Reason.NO_CHANNEL,
            UpdateFailure.Reason.NOT_NEWER -> Unit
        }
    }

    // --------------------------------------------------------------- slots

    private fun bindState(@DrawableRes glyph: Int, @StringRes title: Int, @StringRes line: Int) {
        EmptyStateBinder.bind(
            root = binding.emptyState.root,
            glyph = glyph,
            title = getString(title),
            line = getString(line),
        )
    }

    /** Takes every state down, so a bind never has to remember what the last one left up. */
    private fun clearStates() {
        binding.checking.isVisible = false
        binding.downloading.isVisible = false
        binding.tvReleaseNotes.isVisible = false
        binding.actionArea.isVisible = false
        actionPrimary.isVisible = false
        actionTertiary.isVisible = false
        actionPrimary.clearClick()
        actionTertiary.clearClick()
        EmptyStateBinder.hide(binding.emptyState.root)
    }

    private fun primaryAction(@StringRes label: Int, action: () -> Unit) =
        showAction(actionPrimary, label, Haptic.PRESS, action)

    private fun tertiaryAction(@StringRes label: Int, action: () -> Unit) =
        showAction(actionTertiary, label, Haptic.NONE, action)

    private fun showAction(
        button: MaterialButton,
        @StringRes label: Int,
        haptic: Haptic,
        action: () -> Unit,
    ) {
        button.setText(label)
        button.isVisible = true
        button.onSingleClick(haptic) { action() }
        binding.actionArea.isVisible = true
    }

    private fun showReleaseNotes(notes: String?) {
        val text = notes?.trim().orEmpty()
        binding.tvReleaseNotes.text = text
        binding.tvReleaseNotes.isVisible = text.isNotEmpty()
    }

    /**
     * Megabytes with one decimal. The unit and the decimal separator live in the string resource,
     * so the caption reads «12,4 МБ из 31,0 МБ» rather than being assembled in Kotlin. Bytes and
     * kilobytes are not offered: an APK is tens of megabytes, and a caption that changes unit
     * while it counts is harder to read, not more precise.
     */
    private fun megabytes(bytes: Long): Float = bytes / 1024f / 1024f

    private companion object {
        /** How often an unmeasurable download repaints: every half megabyte. */
        const val PROGRESS_STEP_BYTES = 512L * 1024L
    }
}
