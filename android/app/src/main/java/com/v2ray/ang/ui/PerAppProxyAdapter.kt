package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ItemEditorSectionBinding
import com.v2ray.ang.databinding.ViewRowLineBinding
import com.v2ray.ang.dto.AppInfo
import com.v2ray.ang.ui.component.RowBinder
import com.v2ray.ang.util.AppIconLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The app list of «Прокси по приложениям» and of the routing rule's app picker (A-18, A-19).
 *
 * Two things about the old adapter were defects rather than choices, and both are gone:
 *
 * 1. **A system app was marked by gluing `** ` onto its name in Kotlin.** That is a grouping problem
 *    solved with punctuation - it does not sort, it does not translate, it reads as a typo, and it
 *    put Latin asterisks in front of a Russian label. The list is grouped instead: «Ваши приложения»
 *    then «Системные приложения», each under a real section header ([ItemEditorSectionBinding]).
 * 2. **The boolean was a bare `MaterialCheckBox` inside a card**, one card per app, so a list of
 *    128 apps was 128 bordered boxes. It is one card's worth of rows now, separated by the
 *    full-width hairline §6 asks for, and the box itself is [RowBinder.Trailing.Checkbox] - which
 *    is the control handoff §7 draws here («список приложений с чекбоксами») and the right promise
 *    for membership of a set. The whole ROW is the target, the box is not separately focusable,
 *    and TalkBack hears a checkable node instead of a button.
 *
 * Selection is not owned here: [isSelected] and [onToggle] are passed in, so the per-app screen can
 * back it with its MMKV-persisting view model and the picker with an in-memory set, and neither has
 * to reimplement the row.
 */
class PerAppProxyAdapter(
    private val scope: CoroutineScope,
    private val isSelected: (String) -> Boolean,
    private val onToggle: (String) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** A list entry: either a group header or one app. Headers are data, not decoration. */
    private sealed interface Entry {
        data class Section(val title: CharSequence) : Entry
        data class App(val info: AppInfo) : Entry
    }

    /** The apps currently displayed, in list order. «Выбрать все» and «Инвертировать» act on these. */
    var apps: List<AppInfo> = emptyList()
        private set

    private var entries: List<Entry> = emptyList()

    /**
     * Replaces the list and rebuilds the grouping.
     *
     * @param userLabel the header above non-system apps, or null to leave that group unheaded (the
     *   picker, whose first entry is the «неопознанные приложения» pseudo-app, uses null).
     */
    @SuppressLint("NotifyDataSetChanged")
    fun submit(apps: List<AppInfo>, userLabel: CharSequence?, systemLabel: CharSequence?) {
        this.apps = apps
        val user = apps.filterNot { it.isSystemApp }
        val system = apps.filter { it.isSystemApp }
        entries = buildList {
            if (user.isNotEmpty()) {
                userLabel?.let { add(Entry.Section(it)) }
                user.forEach { add(Entry.App(it)) }
            }
            if (system.isNotEmpty()) {
                systemLabel?.let { add(Entry.Section(it)) }
                system.forEach { add(Entry.App(it)) }
            }
        }
        notifyDataSetChanged()
    }

    /** Redraws every switch after a bulk change (select all, invert, auto-select, import). */
    @SuppressLint("NotifyDataSetChanged")
    fun refreshSelection() {
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = entries.size

    override fun getItemViewType(position: Int): Int =
        if (entries[position] is Entry.Section) VIEW_TYPE_SECTION else VIEW_TYPE_APP

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_SECTION) {
            SectionViewHolder(ItemEditorSectionBinding.inflate(inflater, parent, false))
        } else {
            AppViewHolder(ViewRowLineBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val entry = entries[position]) {
            is Entry.Section -> (holder as SectionViewHolder).binding.sectionTitle.text = entry.title
            // The hairline runs the full width and never sits directly under a section header:
            // there it would double the header's own bottom rule.
            is Entry.App -> (holder as AppViewHolder)
                .bind(entry.info, divided = position > 0 && entries[position - 1] is Entry.App)
        }
    }

    private class SectionViewHolder(val binding: ItemEditorSectionBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        // A row that has left the viewport must not still be waiting on an icon: the PackageManager
        // lookup behind it is the one piece of work in this list that outlives a bind.
        (holder as? AppViewHolder)?.cancelIconLoad()
    }

    private inner class AppViewHolder(val binding: ViewRowLineBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var iconJob: Job? = null

        fun cancelIconLoad() {
            iconJob?.cancel()
            iconJob = null
        }

        fun bind(app: AppInfo, divided: Boolean) {
            binding.rowDivider.isVisible = divided
            RowBinder.bind(
                root = binding.row.root,
                title = app.appName,
                subtitle = app.packageName,
                // A placeholder the binder can tint and size; the real launcher icon replaces it
                // below. Passing a non-zero glyph is what keeps the 40dp tile - and therefore the
                // 68dp text origin - identical to every other row in the product.
                glyph = R.drawable.ic_per_apps_24dp,
                // A BOX AND NOT A SWITCH — §7 «список приложений с чекбоксами». A switch says
                // «this is on now»; this list marks 128 apps as members of a set the MODE row
                // above then acts on, and a column of switches claims to turn 128 things on one
                // at a time.
                trailing = RowBinder.Trailing.Checkbox(
                    checked = isSelected(app.packageName),
                    onCheckedChange = { onToggle(app.packageName) },
                ),
            )
            // THE ICON IS FETCHED HERE, NOT CARRIED BY THE LIST. `AppInfo` used to hold a decoded
            // launcher bitmap per installed app, all of them resident for as long as the screen was
            // open; the row asks for its own and the rest are never decoded. @see AppIconLoader
            //
            // A cached icon is applied in this same frame, so a scroll back up does not blink
            // through the placeholder. Only a miss goes to the PackageManager, and it does that off
            // the main thread.
            cancelIconLoad()
            val context = binding.root.context
            val cached = AppIconLoader.cached(context, app.packageName)
            if (cached != null) {
                showIcon(cached)
                return
            }
            // Until it arrives the row keeps the tinted placeholder RowBinder just put there, so
            // the 40dp tile and the 68dp text origin never move.
            val packageName = app.packageName
            iconJob = scope.launch {
                val icon = AppIconLoader.load(context, packageName) ?: return@launch
                // The holder may have been rebound to a different app while the lookup ran.
                if (bindingAdapterPosition == RecyclerView.NO_POSITION) return@launch
                (entries.getOrNull(bindingAdapterPosition) as? Entry.App)
                    ?.takeIf { it.info.packageName == packageName }
                    ?: return@launch
                showIcon(icon)
            }
        }

        /**
         * The launcher icon is already coloured; the binder's neutral tint would flatten it to a
         * grey silhouette, so it is cleared here rather than never applied - a recycled row that
         * showed the placeholder must not keep that tint. The slot comes from the view binding
         * rather than a second findViewById sweep: this list is 200 rows on a real device and it is
         * scrolled fast.
         */
        private fun showIcon(icon: Drawable) {
            binding.row.rowTile.setImageDrawable(icon)
            ImageViewCompat.setImageTintList(binding.row.rowTile, null)
        }
    }

    private companion object {
        const val VIEW_TYPE_SECTION = 0
        const val VIEW_TYPE_APP = 1
    }
}
