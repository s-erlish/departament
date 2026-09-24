package com.v2ray.ang.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.v2ray.ang.R
import com.v2ray.ang.auth.AccountCache
import com.v2ray.ang.auth.AccountRepository
import com.v2ray.ang.auth.AccountSession
import com.v2ray.ang.auth.ApiError
import com.v2ray.ang.auth.AuthTokenStore
import com.v2ray.ang.auth.dto.DeviceDto
import com.v2ray.ang.auth.dto.SubInfoDto
import com.v2ray.ang.databinding.ActivityDevicesBinding
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.ui.adapter.DeviceAdapter
import com.v2ray.ang.ui.component.SubPage
import com.v2ray.ang.ui.component.ToolbarBinder
import kotlinx.coroutines.launch

/**
 * Device Management screen: lists the devices (HWIDs) bound to the user's subscription and lets
 * them remove a device after a confirmation dialog.
 *
 * The subscription UUID may be supplied via [EXTRA_REMNAWAVE_UUID]; if absent the first
 * subscription with a non-blank remnawaveUuid is resolved from the account.
 *
 * Everything on this screen goes through [AccountRepository] directly (every call returns a
 * [Result]): the device list and the delete are not on `AccountViewModel`, and neither is the
 * lookup that resolves the active подписка. The screen used to declare an `AccountViewModel`
 * anyway — the doc comment claimed it resolved the subscription, and nothing ever read the field.
 * Declaring it was not free: `by viewModels()` builds the model on first access, and the model
 * builds a repository of its own.
 */
class DeviceManagementActivity : BaseActivity() {

    private val binding by lazy { ActivityDevicesBinding.inflate(layoutInflater) }
    private val repo = AccountRepository()

    private val adapter by lazy { DeviceAdapter(::confirmDelete) }

    private var remnawaveUuid: String? = null

    /** How many devices the ACTIVE subscription reports as connected (from /subscription/all). */
    private var expectedCount: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        SubPage.installTransitions(this)
        super.onCreate(savedInstanceState)
        // Handoff README §7: the sub-page lekalo draws «Устройства» at 24sp/700 under a
        // 44dp back control with the explanation as the header's note — none of which
        // activity_base's 16sp MaterialToolbar can do, so the header lives in this
        // screen's own layout and the progress bar came with it.
        setContentView(binding.root)
        ToolbarBinder.bind(
            root = binding.toolbar.root,
            title = getString(R.string.devices_title),
            activity = this,
        )
        ToolbarBinder.attachTo(binding.toolbar.root, binding.mainContent)
        binding.toolbar.toolbarNote.text = getString(R.string.devices_subtitle)
        binding.toolbar.toolbarNote.isVisible = true

        binding.rvDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDevices.adapter = adapter
        // §7: «Своё устройство помечено "Это устройство" акцентом и не удаляется». The same id the
        // subscription fetch sends as its HWID header, so the row this marks is genuinely the row
        // the server bound to this installation.
        adapter.setOwnHwid(AuthTokenStore.deviceId())

