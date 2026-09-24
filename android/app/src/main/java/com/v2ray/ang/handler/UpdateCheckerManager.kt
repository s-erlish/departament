package com.v2ray.ang.handler

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.GitHubRelease
import com.v2ray.ang.dto.UpdateFailure
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.extension.concatUrl
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The self-updater's data half: ask **our** release feed what the newest departament build is, and
 * fetch it.
 *
 * The rule the whole object exists to enforce is that this app never offers the user another
 * application. It used to poll `2dust/v2rayNG` and hand back whatever APK that project had
 * published (M-07); the feed is [AppConfig.APP_API_URL] now, and provenance is re-checked at every
 * step that could still go wrong — the asset name, the parsed package name, and the version code —
 * so a mistyped constant or a redirected host cannot turn into an install.
 *
 * Every failure is a [UpdateFailure], not an exception message: the screen has to be able to say
 * *why* in the product's voice and put the retry next to the reason (G2), and a raw `e.message` is
 * exactly what this screen was rebuilt to stop showing.
 */
object UpdateCheckerManager {

    /** What our own release assets are called — `app/build.gradle.kts`, `applicationVariants.all`. */
    private const val ASSET_PREFIX = "departament_"
    private const val ASSET_SUFFIX = ".apk"
    private const val FDROID_MARKER = "-fdroid"

    /**
     * The two markers `app/build.gradle.kts` puts in a filename when the artefact does not carry a
     * release identity. The release workflow (`.github/workflows/android.yml`) refuses to publish either, and this is the second
     * net: an unsigned APK installs nowhere, and a debug-signed one is a different signing identity
     * — Android answers both with «Приложение не установлено», so neither is ever an offer.
     */
    private val UNINSTALLABLE_MARKERS = listOf("-unsigned", "-debugsigned")

    private const val TIMEOUT_MS = 15000

    /** What GitHub answers for a repository that has published no release. Not an error. */
    private const val HTTP_NOT_FOUND = 404
    private const val HTTP_NO_CONTENT = 204

    /** Where a downloaded update lives. Under `cacheDir`, which is what `@xml/cache_paths` shares. */
    private const val DOWNLOAD_DIR = "updates"

    /**
     * Asks the feed whether there is a newer departament build than this one.
     *
     * @param includePreRelease when true the newest release of any kind wins; otherwise only the
     *   one GitHub marks `latest`.
     * @throws UpdateFailure with the reason, never a bare exception.
     */
    suspend fun checkForUpdate(includePreRelease: Boolean = false): CheckUpdateResult =
        withContext(Dispatchers.IO) {
            val feed = AppConfig.APP_API_URL
            if (feed.isBlank()) throw UpdateFailure(UpdateFailure.Reason.NO_CHANNEL)

            val url = if (includePreRelease) feed else feed.concatUrl("latest")
            val response = fetch(url)

            val release = parse(response, includePreRelease)
                ?: throw UpdateFailure(UpdateFailure.Reason.NO_RELEASE)

            val latestVersion = release.tagName.removePrefix("v").trim()
            LogUtil.i(
                AppConfig.TAG,
                "Update feed ${AppConfig.APP_RELEASE_REPO}: $latestVersion " +
                    "(installed ${BuildConfig.VERSION_NAME})"
            )

            if (compareVersions(latestVersion, BuildConfig.VERSION_NAME) <= 0) {
                return@withContext CheckUpdateResult(hasUpdate = false)
            }

            // A newer version with nothing this device can run is not an update offer — saying
            // «доступна версия X» and then having no button is the dead end this reports instead.
            val asset = selectAsset(release, Build.SUPPORTED_ABIS)
                ?: throw UpdateFailure(UpdateFailure.Reason.NO_ASSET)

            CheckUpdateResult(
                hasUpdate = true,
                latestVersion = latestVersion,
                releaseNotes = release.body,
                downloadUrl = asset.browserDownloadUrl,
                assetName = asset.name,
                isPreRelease = release.prerelease,
            )
        }

