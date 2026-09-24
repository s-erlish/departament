
package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.v2ray.ang.R
import com.v2ray.ang.contracts.MainAdapterListener
import com.v2ray.ang.util.FlagUtil
import com.v2ray.ang.databinding.ItemRecyclerFooterBinding
import com.v2ray.ang.databinding.ItemRecyclerMainBinding
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServerAffiliationInfo
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.template.TemplateManager
import com.v2ray.ang.ui.component.onSingleClick
import com.v2ray.ang.ui.component.pressFeedback
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.viewmodel.MainViewModel

class MainRecyclerAdapter(
    private val mainViewModel: MainViewModel,
    private val adapterListener: MainAdapterListener?
) : RecyclerView.Adapter<MainRecyclerAdapter.BaseViewHolder>() {
    companion object {
        private const val VIEW_TYPE_ITEM = 1
        private const val VIEW_TYPE_FOOTER = 2

        /**
         * What a failed check puts in the latency cell: a hyphen, in the failure colour, where a
         * number would be. Not a word yet because this file does not own the copy — the copy pass
         * is asked for `ping_result_failed` («нет») and should swap this for it.
         */
        private const val PING_FAILED = "-"
    }

    /**
     * THE PROVIDER SECTION HEADERS ARE GONE, AND THEY WERE NEVER DRAWN.
     *
     * This adapter used to keep a second row kind — a «провайдер» heading with a count and a
     * collapse chevron — behind a `showHeaders` flag, plus the group list it needed
     * (`GroupMapItem`), a `collapsed` set, a `Row` sealed hierarchy, a third view type and a
     * `HeaderViewHolder`. Its one host is Главная (`HomeFragment.setupServerList`, the only
     * `MainRecyclerAdapter(...)` in the app) and it has always passed `showHeaders = false`,
     * deliberately: «a second heading inside the list would say the same thing twice» — the
     * подписка card above the list IS the heading. So `useHeaders` was a compile-time false,
     * `VIEW_TYPE_HEADER` was never returned and `bindHeader` was never called.
     *
     * IT WAS NOT FREE, WHICH IS WHY IT MATTERS RATHER THAN JUST BEING TIDY. To hand this adapter
     * the groups it never used, `HomeFragment.refreshServerList` called
     * `MainViewModel.getProviderGroups()` — `MmkvManager.decodeSubscriptions()`, i.e. one MMKV read
     * and one Gson parse of a `SubscriptionItem` PER подписка, plus a sort and a map — on EVERY
     * repaint of the list, including the single-row repaint that arrives once per server of a
     * latency check and once every thirty seconds while a tunnel is up.
     */
    private var servers: List<ServersCache> = emptyList()

    /**
     * Opens the server-actions sheet for a row — edit, delete, share, QR, duplicate, make default.
     *
     * A LONG PRESS ANYWHERE ON THE ROW is the route, and the only one. A trailing «⋮» control was
     * added beside it as a visible second door (9728c5c, M-52) and the owner removed it: «почему у
     * серверов появились кнопки справа, если их вообще быть не должно». The row is back to the
     * reference shape — flag, name, protocol chip, JSON chip, transport, ping — and this handler is
     * what the long press calls. A host that leaves it null offers no actions at all, which is
     * correct: an operator-locked list has none to offer.
     */
    var onItemLongClick: ((String) -> Unit)? = null

    /**
     * Feeds the adapter with the server list to draw.
     *
     * @param index server index in [newServers]; when >= 0 only that row is refreshed.
     */
    @SuppressLint("NotifyDataSetChanged")
    fun setSections(newServers: List<ServersCache>, index: Int = -1) {
        this.servers = newServers.toList()
        val targetGuid = if (index in this.servers.indices) this.servers[index].guid else null
        // An index that names no row is treated as "the whole list moved", not as "one row moved
        // and I could not find it": the caches below are dropped wholesale rather than left holding
        // a value nothing is going to invalidate.
        val structural = targetGuid == null
        pruneCustomProtoCache(structural)
        pruneAffiliationCache(structural, targetGuid)

        // Selection can have been changed by something that owns no list — a subscription import,
        // fast-connect, or the service starting with an explicit guid. Re-read it on every rebuild
        // so a single-row refresh can never leave a stale row painted as selected.
        val latestSelection = MmkvManager.getSelectServer()
        val selectionChanged = latestSelection != selectedGuid
        selectedGuid = latestSelection

        val flat = targetGuid?.let { flatPositionOf(it) } ?: -1
        if (flat >= 0 && !selectionChanged) notifyItemChanged(flat) else notifyDataSetChanged()
    }

    private fun flatPositionOf(guid: String): Int =
        servers.indexOfFirst { it.guid == guid }

    /**
     * The guid this adapter currently paints as selected. Selection lives in MMKV, but MMKV cannot
     * notify, and it is written from several places that do not own a list (subscription import,
     * fast-connect, service start). Mirroring it here lets [syncSelection] repaint exactly the rows
     * that changed — and, crucially, detect the case where the previously selected row is no longer
     * findable, which used to leave two rows painted as selected at once.
     */
    private var selectedGuid: String? = MmkvManager.getSelectServer()

    override fun getItemCount() = servers.size + 1

    override fun getItemViewType(position: Int): Int =
        if (position == servers.size) VIEW_TYPE_FOOTER else VIEW_TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_ITEM -> MainViewHolder(ItemRecyclerMainBinding.inflate(inflater, parent, false)).also {
                // The press response, attached ONCE per view holder rather than on every bind.
                // The rung is the same @anim/press_row the layout names; what this adds is the
                // hardware layer for the duration of the rebound, which is README §11 grabl 1 —
                // a server row is nothing but text (the name, the chips, the transport, the
                // latency) and text re-rasterises on the 2% overshoot unless it is composited.
                it.itemMainBinding.infoContainer.pressFeedback(R.anim.press_row)
            }
            else -> FooterViewHolder(ItemRecyclerFooterBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: BaseViewHolder, position: Int) {
        if (holder is MainViewHolder) bindServer(holder, position, servers[position])
    }

    /**
     * THE ENTRANCE'S CLEAN-UP, and the only part of it this file has.
     *
     * Главная's assemble (handoff §3) slides the rows that are on screen in from −44dp, staggered
     * 85ms apart, once — `HomeFragment.playServerRowEntrance`, which drives the RecyclerView's
     * attached children directly and never touches bind, so the slide cannot replay on every
     * scroll. What bind cannot protect against is a row being recycled MID-SLIDE: the holder goes
     * back to the pool still carrying a translationX and a fractional alpha, and the next server
     * bound into it inherits both — a row that arrives already half off-screen and stays there,
     * for the rest of the session, with nothing in bind to explain it.
     *
     * So the transform is dropped exactly where the view leaves the screen. Cheap, unconditional,
     * and it costs nothing on the ordinary path where there was no animation to cancel.
     */
    override fun onViewRecycled(holder: BaseViewHolder) {
        super.onViewRecycled(holder)
        holder.itemView.animate().cancel()
        holder.itemView.translationX = 0f
        holder.itemView.alpha = 1f
    }

    private fun bindServer(holder: MainViewHolder, position: Int, cache: ServersCache) {
        val binding = holder.itemMainBinding
        val guid = cache.guid
        val profile = cache.profile

        binding.tvFlag.text = FlagUtil.resolveFlag(profile)
        binding.tvName.text = FlagUtil.stripLeadingFlag(profile.remarks)

        // Protocol chips: blue primary, gold JSON when the profile is an operator xray-json
        // template, grey transport·security. The JSON chip is the reference row's mark for a
        // template — chipJsonBg/chipJsonText were declared, themed in all three themes and read
        // by nothing until it came back.
        binding.tvType.text = primaryProtocol(guid, profile)
        binding.tvJson.visibility =
            if (profile.configType == EConfigType.CUSTOM) View.VISIBLE else View.GONE
        binding.tvStatistics.text = transportSecurity(guid, profile)

        // Latency: three states, three different cells (00-rules.md 15). Measuring is the spinner
        // and no number. A failed check is PING_FAILED in the failure colour — never the raw
        // negative marker, which is not a duration and would read as one. A server nobody has
        // measured yet is simply blank, and that blank is what tells it apart from a failure.
        val aff = affiliationOf(guid)
        val measuring = mainViewModel.isMeasuring(guid)
        val failed = aff?.pingResult == ServerAffiliationInfo.PingResult.FAILED
        binding.progressPing.visibility = if (measuring) View.VISIBLE else View.GONE
        binding.tvTestResult.visibility = if (measuring) View.GONE else View.VISIBLE
        val pingText = when {
            measuring -> ""
            failed -> PING_FAILED
            else -> aff?.getTestDelayString().orEmpty()
        }
        binding.tvTestResult.text = pingText
        // Ping colours resolved from theme attrs so ThemeOverlay.Mono greys them out — and
        // resolved ONCE per adapter rather than twice per bind. @see themeColor
        binding.tvTestResult.setTextColor(
            themeColor(binding.tvTestResult, if (failed) R.attr.pingBad else R.attr.pingGood)
        )
        // There is NO dot beside the figure. The colour on the number already carries the verdict,
        // and the owner had the second channel removed — «точка которая появляется она слишком
        // далеко от пинга, лучше ее просто убрать и все». Blank still means "never measured".

        // Selection: blue rounded outline via bg_server_row selected state.
        // Indicator bar tint via theme attr (mono-safe).
        // Painted from the mirrored [selectedGuid], not straight from MMKV, so that a row can never
        // render a selection state the adapter has not been told about.
        val selected = guid == selectedGuid
        binding.infoContainer.isSelected = selected
        binding.layoutIndicator.setBackgroundColor(
            if (selected) themeColor(binding.layoutIndicator, R.attr.indicatorColor)
            else Color.TRANSPARENT
        )

        // THE SEPARATOR, and it is carried on `activated` rather than drawn as a view (handoff
        // README §4, §11 grabl 3). @drawable/bg_server_row paints it as the top layer of the row's
        // own background — an inner shadow, never a border — so it costs no layout and cannot open
        // a gap in the selected row's outline.
        //
        // Two rows go without one: the SELECTED row, whose accent frame is already its edge, and
        // the FIRST row of the list, which has nothing above it to be separated from.
        binding.infoContainer.isActivated = !selected && position > 0

        // AND BOTH OF THOSE STATES LAND INSTANTLY. `bg_server_row` is a selector with
        // enterFadeDuration/exitFadeDuration, so every state written here CROSS-FADES — and a
        // recycled row arrives holding the previous server's selection and separator, which means
        // scrolling the list dissolved an accent frame and a hairline in and out of every row that
        // came round. The fade is meaningless there: it is one server's look bleeding into another
        // server's row.
        //
        // Nothing about the press is lost, and the press is what those two durations are named
        // for (motion_press_in / motion_press_out). A press happens on a row that is already laid
        // out and drawn, long after this bind; jumping here only refuses to animate a state the
        // user did not ask for — the same rule the switches follow (@see restoreChecked).
        binding.infoContainer.jumpDrawablesToCurrentState()

        // The most-tapped control in the product, and it was the one still on a raw listener.
        // The stamp lives on the view, so recycling carries the guard with the row. It does not
        // touch the deliberate repeatability of the reconnect offer — that is a fresh tap seconds
        // later, not a doubled one inside 500ms.
        binding.infoContainer.onSingleClick {
            adapterListener?.onSelectServer(guid)
        }
        // LONG-PRESS IS THE ROUTE TO THE ACTIONS SHEET — edit, share, QR, duplicate, delete.
        // Both hosts assign [onItemLongClick]; this invocation was once missing entirely, which
        // left all five unreachable, so it must not be dropped again.
        //
        // A trailing «⋮» button was added beside it in 9728c5c as a second, visible door. The
        // owner rejected it — «почему у серверов появились кнопки справа, если их вообще быть не
        // должно» — so the row is back to its reference shape and the long press is the route.
        binding.infoContainer.setOnLongClickListener {
            val handler = onItemLongClick ?: return@setOnLongClickListener false
            handler(guid)
            true
        }
    }

    private fun primaryProtocol(guid: String, profile: ProfileItem): String {
        return when (profile.configType) {
            EConfigType.POLICYGROUP -> "Auto"
            EConfigType.PROXYCHAIN -> "Chain"
            // A CUSTOM profile that wraps a single proxy outbound (e.g. a Remnawave XRAY_JSON
            // server) should show its real protocol; fall back to "Custom" only when the config
            // has no single identifiable proxy outbound.
            EConfigType.CUSTOM -> customProtoInfo(guid)?.protocol?.uppercase() ?: "Custom"
            else -> profile.configType.name
        }
    }

    private fun transportSecurity(guid: String, profile: ProfileItem): String {
        val parts = mutableListOf<String>()
        if (profile.configType == EConfigType.CUSTOM) {
            val info = customProtoInfo(guid) ?: return ""
            info.network?.let { if (it.isNotBlank()) parts.add(it.uppercase()) }
            info.security?.let { if (it.isNotBlank()) parts.add(it.uppercase()) }
            return parts.joinToString(" · ")
        }
        if (profile.configType.isComplexType()) return ""
        profile.network?.let { net ->
            if (net.isNotBlank()) parts.add(net.uppercase())
        }
        profile.security?.let { sec ->
            if (sec.isNotBlank()) parts.add(sec.uppercase())
        }
        return parts.joinToString(" · ")
    }

    /**
     * THE LAST STORE READ LEFT IN `onBindViewHolder`, AND IT IS NOT LEFT THERE ANY MORE.
     *
     * `MmkvManager.decodeServerAffiliationInfo(guid)` is one MMKV `decodeString` plus one Gson
     * parse — and it ran on EVERY bind: once per row per recycle while a finger is on the list,
     * and once per visible row for every `notifyDataSetChanged`. Everything else the row needs was
     * already memoised (`FlagUtil`'s two caches, [customProtoCache]); this was the one that was
     * not, so a fling down a long list re-read and re-parsed the same latency out of the store
     * hundreds of times a second, on the main thread, inside a frame.
     *
     * INVALIDATION IS EXACT, WHICH IS WHY THE CACHE IS SAFE. A stored delay is written by exactly
     * two paths and both announce themselves through this adapter's own entry point:
     *
     *  - one server measured (`MainViewModel.publishMeasurement`, or `MSG_MEASURE_CONFIG_SUCCESS`
     *    from the batch in the daemon) arrives as [setSections] with that server's index, and only
     *    that guid is dropped;
     *  - anything wholesale — `clearAllTestDelayResults` before a run, the end of a batch, a
     *    подписка refresh, an import, a delete — arrives with index -1, and the whole map goes.
     *
     * So the value a row paints is never older than the last thing that told the list to repaint
     * it, which is the same guarantee the direct read gave.
     */
    private val affiliationCache = HashMap<String, ServerAffiliationInfo?>()

    /** @param changedGuid the one row this refresh is about, whose stored delay has just moved. */
    private fun pruneAffiliationCache(structural: Boolean, changedGuid: String?) {
        if (structural) affiliationCache.clear() else changedGuid?.let { affiliationCache.remove(it) }
    }

    private fun affiliationOf(guid: String): ServerAffiliationInfo? {
        if (affiliationCache.containsKey(guid)) return affiliationCache[guid]
        val aff = MmkvManager.decodeServerAffiliationInfo(guid)
        affiliationCache[guid] = aff
        return aff
    }

    /**
     * A theme colour, resolved once for the life of the adapter.
     *
     * `MaterialColors.getColor(view, attr)` walks the view's theme and allocates a `TypedValue` for
     * every call, and there were TWO on every bind — the latency figure's colour and the selected
     * row's indicator. Neither can change while this adapter exists: a theme change recreates the
     * Activity, which builds a new `HomeFragment`, a new RecyclerView and a new adapter with it.
     */
    private val themeColors = HashMap<Int, Int>()

    private fun themeColor(view: View, attr: Int): Int =
        themeColors.getOrPut(attr) { MaterialColors.getColor(view, attr) }

    /** Protocol/transport/security parsed from a CUSTOM profile's wrapped xray-json outbound. */
    private data class CustomProtoInfo(
        val protocol: String,
        val network: String?,
        val security: String?,
    )

    // Parsing the stored raw config on every row bind would be wasteful, so cache per guid.
    // A `null` value is cached too (config has no single identifiable outbound → show "Custom").
    private val customProtoCache = HashMap<String, CustomProtoInfo?>()

    /** The guid set [customProtoCache] was last aligned to. @see pruneCustomProtoCache */
    private var cachedProtoGuids: Set<String> = emptySet()

    /** [MmkvManager.serversRevision] [customProtoCache] was last aligned to. @see pruneCustomProtoCache */
    private var cachedProtoRevision = Long.MIN_VALUE

    /**
     * THE PARSE CACHE NEVER FORGOT A GUID, and a подписка refresh is what made that matter.
     *
     * A refresh used to delete every profile of a провайдер and mint a new guid for each
     * replacement, so once one had run not a single cached key named a server that still existed —
     * and the entries stayed, a fresh set per refresh, for the life of the adapter. Главная's list
     * belongs to a tab the shell hides rather than replaces, so that life is the whole session.
     *
     * Dropping the whole map when the guid SET changes also settles the second question — whether a
     * cached value can still be trusted. An import and a delete move the set; a подписка refresh
     * moves it for every server it does not recognise. What the set cannot see is new content under
     * an OLD guid, and a refresh makes exactly that now: a провайдер config is recognised by its name
     * and keeps its guid while its template — protocol, transport, security, the three things this
     * cache holds — may have changed (SubscriptionRefreshIdentity). So the map also goes when
     * [MmkvManager.serversRevision] has moved, which a refresh does only when it changed something.
     * What is left over is editing one profile in place, which keeps its guid and is the one case
     * this cannot see.
     *
     * Checked only on a structural rebuild, and against the set rather than on every call, because
     * the other caller is a SINGLE-ROW refresh — one per server of a bulk latency check, arriving
     * several times a second — and a list whose rows have not moved must not pay to re-read them.
     *
     * @param structural true when the whole list was rebuilt rather than one row refreshed.
     */
    private fun pruneCustomProtoCache(structural: Boolean) {
        if (!structural) return
        val live = servers.mapTo(HashSet(servers.size)) { it.guid }
        val revision = MmkvManager.serversRevision()
        if (live == cachedProtoGuids && revision == cachedProtoRevision) return
        cachedProtoGuids = live
        cachedProtoRevision = revision
        customProtoCache.clear()
    }

    private fun customProtoInfo(guid: String): CustomProtoInfo? {
        if (customProtoCache.containsKey(guid)) return customProtoCache[guid]
        val info = try {
            val raw = TemplateManager.decodeRuntimeRaw(guid) ?: MmkvManager.decodeServerRaw(guid)
            val outbound = raw
                ?.let { JsonUtil.fromJsonSafe(it, V2rayConfig::class.java) }
                ?.getProxyOutbound()
            outbound?.let {
                CustomProtoInfo(
                    protocol = it.protocol,
                    network = it.streamSettings?.network,
                    security = it.streamSettings?.security,
                )
            }
        } catch (e: Exception) {
            null
        }
        customProtoCache[guid] = info
        return info
    }

    /** Refreshes the two rows involved in a selection change (guids). */
    fun setSelectServer(fromGuid: String?, toGuid: String?) {
        syncSelection(toGuid, previous = fromGuid)
    }

    /**
     * Repaints selection to match [guid] (defaults to whatever MMKV holds).
     *
     * Refreshing only the affected rows is the cheap path, but it is only correct when every one of
     * them is currently in [servers]. A row can be missing — the подписка carousel shows one
     * провайдер at a time, and the list may have been rebuilt by a subscription update since the
     * server was selected — and a missed refresh leaves it painted as selected next to the new one,
     * which is the "two servers selected at once" defect. So: fall back to a full refresh whenever
     * a row cannot be located.
     *
     * THE MIRROR IS REPAINTED TOO, NOT ONLY THE CALLER'S `previous`, and that is what closes the
     * last hole in this file. [previous] is what the SHELL read out of MMKV before it wrote; the
     * mirror is what THIS adapter actually painted blue, and the two are not the same thing —
     * anything that writes the selection without owning a list (an import, the service starting
     * with an explicit guid) moves the stored value while the painted row stays where it was. When
     * they disagree and both are on screen, refreshing the caller's row alone left the mirror's row
     * blue beside the new one: the same defect, arrived at from the other side. The union of the
     * three is the only set that is always right, and repainting a row that was already correct
     * costs one bind.
     */
    @SuppressLint("NotifyDataSetChanged")
    fun syncSelection(guid: String? = MmkvManager.getSelectServer(), previous: String? = selectedGuid) {
        val painted = selectedGuid
        if (guid == painted && previous == painted) return
        selectedGuid = guid

        // Every row whose look can be wrong now: the one the shell moved away from, the one this
        // adapter actually painted, and the new one.
        val affected = listOfNotNull(previous, painted, guid).distinct()
        val positions = ArrayList<Int>(affected.size)
        for (candidate in affected) {
            val position = flatPositionOf(candidate)
            if (position < 0) {
                // A row that has to change and cannot be addressed — filtered away with the
                // carousel's page, or gone with a refresh. Nothing targeted can reach it, so
                // repaint everything.
                notifyDataSetChanged()
                return
            }
            positions.add(position)
        }
        positions.distinct().forEach { notifyItemChanged(it) }
    }

    /**
     * THE DRAG CONTRACT IS OFF THIS FILE, and every piece of it was inert.
     *
     * This adapter used to implement `ItemTouchHelperAdapter` and its holder
     * `ItemTouchHelperViewHolder`, which between them are five methods: `onItemMove` (returned
     * `false`), `onItemMoveCompleted` and `onItemDismiss` (empty), and the holder's
     * `onItemSelected` / `onItemClear` — the grey wash a dragged row wore, painted as a raw
     * `Color.LTGRAY` over a row whose whole look is `@drawable/bg_server_row`.
     *
     * All five are called by `SimpleItemTouchHelperCallback`, and NO `ItemTouchHelper` is attached
     * to Главная's list (see `HomeFragment.setupServerList`) — reordering there belongs to the
     * провайдер, not the finger. The three screens that DO drag keep the interfaces and their own
     * adapters; this one was carrying a contract nobody could invoke.
     */
    open class BaseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    class MainViewHolder(val itemMainBinding: ItemRecyclerMainBinding) :
        BaseViewHolder(itemMainBinding.root)

    class FooterViewHolder(val itemFooterBinding: ItemRecyclerFooterBinding) :
        BaseViewHolder(itemFooterBinding.root)
}
