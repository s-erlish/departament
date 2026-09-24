package com.v2ray.ang.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import androidx.core.view.isVisible
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityUrlSchemeListBinding
import com.v2ray.ang.databinding.ItemEditorSectionBinding
import com.v2ray.ang.databinding.ViewCardSectionBinding
import com.v2ray.ang.databinding.ViewRowLineBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.ui.component.EmptyStateBinder
import com.v2ray.ang.ui.component.RowBinder
import com.v2ray.ang.ui.component.SubPage
import com.v2ray.ang.ui.component.ToolbarBinder
import com.v2ray.ang.util.Utils

/**
 * A-34 `settings/about/urlschemes` - «Схемы URL-адресов». H3 header, R3 rhythm.
 *
 * Reachable for the first time (it is a row on «О приложении»), and its 634-line layout is gone:
 * the nine schemes are [SCHEMES], one table, rendered into the same `view_row.xml` every other list
 * in the product uses. The nine 42dp `ImageButton`s - under the 48dp touch floor - become the row's
 * one `Button.Icon` in a 48dp box with an accessible name that states the action.
 *
 * **The empty state here is real, not decorative.** The schemes only work if this build actually
 * registered the `depv` intent filter, and a ROM or an enterprise policy can strip it. The screen
 * asks the package manager whether anything resolves `depv://open`, and if nothing does it says so
 * instead of listing nine links that would silently fail.
 */
class UrlSchemeListActivity : BaseActivity() {

    private val binding by lazy { ActivityUrlSchemeListBinding.inflate(layoutInflater) }

    /** label, uri, and the section it belongs to. Adding a scheme is one line here and nowhere else. */
    private data class Scheme(val labelRes: Int, val uri: String)

    private data class Section(val titleRes: Int, val schemes: List<Scheme>)

    override fun onCreate(savedInstanceState: Bundle?) {
        SubPage.installTransitions(this)
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        ToolbarBinder.bind(
            root = binding.toolbar.root,
            title = getString(R.string.url_scheme_list_title),
            activity = this,
        )
        ToolbarBinder.attachTo(binding.toolbar.root, binding.mainContent)

        if (schemesRegistered()) renderSchemes() else renderUnregistered()
    }

    private fun renderSchemes() {
        EmptyStateBinder.hide(binding.emptyState.root)
        binding.tvIntro.isVisible = true
        binding.tvNote.isVisible = true

        val inflater = LayoutInflater.from(this)
        binding.schemeList.removeAllViews()

        SECTIONS.forEach { section ->
            val header = ItemEditorSectionBinding.inflate(inflater, binding.schemeList, false)
            header.sectionTitle.setText(section.titleRes)
            binding.schemeList.addView(header.root)

            // The section's rows live inside ONE bordered card, which is the composition this
            // screen had at 5e8cd54 (five section cards, each holding its schemes) and the one the
            // owner asked for back on 2026-08-02. The card is inflated rather than hand-built so it
            // is the same @style/Widget.Departament.CardSection every other screen uses.
            val card = ViewCardSectionBinding.inflate(inflater, binding.schemeList, false)
            binding.schemeList.addView(card.root)

            section.schemes.forEachIndexed { index, scheme ->
                val row = ViewRowLineBinding.inflate(inflater, card.cardBody, false)
                // §6: the hairline runs the full width of the card and never above its first row.
                row.rowDivider.isVisible = index > 0
                RowBinder.bind(
                    root = row.row.root,
                    title = getString(scheme.labelRes),
                    subtitle = scheme.uri,
                    // The button IS the action, so the row is inert - one target, not two.
                    trailing = RowBinder.Trailing.IconAction(
                        icon = R.drawable.ic_copy,
                        contentDescription = getString(R.string.scheme_copy_cd),
                        onClick = {
                            Utils.setClipboard(this, scheme.uri)
                            toast(R.string.notice_copied)
                        },
                    ),
                )
                card.cardBody.addView(row.root)
            }
        }
    }

    private fun renderUnregistered() {
        binding.tvIntro.isVisible = false
        binding.tvNote.isVisible = false
        binding.schemeList.removeAllViews()
        EmptyStateBinder.bind(
            root = binding.emptyState.root,
            glyph = R.drawable.ic_hub_url_scheme,
            title = getString(R.string.scheme_empty_title),
            line = getString(R.string.scheme_empty_line),
        )
    }

    /** True when something on this device can actually open a `depv://` link. */
    private fun schemesRegistered(): Boolean {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse(PROBE_URI))
        val flags = PackageManager.MATCH_DEFAULT_ONLY
        val matches = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                probe,
                PackageManager.ResolveInfoFlags.of(flags.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(probe, flags)
        }
        return matches.isNotEmpty()
    }

    private companion object {
        const val PROBE_URI = "depv://open"

        val SECTIONS = listOf(
            Section(
                R.string.scheme_section_control,
                listOf(
                    Scheme(R.string.url_scheme_label_connect, "depv://connect"),
                    Scheme(R.string.url_scheme_label_open, "depv://open"),
                    Scheme(R.string.url_scheme_label_disconnect, "depv://disconnect"),
                    Scheme(R.string.url_scheme_label_close, "depv://close"),
                    Scheme(R.string.url_scheme_label_toggle, "depv://toggle"),
                ),
            ),
            Section(
                R.string.scheme_section_import,
                listOf(
                    Scheme(R.string.url_scheme_label_import, "depv://import/{base64}"),
                    Scheme(R.string.url_scheme_label_add, "depv://add/{url}"),
                ),
            ),
            Section(
                R.string.scheme_section_routing,
                listOf(
                    Scheme(R.string.url_scheme_label_routing_add, "depv://routing/add/{base64}"),
                    Scheme(R.string.url_scheme_label_routing_onadd, "depv://routing/onadd/{base64}"),
                ),
            ),
        )
    }
}
