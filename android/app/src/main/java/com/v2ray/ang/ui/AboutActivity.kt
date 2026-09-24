package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.DimenRes
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.databinding.ActivityAboutBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.ui.component.RowBinder
import com.v2ray.ang.ui.component.SubPage
import com.v2ray.ang.ui.component.ToolbarBinder
import com.v2ray.ang.ui.component.themeColor
import com.v2ray.ang.util.Utils
import java.util.Locale

/**
 * A-33 `settings/about` - «О приложении». H3 header, R1 rhythm.
 *
 * Beyond the restyle, this screen is where three built-but-unreachable activities get an entry
 * point. `LogcatActivity`, `CheckUpdateActivity` and `UrlSchemeListActivity` are all declared in the
 * manifest and launched by nothing in the codebase, which is why the product shipped with no way to
 * read a log and no way to check for an update. They are rows here.
 *
 * The version and the application id stop being two centred grey labels at the bottom of a scroll
 * and become `Row.Value` rows with a copy action - they exist to be sent to support, so they have to
 * be copyable.
 */
class AboutActivity : BaseActivity() {

    private val binding by lazy { ActivityAboutBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        SubPage.installTransitions(this)
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        ToolbarBinder.bind(
            root = binding.toolbar.root,
            title = getString(R.string.about_title),
            activity = this,
        )
        ToolbarBinder.attachTo(binding.toolbar.root, binding.mainContent)

        bindFacts()
        bindLinks()
    }

    private fun bindFacts() {
        val version = "${BuildConfig.VERSION_NAME} (${CoreNativeManager.getLibVersion()})"
        RowBinder.bind(
            root = binding.rowVersion.root,
            title = getString(R.string.about_version),
            value = version,
            valueIsNumeric = true,
            trailing = RowBinder.Trailing.IconAction(
                icon = R.drawable.ic_copy,
                contentDescription = getString(R.string.about_version_cd),
                onClick = { copy(version) },
            ),
        )
        RowBinder.bind(
            root = binding.rowPackage.root,
            title = getString(R.string.about_package),
            value = BuildConfig.APPLICATION_ID,
            trailing = RowBinder.Trailing.IconAction(
                icon = R.drawable.ic_copy,
                contentDescription = getString(R.string.about_package_cd),
                onClick = { copy(BuildConfig.APPLICATION_ID) },
            ),
        )
    }

    private fun bindLinks() {
        RowBinder.bind(
            root = binding.rowCheckUpdate.root,
            title = getString(R.string.about_check_update),
            glyph = R.drawable.ic_check_update_24dp,
            onClick = { open(CheckUpdateActivity::class.java) },
        )
        RowBinder.bind(
            root = binding.rowSourceCode.root,
            title = getString(R.string.about_source_code),
            glyph = R.drawable.ic_source_code_24dp,
            onClick = { Utils.openUri(this, AppConfig.APP_URL) },
        )
        RowBinder.bind(
            root = binding.rowFeedback.root,
            title = getString(R.string.about_feedback),
            glyph = R.drawable.ic_feedback_24dp,
            onClick = { Utils.openUri(this, AppConfig.APP_ISSUES_URL) },
        )
        RowBinder.bind(
            root = binding.rowTgChannel.root,
            title = getString(R.string.about_telegram),
            glyph = R.drawable.ic_telegram_24dp,
            onClick = { Utils.openUri(this, AppConfig.TG_CHANNEL_URL) },
        )
        RowBinder.bind(
            root = binding.rowLicenses.root,
            title = getString(R.string.about_licenses),
            glyph = R.drawable.license_24px,
            onClick = { showLicenses() },
        )
        RowBinder.bind(
            root = binding.rowPrivacyPolicy.root,
            title = getString(R.string.about_privacy),
            glyph = R.drawable.ic_privacy_24dp,
            onClick = { Utils.openUri(this, AppConfig.APP_PRIVACY_POLICY) },
        )
    }

    private fun open(target: Class<*>) = SubPage.open(this, Intent(this, target))

    private fun copy(value: String) {
        Utils.setClipboard(this, value)
        toast(R.string.notice_copied)
    }