    /**
     * Downloads the offered APK into the app's own cache and proves it is an upgrade to *this*
     * application before anyone is asked to install it.
     *
     * @param onProgress bytes read / total, or total ≤ 0 when the server sent no length. Called on
     *   the IO dispatcher; the caller marshals to the main thread.
     * @return the verified file, ready for `FileProvider`.
     * @throws UpdateFailure with the reason.
     */
    suspend fun downloadUpdate(
        context: Context,
        result: CheckUpdateResult,
        onProgress: (Long, Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val url = result.downloadUrl ?: throw UpdateFailure(UpdateFailure.Reason.NO_ASSET)

        val dir = File(context.cacheDir, DOWNLOAD_DIR)
        // One file at a time: a half-written APK from an interrupted attempt must never be the
        // thing the installer opens on the next try.
        dir.deleteRecursively()
        if (!dir.mkdirs()) throw UpdateFailure(UpdateFailure.Reason.DOWNLOAD_FAILED)

        val target = File(dir, result.assetName ?: "departament-update.apk")
        val ok = HttpUtil.downloadToFile(
            request = UrlContentRequest(url = url, timeout = TIMEOUT_MS),
            targetFile = target,
            // `downloadToFile` reads in a blocking loop, which cancelling a coroutine cannot
            // interrupt on its own. Checking here is what actually stops the transfer when the
            // user presses «Отменить», instead of leaving a dead download pulling megabytes.
            onProgress = { read, total ->
                coroutineContext.ensureActive()
                onProgress(read, total)
            },
        )
        // Asked again after the call because `downloadToFile` catches everything the loop throws,
        // cancellation included: without this, «Отменить» would surface as «не удалось скачать».
        coroutineContext.ensureActive()
        if (!ok || !target.exists() || target.length() == 0L) {
            target.delete()
            throw UpdateFailure(UpdateFailure.Reason.DOWNLOAD_FAILED)
        }

        verify(context, target)
        target
    }

    /**
     * The last and strictest provenance check, and the only one that reads the artefact itself.
     *
     * Refuses anything that is not this application id, and anything whose `versionCode` does not
     * exceed the installed one. The second half is not pedantry: the shipped code is
     * `1_000_000 × ABI rank + base` (`app/build.gradle.kts`), so a release that bumps `versionName`
     * without bumping `versionCode` produces an APK Android rejects outright
     * (INSTALL_FAILED_VERSION_DOWNGRADE, shown to the user as «Приложение не установлено»). Better
     * to say so before the download is handed over than to let the system installer fail in a
     * dialog that explains nothing.
     */
    private fun verify(context: Context, apk: File) {
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
        if (info == null || info.packageName != BuildConfig.APPLICATION_ID) {
            LogUtil.w(
                AppConfig.TAG,
                "Refusing update: ${apk.name} declares ${info?.packageName} " +
                    "(expected ${BuildConfig.APPLICATION_ID})"
            )
            apk.delete()
            throw UpdateFailure(UpdateFailure.Reason.FOREIGN_PACKAGE)
        }

        val offered = PackageInfoCompat.getLongVersionCode(info)
        val installed = installedVersionCode(context)
        if (installed > 0 && offered <= installed) {
            LogUtil.w(AppConfig.TAG, "Refusing update: versionCode $offered <= installed $installed")
            apk.delete()
            throw UpdateFailure(UpdateFailure.Reason.NOT_NEWER)
        }
    }

    /**
     * The version code Android actually has on disk. `BuildConfig.VERSION_CODE` is the *variant's*
     * code (731) rather than the per-ABI override the manifest carries, so the package manager is
     * the only source that can be compared with a downloaded APK's.
     */
    private fun installedVersionCode(context: Context): Long = try {
        PackageInfoCompat.getLongVersionCode(
            context.packageManager.getPackageInfo(context.packageName, 0)
        )
    } catch (e: PackageManager.NameNotFoundException) {
        LogUtil.w(AppConfig.TAG, "Own package not found while checking the update", e)
        0L
    }

    // ------------------------------------------------------------------ feed

    /**
     * Asks the feed, and tells the three answers apart.
     *
     * **«РЕЛИЗОВ ЕЩЁ НЕТ» IS AN ANSWER, NOT AN OUTAGE.** GitHub replies **404** to
     * `/releases/latest` for a repository that has published none — and this project has published
     * none yet: «пока что без ключей, не релизное приложение пока». That 404 used to be flattened
     * into "no body", which became [UpdateFailure.Reason.UNREACHABLE], which the screen reported as
     * «Не удалось связаться с сервером обновлений» and `CheckUpdateActivity` dropped into «Журнал»
     * as an ERROR with a stack trace. The owner read that stack and reasonably concluded the app was
     * broken. Nothing was broken; the feed answered, correctly, that there is nothing to offer.
     *
     * Direct first, then through the local HTTP inbound — the feed may be what is blocked. But only
     * when the direct attempt got NO answer: once a server has replied with a status, asking the
     * same question again through a proxy cannot change it, and the second attempt was the reason
     * that 404 appeared in the log twice.
     *
     * @return the response body.
     * @throws UpdateFailure [UpdateFailure.Reason.NO_RELEASE] when the feed answered that it has
     *   nothing, [UpdateFailure.Reason.UNREACHABLE] when nothing answered at all.
     */
    private fun fetch(url: String): String {
        val direct = HttpUtil.getUrlOutcome(UrlContentRequest(url = url, timeout = TIMEOUT_MS))
        direct.body?.takeIf { it.isNotEmpty() }?.let { return it }
        if (direct.answered) return orFailure(direct.code)

        val proxied = HttpUtil.getUrlOutcome(
            UrlContentRequest(
                url = url,
                timeout = TIMEOUT_MS,
                httpPort = SettingsManager.getHttpPort(),
                proxyUsername = SettingsManager.getSocksUsername(),
                proxyPassword = SettingsManager.getSocksPassword(),
            )
        )
        proxied.body?.takeIf { it.isNotEmpty() }?.let { return it }
        if (proxied.answered) return orFailure(proxied.code)

        LogUtil.w(AppConfig.TAG, "Update feed ${AppConfig.APP_RELEASE_REPO}: no answer, directly or through the proxy")
        throw UpdateFailure(UpdateFailure.Reason.UNREACHABLE)
    }

    /**
     * Turns a status the feed actually answered with into the right dead end. Never returns.
     *
     * 404 is «there is no release», and so is 204/an empty 200 — a repository with nothing
     * published, which is the state this project is in until the first signed build ships. Anything
     * else (5xx, a rate limit, a gateway) is the feed failing to answer the question, which is what
     * UNREACHABLE means.
     */
    private fun orFailure(code: Int): Nothing {
        if (code == HTTP_NOT_FOUND || code == HTTP_NO_CONTENT || code in 200..299) {
            LogUtil.i(
                AppConfig.TAG,
                "Update feed ${AppConfig.APP_RELEASE_REPO} has no published release (HTTP $code)"
            )
            throw UpdateFailure(UpdateFailure.Reason.NO_RELEASE)
        }
        LogUtil.w(AppConfig.TAG, "Update feed ${AppConfig.APP_RELEASE_REPO} answered HTTP $code")
        throw UpdateFailure(UpdateFailure.Reason.UNREACHABLE)
    }

    private fun parse(response: String, includePreRelease: Boolean): GitHubRelease? =
        if (includePreRelease) {
            JsonUtil.fromJsonSafe(response, Array<GitHubRelease>::class.java)
                ?.firstOrNull { it.assets.isNotEmpty() }
        } else {
            JsonUtil.fromJsonSafe(response, GitHubRelease::class.java)
        }

    /**
     * Compares two dotted versions without trusting either of them to be well-formed.
     *
     * The old implementation was `split(".").map { it.toInt() }`, which threw
     * `NumberFormatException` on any tag a human might type — `v2.3.0-rc1`, `2.3`, `2.3.0.1` — and
     * that exception surfaced as «не удалось проверить обновления», i.e. a tagging typo looked
     * exactly like a network outage. Non-numeric tails are ignored; a missing component is 0.
     */
    private fun compareVersions(a: String, b: String): Int {
        val left = a.split('.')
        val right = b.split('.')
        for (i in 0 until maxOf(left.size, right.size)) {
            val x = left.getOrNull(i).toVersionPart()
            val y = right.getOrNull(i).toVersionPart()
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun String?.toVersionPart(): Int =
        this?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0

    /**
     * Picks the one asset this device can install, and refuses everything else.
     *
     * Matching is on our own filename grammar rather than on `contains(abi)`: upstream's asset for
     * the same ABI would satisfy a substring test, and `"x86"` is a substring of `"x86_64"`, so the
     * old filter could hand a 32-bit APK to a 64-bit device. ABIs are tried in the device's own
     * order of preference, which is what makes a 64-bit device take the 64-bit split and an
     * armeabi-v7a device still find something.
     */
    private fun selectAsset(release: GitHubRelease, abis: Array<String>): GitHubRelease.Asset? {
        val wantFdroid = BuildConfig.FLAVOR == "fdroid"
        val candidates = release.assets.filter { asset ->
            val name = asset.name
            name.startsWith(ASSET_PREFIX) &&
                name.endsWith(ASSET_SUFFIX) &&
                UNINSTALLABLE_MARKERS.none { name.contains(it) } &&
                name.contains(FDROID_MARKER) == wantFdroid
        }
        if (candidates.isEmpty()) return null

        // The device's preferred ABI first, then its fallbacks, then the fat APK.
        for (abi in abis) {
            candidates.firstOrNull { it.name.abiToken() == abi }?.let { return it }
        }
        return candidates.firstOrNull { it.name.abiToken() == "universal" }
    }

    /** `departament_2.2.1-fdroid_arm64-v8a.apk` → `arm64-v8a`. */
    private fun String.abiToken(): String =
        removeSuffix(ASSET_SUFFIX).substringAfterLast('_', "")
}
