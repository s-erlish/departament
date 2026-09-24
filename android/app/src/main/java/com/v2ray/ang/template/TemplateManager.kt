package com.v2ray.ang.template

import android.util.Base64
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.MmkvManager

/**
 * Host-bound "hidden" JSON config templates, à la Happ / Remnawave / 3x-ui.
 *
 * A subscription owned by the operator can be marked hidden (via a `profile-hidden`
 * response header or an in-body `#profile-hidden:` directive). Its full Xray-JSON
 * config template is then:
 *  - stored obfuscated/encrypted at rest (never plaintext) — see [wrapRawForStorage];
 *  - applied as-authored at connect time by the config builder, which reads the raw
 *    back through [decodeRuntimeRaw] (transparent decrypt);
 *  - hidden from the user: every imported profile is stamped [ProfileItem.locked],
 *    which the UI uses to block share / QR / show-config / edit / export.
 *
 * This is the single public entry point for the feature so that edits to shared
 * files (AngConfigManager, CoreConfigManager, the UI) stay minimal and localized.
 *
 * Honesty note: hiding here is obfuscation + UX gating, NOT DRM. See [TemplateCrypto].
 */
object TemplateManager {

    // Storage markers. Normal (non-locked) raw configs are stored verbatim with NO
    // prefix, so the non-locked path is byte-for-byte unchanged.
    private const val ENC_PREFIX = "dpt-enc:v1:" // Keystore AES-GCM ciphertext
    private const val OBF_PREFIX = "dpt-obf:v1:" // base64 obfuscation fallback (no Keystore)

    // How many leading lines to scan for an in-body directive block.
    private const val DIRECTIVE_SCAN_LINES = 24

    //region locked-state API

    /**
     * The hook referenced by the server-actions bottom sheet
     * (`ServerActionsSheet.isLocked` → `return TemplateManager.isLocked(profile)`).
     */
    fun isLocked(profile: ProfileItem): Boolean = profile.locked

    /**
     * Whether a subscription is an operator-managed hidden subscription.
     */
    fun isLocked(sub: SubscriptionItem): Boolean = sub.locked

    /**
     * Convenience overload that resolves the profile for [guid] first.
     */
    fun isLocked(guid: String): Boolean =
        MmkvManager.decodeServerConfig(guid)?.let { isLocked(it) } == true

    //endregion

    //region ingestion / lock-state resolution

    /**
     * Applies the hidden/locked signal carried by a subscription fetch to [sub].
     *
     * The signal comes from the response header value [hiddenHeader] (e.g. the
     * `profile-hidden`/`hidden` header) or, failing that, an in-body `#profile-hidden:`
     * directive at the top of [body]. Absent signal ⇒ [sub] is left untouched (so
     * ordinary subscriptions never become locked).
     *
     * @return true if [sub].locked was changed (caller should persist it).
     */
    fun applyLockState(sub: SubscriptionItem, hiddenHeader: String?, body: String?): Boolean {
        val signal = truthy(hiddenHeader) ?: resolveBodyDirective(body) ?: return false
        if (sub.locked == signal) return false
        sub.locked = signal
        return true
    }

    /**
     * Maps a directive value to a tri-state: true/false/unknown(null).
     */
    private fun truthy(value: String?): Boolean? {
        return when (value?.trim()?.lowercase()) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> null
        }
    }

    /**
     * Scans the leading `#key: value` directive block of a subscription body for a
     * hidden/locked directive. Cheap: stops at the first non-directive, non-blank line.
     */
    private fun resolveBodyDirective(body: String?): Boolean? {
        if (body.isNullOrEmpty()) return null
        for (rawLine in body.lineSequence().take(DIRECTIVE_SCAN_LINES)) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (!line.startsWith("#")) break // directive block is at the very top
            val parts = line.removePrefix("#").split(":", limit = 2)
            if (parts.size == 2) {
                when (parts[0].trim().lowercase()) {
                    "profile-hidden", "hidden", "locked" -> return truthy(parts[1])
                }
            }
        }
        return null
    }

    //endregion

    //region storage wrapping

    /**
     * Wraps a raw config for storage. For [locked] templates the value is encrypted
     * with the Keystore AES key (or, if the Keystore is unavailable, base64-obfuscated
     * with a clearly labelled prefix). Non-locked values are returned unchanged, so the
     * existing plaintext storage path is preserved exactly.
     */
    fun wrapRawForStorage(rawJson: String, locked: Boolean): String {
        if (!locked) return rawJson
        TemplateCrypto.encrypt(rawJson)?.let { return ENC_PREFIX + it }
        // Honest fallback when the Keystore is unavailable: obfuscation only.
        return OBF_PREFIX + Base64.encodeToString(rawJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    /**
     * Inverse of [wrapRawForStorage]. A value with no known prefix (i.e. an ordinary,
     * non-locked config) is returned unchanged.
     */
    fun unwrapStoredRaw(stored: String?): String? {
        if (stored == null) return null
        return when {
            stored.startsWith(ENC_PREFIX) -> TemplateCrypto.decrypt(stored.removePrefix(ENC_PREFIX))
            stored.startsWith(OBF_PREFIX) -> try {
                String(Base64.decode(stored.removePrefix(OBF_PREFIX), Base64.NO_WRAP), Charsets.UTF_8)
            } catch (e: Exception) {
                null
            }
            else -> stored
        }
    }

    //endregion

    /**
     * Reads and (if needed) decrypts the runtime raw config for [guid]. This is the
     * apply point's storage accessor: the decrypted template — with all of its
     * routing/DNS/obfuscation rules — flows into the config builder unchanged.
     *
     * For non-locked profiles this is identical to `MmkvManager.decodeServerRaw(guid)`.
     */
    fun decodeRuntimeRaw(guid: String): String? = unwrapStoredRaw(MmkvManager.decodeServerRaw(guid))
}