    /**
     * The licence text is a bundled HTML asset, so it stays in a WebView. What changed is the frame
     * around it: a Russian title and a verb on the button instead of the English «Open source
     * licenses» / «OK» pair the upstream dialog shipped (§9.2 - no «OK» anywhere).
     *
     * **The asset arrives wearing someone else's theme.** It is a generated third-party notice
     * file and it ships its own stylesheet - `body{background:#ffffff;color:#000000}`,
     * `a{color:#0000EE}`, `pre{background:#eeeeee}`, and a `prefers-color-scheme: dark` block on
     * `#121212` / `#E0E0E0` / `#BB86FC` / `#333333`. Loaded straight, that stylesheet is what
     * paints the dialog: a grey slab of monospace inside a near-black surface, links in Material's
     * stock purple, and a palette that answers the SYSTEM's dark mode rather than the app's own
     * theme - so a user reading it in the light theme on a dark phone gets the dark page.
     *
     * The notices themselves are a legal artefact and are not edited. The page is restyled instead,
     * at load time, from the colours the dialog is already wearing: [licenceStyle] resolves
     * `?attr/`-level values off the dialog's own themed context and appends one stylesheet after
     * the asset's, where equal specificity makes source order decide. Attributes and not
     * `@color/...`, because `ThemeOverlay.Mono` overrides ATTRIBUTES and cannot reach a colour
     * resource - a fix written against the resources would be right in the blue and light themes
     * and leave the black-and-white one blue.
     */
    private fun showLicenses() {
        val builder = AlertDialog.Builder(this)
        // The dialog's own context, i.e. this Activity's theme with ThemeOverlay.Departament.Dialog
        // laid over it. Everything below - the title's ramp role, the page's palette, the WebView's
        // fill - is resolved through it, so all three themes are answered by one code path.
        val themed = builder.context

        val title = LayoutInflater.from(themed)
            .inflate(R.layout.view_licenses_title, null) as TextView
        title.setText(R.string.about_licenses)

        val webView = WebView(themed)
        // A WebView paints white until the page's own background lands. Straight from the tile,
        // that is a full-screen white flash in a dark dialog.
        webView.setBackgroundColor(
            webView.themeColor(com.google.android.material.R.attr.colorSurfaceContainerHigh)
        )

        val page = Utils.readTextFromAssets(this, LICENSES_ASSET)
        if (page.isEmpty()) {
            // Unreadable asset: show the notices in their own colours rather than an empty dialog.
            // A licence page nobody can read is the one failure that actually matters here.
            webView.loadUrl(LICENSES_URL)
        } else {
            webView.loadDataWithBaseURL(
                LICENSES_BASE_URL,
                page.withStylesheet(licenceStyle(webView)),
                MIME_HTML,
                CHARSET,
                null,
            )
        }

        val dialog = builder
            // NOT setTitle. ?attr/materialAlertDialogTitlePanelStyle starts the platform title
            // @dimen/abc_dialog_padding_top_material - 18dp - below the top of the window, and
            // @drawable/bg_dialog rounds that window by @dimen/radius_card, 20: the panel was
            // measured for a square-cornered window, so the title's first line lands inside the
            // band the corner is still cutting away. The same panel also hides the 16dp
            // @id/titleDividerNoCustom under the title whenever the dialog carries a custom view,
            // which this one does. @layout/view_licenses_title owns both numbers.
            .setCustomTitle(title)
            .setView(webView)
            .setPositiveButton(R.string.editor_close) { d, _ -> d.dismiss() }
            .create()
        dialog.setOnDismissListener {
            // A WebView outlives the window it was shown in unless it is told not to, and it holds
            // this Activity through its own context. «О приложении» is a screen the same user opens
            // again, so the cost compounds; detach first, because destroy() on an attached WebView
            // is undefined.
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        dialog.show()
    }

    /**
     * The app's palette, as one stylesheet the licence page can wear.
     *
     * Five values, all resolved from [view]'s theme so the blue, the light and the mono themes each
     * get their own and none of them is written down twice. `color-scheme` is stated from the
     * measured luminance of the surface rather than from `prefers-color-scheme`, because the app's
     * theme is a setting inside the app: a light app on a dark phone must not get a dark page, and
     * the WebView's own scrollbar has to follow the app too.
     *
     * The face stays `sans-serif`. Golos Text lives in `res/font` and a WebView can only reach it
     * through `file:///android_res/`, which means turning file access on for a page that has no
     * other reason to want it - not a trade worth making for a licence list.
     */
    private fun licenceStyle(view: View): String {
        val surface = view.themeColor(com.google.android.material.R.attr.colorSurfaceContainerHigh)
        val onSurface = view.themeColor(com.google.android.material.R.attr.colorOnSurface)
        val muted = view.themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        val accent = view.themeColor(androidx.appcompat.R.attr.colorPrimary)
        val scheme = if (ColorUtils.calculateLuminance(surface) < LUMINANCE_MIDPOINT) "dark" else "light"
        val gutter = dp(R.dimen.space_24)
        val gap = dp(R.dimen.space_16)

        // The <pre> blocks are the reason this page overflowed. The asset draws them
        // `display:inline-block`, which is shrink-to-fit around a 72-column line of monospace and
        // so wider than the dialog, and `word-break:break-word`, which then splits ordinary words
        // down the middle. A block box is exactly as wide as the dialog and `word-break:normal`
        // puts the breaks back on the spaces, leaving `overflow-wrap` to deal with the one thing
        // that genuinely cannot fit a line - a long URL.
        return """
            |<meta name="viewport" content="width=device-width, initial-scale=1">
            |<style>
            |:root { color-scheme: $scheme; }
            |html, body { background: ${hex(surface)}; }
            |body { margin: 0; padding: ${gutter}px; color: ${hex(onSurface)};
            |  font-family: sans-serif; font-size: 14px; line-height: 20px;
            |  overflow-wrap: break-word; }
            |h3 { margin: 0 0 ${gap}px; font-size: 16px; line-height: 20px; color: ${hex(onSurface)}; }
            |ul { margin: 0 0 ${gap}px; padding-left: ${gap}px; }
            |li { margin: 0 0 ${dp(R.dimen.space_8)}px; }
            |a { color: ${hex(accent)}; }
            |dl { margin: ${dp(R.dimen.space_4)}px 0 0; }
            |dt, dd { margin: 0; color: ${hex(muted)}; font-size: 13px; line-height: 18px; }
            |pre { display: block; box-sizing: border-box; max-width: 100%;
            |  margin: 0 0 ${gap}px; padding: 0;
            |  background: transparent; color: ${hex(onSurface)};
            |  font-size: 12px; line-height: 18px;
            |  white-space: pre-wrap; word-break: normal; overflow-wrap: break-word; }
            |</style>
        """.trimMargin()
    }

    /**
     * Puts [style] last in the document's head, which is all it takes to win: the asset's own rules
     * and these are the same specificity, so the later block decides - including over the asset's
     * `@media (prefers-color-scheme: dark)`, which is a media query and not a specificity bump.
     */
    private fun String.withStylesheet(style: String): String {
        val head = indexOf(HEAD_CLOSE, ignoreCase = true)
        return if (head < 0) style + this else substring(0, head) + style + substring(head)
    }

    /** A dimen token in CSS pixels. A WebView's CSS pixel is a dp, so the number carries across. */
    private fun dp(@DimenRes token: Int): Int =
        (resources.getDimension(token) / resources.displayMetrics.density).toInt()

    private fun hex(@ColorInt color: Int): String =
        String.format(Locale.ROOT, "#%06X", 0xFFFFFF and color)

    private companion object {
        const val LICENSES_ASSET = "open_source_licenses.html"
        const val LICENSES_URL = "file:///android_asset/open_source_licenses.html"
        const val LICENSES_BASE_URL = "file:///android_asset/"
        const val HEAD_CLOSE = "</head>"
        const val MIME_HTML = "text/html"
        const val CHARSET = "utf-8"
        const val LUMINANCE_MIDPOINT = 0.5
    }
}