        // Prefer the UUID passed via intent; otherwise resolve it from the already-loaded
        // AccountSession profile (no network). This lets the cache-first fast path in [loadDevices]
        // render the pre-warmed device list instantly and skips the /subscription/all round-trip
        // that otherwise ran before we could even hit the cache.
        remnawaveUuid = intent.getStringExtra(EXTRA_REMNAWAVE_UUID)?.takeIf { it.isNotBlank() }
            ?: loggedInProfileUuid()
        loadDevices()
    }

    /**
     * Loads the device list. Unless [forceRefresh] is set, a fresh (< 1h) list already in
     * [AccountCache] for the resolved UUID is rendered immediately without any network call, so
     * re-entering the screen while logged in doesn't re-fetch. [forceRefresh] (used after a delete)
     * bypasses the cache and repopulates it from the network.
     */
    private fun loadDevices(forceRefresh: Boolean = false) {
        // Fast path: UUID already known (e.g. passed via intent) and a fresh cached list exists —
        // render from memory, no coroutine, no network.
        if (!forceRefresh) {
            val knownUuid = remnawaveUuid
            if (!knownUuid.isNullOrBlank()) {
                val cached = AccountCache.getDevices(knownUuid)
                if (cached != null) {
                    hideLoading()
                    render(cached)
                    return
                }
            }
        }
        showLoading()
        showEmpty(false)
        lifecycleScope.launch {
            var uuid = remnawaveUuid
            if (uuid.isNullOrBlank()) {
                val sub = resolveActiveSub()
                uuid = sub?.remnawaveUuid?.takeIf { it.isNotBlank() }
                expectedCount = sub?.connectedDevices ?: 0
            }
            // The /all list is empty for accounts whose only subscription is the primary one, so
            // fall back to the logged-in profile's remnawaveUuid before declaring "no subscription".
            if (uuid.isNullOrBlank()) {
                uuid = loggedInProfileUuid()
            }
            if (uuid.isNullOrBlank()) {
                hideLoading()
                showEmptyState(getString(R.string.devices_error_no_subscription), isError = true)
                return@launch
            }
            val resolvedUuid: String = uuid
            remnawaveUuid = resolvedUuid

            // UUID was resolved via the network above; re-check the cache before fetching devices.
            if (!forceRefresh) {
                val cached = AccountCache.getDevices(resolvedUuid)
                if (cached != null) {
                    render(cached)
                    hideLoading()
                    return@launch
                }
            }

            repo.getDevices(resolvedUuid)
                .onSuccess { result ->
                    AccountCache.putDevices(resolvedUuid, result.devices)
                    render(result.devices)
                    // The list parsed empty but the subscription says devices ARE connected:
                    // the /client/devices response shape doesn't match. Surface the raw response
                    // so the real backend contract can be diagnosed and fixed precisely.
                    if (result.devices.isEmpty() && expectedCount > 0) {
                        showDiagnostic(
                            getString(R.string.devices_diag_empty, expectedCount),
                            result.httpCode,
                            result.rawBody,
                        )
                    }
                }
                .onFailure { error ->
                    showEmptyState(getString(R.string.devices_error_generic), isError = true)
                    val (code, detail) = httpDetailOf(error)
                    showDiagnostic(getString(R.string.devices_diag_failed), code, detail)
                }
            hideLoading()
        }
    }

    /** Resolve the active subscription (first with a non-blank remnawaveUuid) via the repo. */
    private suspend fun resolveActiveSub(): SubInfoDto? =
        repo.loadSubscriptions().getOrNull()
            ?.items
            ?.firstOrNull { it.remnawaveUuid.isNotBlank() }

    /** remnawaveUuid of the currently logged-in profile, if any (covers primary-only accounts). */
    private fun loggedInProfileUuid(): String? =
        (AccountSession.state.value as? AccountSession.AccountState.LoggedIn)
            ?.profile
            ?.remnawaveUuid
            ?.takeIf { it.isNotBlank() }

    /** Extracts a best-effort HTTP status + sanitized body from an [ApiError] for diagnostics. */
    private fun httpDetailOf(error: Throwable): Pair<Int, String?> = when (error) {
        is ApiError.Server -> error.code to error.detail
        is ApiError.Unauthorized -> 401 to error.detail
        is ApiError.NotFound -> 404 to null
        is ApiError.Gone -> 410 to null
        is ApiError.RateLimited -> 429 to null
        is ApiError.ServiceUnavailable -> 503 to null
        else -> 0 to null
    }

    /** Small dialog with the raw HTTP status + response body, safe to screenshot and share. */
    private fun showDiagnostic(summary: String, httpCode: Int, body: String?) {
        val codeLine = if (httpCode > 0) getString(R.string.devices_diag_http, httpCode) else ""
        val bodyText = body?.trim()?.takeIf { it.isNotBlank() } ?: getString(R.string.devices_diag_no_body)
        val message = listOf(summary, codeLine, bodyText).filter { it.isNotBlank() }.joinToString("\n\n")
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.devices_diag_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun render(devices: List<DeviceDto>) {
        adapter.submit(devices)
        if (devices.isEmpty()) {
            showEmptyState(getString(R.string.devices_empty), isError = false)
        } else {
            showEmpty(false)
        }
    }

    private fun confirmDelete(device: DeviceDto) {
        val name = device.deviceModel?.takeIf { it.isNotBlank() }
            ?: device.platform?.takeIf { it.isNotBlank() }
            ?: getString(R.string.devices_unknown_model)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.devices_delete_title)
            .setMessage(getString(R.string.devices_delete_message, name))
            .setPositiveButton(R.string.devices_delete_confirm) { _, _ -> deleteDevice(device) }
            .setNegativeButton(R.string.devices_delete_cancel, null)
            .show()
    }

    private fun deleteDevice(device: DeviceDto) {
        val uuid = remnawaveUuid
        if (uuid.isNullOrBlank() || device.hwid.isBlank()) {
            toastError(R.string.devices_error_delete)
            return
        }
        showLoading()
        lifecycleScope.launch {
            repo.deleteDevice(device.hwid, uuid)
                .onSuccess {
                    toastSuccess(R.string.devices_deleted)
                    // The cached list is now stale (one fewer device); drop it and re-fetch,
                    // bypassing the cache so the UI reflects the deletion immediately.
                    AccountCache.invalidateDevices(uuid)
                    // §7: the row dims, says «Отключено от подписки», and leaves 700ms later. The
                    // refetch waits for that, because submitting a fresh list mid-animation would
                    // yank the row out from under it and the sentence would never be read.
                    hideLoading()
                    adapter.release(device, binding.rvDevices) {
                        loadDevices(forceRefresh = true)
                    }
                }
                .onFailure {
                    hideLoading()
                    toastError(R.string.devices_error_delete)
                }
        }
    }

    // The progress bar moved into activity_devices.xml with the §7 header, so the base
    // layout's cached one is never inflated and showLoading/hideLoading drive this one.
    override fun showLoading() = runOnUiThread { binding.progressBar.isVisible = true }

    override fun hideLoading() = runOnUiThread { binding.progressBar.isVisible = false }

    /** Shows the empty/error panel with [message], hiding the list. */
    private fun showEmptyState(message: String, isError: Boolean) {
        binding.tvDevicesEmptyTitle.text = message
        binding.tvDevicesEmptyHint.visibility = if (isError) View.GONE else View.VISIBLE
        showEmpty(true)
    }

    private fun showEmpty(show: Boolean) {
        binding.layoutDevicesEmpty.visibility = if (show) View.VISIBLE else View.GONE
        binding.rvDevices.visibility = if (show) View.GONE else View.VISIBLE
        // §7's footnote explains what «Удалить» costs, so it goes with the rows that offer it.
        binding.tvDevicesFootnote.visibility = if (show) View.GONE else View.VISIBLE
        // Hide the "devices connected to your subscription" note while the empty/error
        // overlay is up — it contradicts "no devices" / "subscription not found". §7 moved
        // that sentence into the header, so this is the header's note now.
        binding.toolbar.toolbarNote.visibility = if (show) View.GONE else View.VISIBLE
    }

    companion object {
        const val EXTRA_REMNAWAVE_UUID = "extra_remnawave_uuid"
    }
}
