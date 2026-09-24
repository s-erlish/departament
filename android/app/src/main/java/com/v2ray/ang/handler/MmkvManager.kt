package com.v2ray.ang.handler

import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.DEFAULT_SUBSCRIPTION_ID
import com.v2ray.ang.AppConfig.PREF_IS_BOOTED
import com.v2ray.ang.AppConfig.PREF_ROUTING_RULESET
import com.v2ray.ang.dto.entities.AssetUrlCache
import com.v2ray.ang.dto.entities.AssetUrlItem
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.dto.entities.ServerAffiliationInfo
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.dto.entities.WebDavConfig
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import java.util.concurrent.locks.ReentrantLock

object MmkvManager {

    //region private

    private const val ID_MAIN = "MAIN"
    private const val ID_PROFILE_FULL_CONFIG = "PROFILE_FULL_CONFIG"
    private const val ID_SERVER_RAW = "SERVER_RAW"
    private const val ID_SERVER_AFF = "SERVER_AFF"
    private const val ID_SUB = "SUB"
    private const val ID_ASSET = "ASSET"
    private const val ID_SETTING = "SETTING"
    private const val KEY_SELECTED_SERVER = "SELECTED_SERVER"
    private const val KEY_ANG_CONFIGS = "ANG_CONFIGS"
    private const val KEY_SUB_SERVER_PREFIX = "SUB_SERVERS_"
    private const val KEY_SUB_IDS = "SUB_IDS"
    private const val KEY_WEBDAV_CONFIG = "WEBDAV_CONFIG"
    private const val KEY_SERVERS_REVISION = "SERVERS_REVISION"

    /**
     * The retired "measurement in flight" sentinel.
     *
     * A shipped build wrote **-2** into every row's stored delay when a latency check started, to
     * make the list spin. Nothing writes it any more — "in flight" is memory-only state on
     * `MainViewModel` now — but MMKV kept what that build wrote, so an upgraded install still has
     * rows carrying it, including rows the check never measured (group/balancer entries,
     * unparseable CUSTOM profiles) and therefore never overwrote.
     *
     * It is negative, and [removeInvalidServer] deletes on "negative". That combination silently
     * deleted servers the user never asked to delete, unattended, the first time
     * `PREF_AUTO_REMOVE_INVALID_AFTER_TEST` was on. So the sentinel is named here and excluded by
     * value: a display state must never be readable as a verdict, whatever wrote it.
     */
    private const val TESTING = -2L

    private val mainStorage by lazy { MMKV.mmkvWithID(ID_MAIN, MMKV.MULTI_PROCESS_MODE) }
    private val profileFullStorage by lazy { MMKV.mmkvWithID(ID_PROFILE_FULL_CONFIG, MMKV.MULTI_PROCESS_MODE) }
    private val serverRawStorage by lazy { MMKV.mmkvWithID(ID_SERVER_RAW, MMKV.MULTI_PROCESS_MODE) }
    private val serverAffStorage by lazy { MMKV.mmkvWithID(ID_SERVER_AFF, MMKV.MULTI_PROCESS_MODE) }
    private val subStorage by lazy { MMKV.mmkvWithID(ID_SUB, MMKV.MULTI_PROCESS_MODE) }
    private val assetStorage by lazy { MMKV.mmkvWithID(ID_ASSET, MMKV.MULTI_PROCESS_MODE) }
    private val settingsStorage by lazy { MMKV.mmkvWithID(ID_SETTING, MMKV.MULTI_PROCESS_MODE) }

    //endregion

    //region Server

    /**
     * Runs [block] while holding the cross-process lock on [mainStorage].
     *
     * **THE SERVER LIST IS ONE JSON STRING, AND TWO PROCESSES READ-MODIFY-WRITE IT.** Every list
     * lives under a single `SUB_SERVER_` key: a change means decode the string, edit the collection,
     * encode it back. MMKV in `MULTI_PROCESS_MODE` makes each `encode` atomic — it does not make the
     * decode and the encode around it one step. So two writers interleave and the later write wins
     * whole, silently dropping everything the other one did in between.
     *
     * The two writers are real and they run at the same time by design. `:bg` refreshes a подписка —
     * `removeServerViaSubid` and then one [encodeServerConfig] per imported сервер, several seconds
     * end to end — while the interface process may be deleting a row, importing a link, or applying
     * a sort. The visible result was серверы that reappeared after being deleted, or vanished after
     * being imported, with nothing in the log because neither write failed.
     *
     * MMKV's own inter-process lock is what the write path already uses, and it is re-entrant within
     * a process, so a `decode … encode` fenced with it is genuinely one step against the other
     * process. It is held for the read-modify-write and NEVER across I/O: a subscription fetch under
     * this lock would stall the interface for the length of an HTTP request.
     *
     * **TWO LOCKS, AND BOTH ARE NEEDED.** MMKV's inter-process lock is reference-counted per
     * INSTANCE, not per thread: while one thread of this process holds it, a second thread asking
     * for it sees the count and is let straight through. That is exactly right for the re-entrant
     * nesting these transactions do (an import fences the whole replacement, and `removeServer`
     * inside it fences again) and exactly wrong as mutual exclusion between two threads here. The
     * [java.util.concurrent.locks.ReentrantLock] supplies the second half — re-entrant for the same
     * thread, blocking for a different one — and it is always taken FIRST, so the two can never be
     * acquired in opposite orders.
     *
     * A failure to take the process lock must not lose the write itself, so the block runs either
     * way.
     */
    private val serverListLock = ReentrantLock()

    private fun <T> withServerListLock(block: () -> T): T {
        serverListLock.lock()
        val locked = try {
            mainStorage.lock()
            true
        } catch (e: Throwable) {
            LogUtil.w(AppConfig.TAG, "Server list lock unavailable, writing unfenced: ${e.message}")
            false
        }
        try {
            return block()
        } finally {
            if (locked) runCatching { mainStorage.unlock() }
            serverListLock.unlock()
        }
    }

    /**
     * Reads the legacy server list from KEY_ANG_CONFIGS for migration.
     * This method is for migration purposes only.
     *
     * @return The JSON string of legacy server list, or null if not exists.
     */
    fun readLegacyServerList(): String? {
        return mainStorage.decodeString(KEY_ANG_CONFIGS)
    }


    /**
     * The selected server — **and only when that server still exists.**
     *
     * A STORED GUID IS NOT A SERVER, and that gap is the whole defect this guard closes. A
     * subscription refresh deletes every profile of a провайдер it does not recognise in the new
     * answer and mints new guids for what is new ([removeServerViaSubid] +
     * `AngConfigManager.parseCustomConfigServer`; a server that is still there keeps its guid, see
     * [SubscriptionRefreshIdentity]), and the refresh runs unattended — the periodic worker fires
     * while the app is in the foreground. What it leaves behind, whenever the re-selection above it
     * does not land, is this key pointing at a profile that no longer exists.
     *
     * Nothing downstream could tell that apart from a real selection. `HomeFragment.resolveState`
     * read it, failed to decode the profile, and drew «Выберите сервер в списке ниже» with the
     * connect object disabled — a screen full of servers and no way to use any of them.
     * `CoreServiceManager.startContextService` read it and answered «Неправильный профиль».
     *
     * So a dangling guid reads as NO SELECTION, which is what it is. The value is not deleted here:
     * a getter that writes would race every caller, and [ensureSelectedServer] is the one place that
     * repairs the store.
     *
     * @return The selected server GUID, or null when nothing is selected or the selection is dead.
     */
    fun getSelectServer(): String? {
        val guid = mainStorage.decodeString(KEY_SELECTED_SERVER)
        if (guid.isNullOrBlank()) return null
        // containsKey, not decodeServerConfig: this is read on every render and every row rebuild,
        // and the question is whether the profile EXISTS, not what is in it.
        if (!profileFullStorage.containsKey(guid)) return null
        return guid
    }

    /**
     * Repairs the stored selection, and returns what is selected afterwards.
     *
     * Called wherever the server list is (re)read, so that "there are servers but none is selected"
     * cannot survive a single list rebuild. Three outcomes:
     *
     *  - the selection names a live server → nothing is written, that guid is returned;
     *  - the selection is dead or absent and there IS a server → the first server in the stored
     *    order is promoted, which is the same server a fresh import would have selected
     *    (`AngConfigManager.resolveSelectedKey`: "fresh add selects the first subscription server");
     *  - there is no server at all → the dead key is removed and null is returned, so the screen
     *    shows its empty state rather than naming a server nobody has.
     *
     * @return The GUID now selected, or null when the app has no servers.
     */
    fun ensureSelectedServer(): String? {
        val stored = mainStorage.decodeString(KEY_SELECTED_SERVER)
        if (!stored.isNullOrBlank() && profileFullStorage.containsKey(stored)) return stored

        val replacement = decodeAllServerList().firstOrNull { profileFullStorage.containsKey(it) }
        if (replacement == null) {
            if (!stored.isNullOrBlank()) mainStorage.remove(KEY_SELECTED_SERVER)
            return null
        }
        mainStorage.encode(KEY_SELECTED_SERVER, replacement)
        return replacement
    }

    /**
     * Sets the selected server GUID.
     *
     * @param guid The server GUID.
     */
    fun setSelectServer(guid: String) {
        mainStorage.encode(KEY_SELECTED_SERVER, guid)
    }

    /**
     * [withServerListLock] for a caller outside this object that read-modify-writes a server list —
     * a reorder, a sort. Same rules: short, and never around I/O.
     */
    fun <T> inServerListTransaction(block: () -> T): T = withServerListLock(block)

    /**
     * Encodes the server list for a given subscription.
     * Saves to the subscription's serverList (including default subscription for ungrouped servers).
     *
     * @param serverList The list of server GUIDs.
     * @param subscriptionId The subscription ID.
     */
    fun encodeServerList(serverList: MutableList<String>, subscriptionId: String) {
        val subId = getSubscriptionId(subscriptionId)
        val key = "$KEY_SUB_SERVER_PREFIX$subId"
        mainStorage.encode(key, JsonUtil.toJson(serverList))
    }


    /**
     * Decodes the server list for a given subscription.
     * If subscriptionId is empty, returns ungrouped servers.
     * Otherwise, returns servers from the specified subscription's serverList.
     *
     * @param subscriptionId The subscription ID.
     * @return The list of server GUIDs.
     */
    fun decodeServerList(subscriptionId: String): MutableList<String> {
        val subId = getSubscriptionId(subscriptionId)
        val key = "$KEY_SUB_SERVER_PREFIX$subId"
        val json = mainStorage.decodeString(key)
        return if (json.isNullOrBlank()) {
            mutableListOf()
        } else {
            JsonUtil.fromJsonSafe(json, Array<String>::class.java)?.toMutableList() ?: mutableListOf()
        }
    }

    /**
     * Decodes all server list (merged from all subscriptions including default subscription).
     * Use this when you need the complete server list.
     *
     * @return The list of all server GUIDs.
     */
    fun decodeAllServerList(): MutableList<String> {
        val allServers = mutableListOf<String>()
        val subsList = decodeSubsList()

        // If DEFAULT_SUBSCRIPTION_ID is not in the subscriptions list, add its servers
        if (!subsList.contains(DEFAULT_SUBSCRIPTION_ID)) {
            allServers.addAll(decodeServerList(DEFAULT_SUBSCRIPTION_ID))
        }

        // Add servers from all subscriptions
        subsList.forEach { guid ->
            allServers.addAll(decodeServerList(guid))
        }

        return allServers
    }


    /**
     * Decodes the server configuration.
     *
     * @param guid The server GUID.
     * @return The server configuration.
     */
    fun decodeServerConfig(guid: String): ProfileItem? {
        if (guid.isBlank()) {
            return null
        }
        val json = profileFullStorage.decodeString(guid)
        if (json.isNullOrBlank()) {
            return null
        }
        return JsonUtil.fromJsonSafe(json, ProfileItem::class.java)
    }


    /**
     * Encodes the server configuration.
     *
     * @param guid The server GUID.
     * @param config The server configuration.
     * @return The server GUID.
     */
    fun encodeServerConfig(guid: String, config: ProfileItem): String {
        val key = guid.ifBlank { Utils.getUuid() }
        profileFullStorage.encode(key, JsonUtil.toJson(config))

        // Use default subscription for servers without subscription
        val subId = getSubscriptionId(config.subscriptionId)
        withServerListLock {
            val serverList = decodeServerList(subId)
            if (!serverList.contains(key)) {
                serverList.add(0, key)
                encodeServerList(serverList, subId)
                if (getSelectServer().isNullOrBlank()) {
                    mainStorage.encode(KEY_SELECTED_SERVER, key)
                }
            }
        }

        return key
    }

    /**
     * Encodes the server configuration directly without updating serverList.
     *
     * @param key The server GUID.
     * @param configJson The server configuration JSON string.
     */
    fun encodeProfileDirect(key: String, configJson: String) {
        profileFullStorage.encode(key, configJson)
    }

    /**
     * Removes the server configuration.
     *
     * @param guid The server GUID.
     */
    fun removeServer(guid: String) {
        if (guid.isBlank()) {
            return
        }

        // Get config to determine which subscription to update
        val config = decodeServerConfig(guid)
        val subId = getSubscriptionId(config?.subscriptionId)

        // Remove from appropriate server list
        withServerListLock {
            val serverList = decodeServerList(subId)
            serverList.remove(guid)
            encodeServerList(serverList, subId)
        }

        // Clean up storage
        if (getSelectServer() == guid) {
            mainStorage.remove(KEY_SELECTED_SERVER)
        }
        profileFullStorage.remove(guid)
        serverAffStorage.remove(guid)
        // The raw template goes with the profile, and after it, never before: a locked operator
        // profile reads its config out of this store, so dropping the raw first would leave a live
        // profile pointing at nothing.
        serverRawStorage.remove(guid)
    }

    /**
     * Removes the server configurations via subscription ID.
     *
     * This is the hot path for the raw-template store: a subscription refresh replaces a
     * провайдер's whole server set through here, so before the raw entries were removed with their
     * profiles the store grew by a full copy of every сервер on every update, for the life of the
     * install, with nothing able to read a single one of those entries again.
     *
     * @param subscriptionId The subscription ID.
     * @param keep guids that the caller is about to write again under the same key — the servers a
     *   refresh recognised in the new answer ([SubscriptionRefreshIdentity]). They leave the list
     *   like every other row, because the caller rebuilds the list in the провайдер's order, but
     *   their records stay: the delay under the guid is what «пинги не пропадают» means, and the
     *   selection pointing at one of them is still a live selection, so it is not cleared either.
     */
    fun removeServerViaSubid(subscriptionId: String?, keep: Set<String> = emptySet()) {
        val subId = getSubscriptionId(subscriptionId)
        withServerListLock {
            val serverList = decodeServerList(subId)

            // Remove every server in the list except the ones the caller is about to rewrite
            serverList.forEach { guid ->
                if (guid in keep) return@forEach
                if (getSelectServer() == guid) {
                    mainStorage.remove(KEY_SELECTED_SERVER)
                }
                profileFullStorage.remove(guid)
                serverAffStorage.remove(guid)
                serverRawStorage.remove(guid)
            }

            serverList.clear()
            encodeServerList(serverList, subId)
        }
    }

    /**
     * Счётчик изменений содержимого серверов: растёт, когда обновление подписки на самом деле
     * что-то поменяло - строку, её порядок или настройки под прежним guid.
     *
     * ЗАЧЕМ ОН, КОГДА ЕСТЬ СПИСОК guid. Пока каждое обновление выдавало серверам новые guid,
     * сравнения списков хватало: изменилось хоть что-то - изменился и список. Теперь узнанный
     * сервер сохраняет guid ([SubscriptionRefreshIdentity]), а у конфига провайдера под тем же
     * guid вправе поменяться шаблон - адрес, транспорт, протокол. Список guid такого не видит, и
     * экран, сверяющий только его (`MainViewModel.reloadServerListIfStale`,
     * `MainRecyclerAdapter.pruneCustomProtoCache`), продолжал бы рисовать прежнее. Счётчик - та
     * самая недостающая половина, и стоит одно чтение числа.
     *
     * В `mainStorage`, MULTI_PROCESS_MODE: пишет его `:bg`, а читает процесс интерфейса.
     */
    fun serversRevision(): Long = mainStorage.decodeLong(KEY_SERVERS_REVISION, 0L)

    /** @see serversRevision */
    fun markServersChanged() {
        withServerListLock {
            mainStorage.encode(KEY_SERVERS_REVISION, serversRevision() + 1)
        }
    }

    /**
     * Decodes the server affiliation information.
     *
     * @param guid The server GUID.
     * @return The server affiliation information.
     */
    fun decodeServerAffiliationInfo(guid: String): ServerAffiliationInfo? {
        if (guid.isBlank()) {
            return null
        }
        val json = serverAffStorage.decodeString(guid)
        if (json.isNullOrBlank()) {
            return null
        }
        return JsonUtil.fromJsonSafe(json, ServerAffiliationInfo::class.java)
    }

    /**
     * Encodes the server test delay in milliseconds.
     *
     * @param guid The server GUID.
     * @param testResult The test delay in milliseconds.
     */
    fun encodeServerTestDelayMillis(guid: String, testResult: Long) {
        if (guid.isBlank()) {
            return
        }
        val aff = decodeServerAffiliationInfo(guid) ?: ServerAffiliationInfo()
        aff.testDelayMillis = testResult
        serverAffStorage.encode(guid, JsonUtil.toJson(aff))
    }

    /**
     * Clears all test delay results.
     *
     * @param keys The list of server GUIDs.
     */
    fun clearAllTestDelayResults(keys: List<String>?) {
        keys?.forEach { key ->
            decodeServerAffiliationInfo(key)?.let { aff ->
                aff.testDelayMillis = 0
                serverAffStorage.encode(key, JsonUtil.toJson(aff))
            }
        }
    }

    /**
     * Removes all server configurations.
     *
     * `mainStorage` is NOT cleared wholesale, and must not be: it is a shared store, not a server
     * store. Alongside the per-subscription server lists it holds [KEY_SUB_IDS] — the провайдер
     * order the user arranged — and [KEY_WEBDAV_CONFIG]. Clearing it took both down with the
     * серверы, and [initSubsList] then rebuilt the провайдер list from `subStorage.allKeys()` in
     * whatever order the store happened to enumerate. Only the keys that describe серверы go.
     *
     * @return The number of server configurations removed.
     */
    fun removeAllServer(): Int {
        val count = profileFullStorage.allKeys()?.count() ?: 0
        mainStorage.allKeys()
            ?.filter { it.startsWith(KEY_SUB_SERVER_PREFIX) }
            ?.forEach { mainStorage.remove(it) }
        mainStorage.remove(KEY_SELECTED_SERVER)
        profileFullStorage.clearAll()
        serverAffStorage.clearAll()
        // The raw templates are keyed by the same guids as the profiles just dropped, so every one
        // of them is now unreachable (D23: nothing else deletes from this store).
        serverRawStorage.clearAll()
        return count
    }

    /**
     * Whether a stored delay is evidence that a check ran against this server and it did not answer.
     *
     * Not simply "negative". [TESTING] is negative and is not a verdict about the server at all — it
     * is a display state a retired build persisted — so it is excluded by value here rather than
     * trusted to be absent. This is the single place that decides what "invalid" means, and it is
     * the place that deletes, so the two cannot drift apart.
     */
    private fun isFailedMeasurement(delayMillis: Long): Boolean =
        delayMillis < 0L && delayMillis != TESTING

    /**
     * Removes invalid server configurations.
     *
     * @param guid The server GUID.
     * @return The number of server configurations removed.
     */
    fun removeInvalidServer(guid: String): Int {
        var count = 0
        if (guid.isNotEmpty()) {
            decodeServerAffiliationInfo(guid)?.let { aff ->
                if (isFailedMeasurement(aff.testDelayMillis)) {
                    removeServer(guid)
                    count++
                }
            }
        } else {
            serverAffStorage.allKeys()?.forEach { key ->
                decodeServerAffiliationInfo(key)?.let { aff ->
                    if (isFailedMeasurement(aff.testDelayMillis)) {
                        removeServer(key)
                        count++
                    }
                }
            }
        }
        return count
    }

    /**
     * Encodes the raw server configuration.
     *
     * @param guid The server GUID.
     * @param config The raw server configuration.
     */
    fun encodeServerRaw(guid: String, config: String) {
        serverRawStorage.encode(guid, config)
    }

    /**
     * Decodes the raw server configuration.
     *
     * @param guid The server GUID.
     * @return The raw server configuration.
     */
    fun decodeServerRaw(guid: String): String? {
        return serverRawStorage.decodeString(guid)
    }

    /**
     * Deletes every raw template whose profile no longer exists.
     *
     * A one-time repair, not a routine sweep: the delete paths above now carry the raw entry with
     * its profile, so nothing new is orphaned. What this clears is the backlog left by the builds
     * that had no delete path at all — one copy of every сервер per subscription refresh, kept for
     * the life of the install and read by nothing.
     *
     * @return The number of orphaned raw entries removed.
     */
    fun pruneOrphanServerRaw(): Int {
        val keys = serverRawStorage.allKeys() ?: return 0
        var count = 0
        for (key in keys) {
            if (profileFullStorage.containsKey(key)) continue
            serverRawStorage.remove(key)
            count++
        }
        return count
    }

    //endregion

    //region Subscriptions

    private fun getSubscriptionId(subscriptionId: String?): String {
        return subscriptionId?.ifEmpty { DEFAULT_SUBSCRIPTION_ID } ?: DEFAULT_SUBSCRIPTION_ID
    }

    /**
     * Initializes the subscription list.
     */
    private fun initSubsList() {
        val subsList = decodeSubsList()
        if (subsList.isNotEmpty()) {
            return
        }
        subStorage.allKeys()?.forEach { key ->
            subsList.add(key)
        }
        encodeSubsList(subsList)
    }

    /**
     * Decodes the subscriptions.
     *
     * @return The list of subscriptions.
     */
    fun decodeSubscriptions(): List<SubscriptionCache> {
        initSubsList()

        val subscriptions = mutableListOf<SubscriptionCache>()
        decodeSubsList().forEach { key ->
            val json = subStorage.decodeString(key)
            if (!json.isNullOrBlank()) {
                val item = JsonUtil.fromJsonSafe(json, SubscriptionItem::class.java) ?: SubscriptionItem()
                subscriptions.add(SubscriptionCache(key, item))
            }
        }
        return subscriptions
    }

    /**
     * Removes the subscription.
     *
     * @param subid The subscription ID.
     */
    fun removeSubscription(subid: String) {
        subStorage.remove(subid)
        val subsList = decodeSubsList()
        subsList.remove(subid)
        encodeSubsList(subsList)

        removeServerViaSubid(subid)
    }

    /**
     * Encodes the subscription.
     *
     * @param guid The subscription GUID.
     * @param subItem The subscription item.
     */
    fun encodeSubscription(guid: String, subItem: SubscriptionItem) {
        val key = guid.ifBlank { Utils.getUuid() }
        subStorage.encode(key, JsonUtil.toJson(subItem))

        val subsList = decodeSubsList()
        if (!subsList.contains(key)) {
            subsList.add(key)
            encodeSubsList(subsList)
        }
    }

    /**
     * Decodes the subscription.
     *
     * @param subscriptionId The subscription ID.
     * @return The subscription item.
     */
    fun decodeSubscription(subscriptionId: String): SubscriptionItem? {
        val json = subStorage.decodeString(subscriptionId) ?: return null
        return JsonUtil.fromJsonSafe(json, SubscriptionItem::class.java)
    }

    /**
     * Encodes the subscription list.
     *
     * @param subsList The list of subscription IDs.
     */
    fun encodeSubsList(subsList: MutableList<String>) {
        mainStorage.encode(KEY_SUB_IDS, JsonUtil.toJson(subsList))
    }

    /**
     * Decodes the subscription list.
     *
     * @return The list of subscription IDs.
     */
    fun decodeSubsList(): MutableList<String> {
        val json = mainStorage.decodeString(KEY_SUB_IDS)
        return if (json.isNullOrBlank()) {
            mutableListOf()
        } else {
            JsonUtil.fromJsonSafe(json, Array<String>::class.java)?.toMutableList() ?: mutableListOf()
        }
    }

    //endregion

    //region Asset

    /**
     * Decodes the asset URLs.
     *
     * @return The list of asset URLs.
     */
    fun decodeAssetUrls(): List<AssetUrlCache> {
        val assetUrlItems = mutableListOf<AssetUrlCache>()
        assetStorage.allKeys()?.forEach { key ->
            val json = assetStorage.decodeString(key)
            if (!json.isNullOrBlank()) {
                val item = JsonUtil.fromJsonSafe(json, AssetUrlItem::class.java) ?: AssetUrlItem()
                assetUrlItems.add(AssetUrlCache(key, item))
            }
        }
        return assetUrlItems.sortedBy { it.assetUrl.addedTime }
    }

    /**
     * Removes the asset URL.
     *
     * @param assetid The asset ID.
     */
    fun removeAssetUrl(assetid: String) {
        assetStorage.remove(assetid)
    }

    /**
     * Encodes the asset.
     *
     * @param assetid The asset ID.
     * @param assetItem The asset item.
     */
    fun encodeAsset(assetid: String, assetItem: AssetUrlItem) {
        val key = assetid.ifBlank { Utils.getUuid() }
        assetStorage.encode(key, JsonUtil.toJson(assetItem))
    }

    /**
     * Decodes the asset.
     *
     * @param assetid The asset ID.
     * @return The asset item.
     */
    fun decodeAsset(assetid: String): AssetUrlItem? {
        val json = assetStorage.decodeString(assetid) ?: return null
        return JsonUtil.fromJsonSafe(json, AssetUrlItem::class.java)
    }

    //endregion

    //region Routing

    /**
     * Decodes the routing rulesets.
     *
     * @return The list of routing rulesets.
     */
    fun decodeRoutingRulesets(): MutableList<RulesetItem>? {
        val ruleset = settingsStorage.decodeString(PREF_ROUTING_RULESET)
        if (ruleset.isNullOrEmpty()) return null
        return JsonUtil.fromJsonSafe(ruleset, Array<RulesetItem>::class.java)?.toMutableList() ?: mutableListOf()
    }

    /**
     * Encodes the routing rulesets.
     *
     * @param rulesetList The list of routing rulesets.
     */
    fun encodeRoutingRulesets(rulesetList: MutableList<RulesetItem>?) {
        if (rulesetList.isNullOrEmpty())
            encodeSettings(PREF_ROUTING_RULESET, "")
        else
            encodeSettings(PREF_ROUTING_RULESET, JsonUtil.toJson(rulesetList))
    }

    //endregion

    //region settings
    /**
     * Encodes the settings.
     *
     * @param key The settings key.
     * @param value The settings value.
     * @return Whether the encoding was successful.
     */
    fun encodeSettings(key: String, value: String?): Boolean {
        return settingsStorage.encode(key, value)
    }

    /**
     * Encodes the settings.
     *
     * @param key The settings key.
     * @param value The settings value.
     * @return Whether the encoding was successful.
     */
    fun encodeSettings(key: String, value: Int): Boolean {
        return settingsStorage.encode(key, value)
    }

    /**
     * Encodes the settings.
     *
     * @param key The settings key.
     * @param value The settings value.
     * @return Whether the encoding was successful.
     */
    fun encodeSettings(key: String, value: Long): Boolean {
        return settingsStorage.encode(key, value)
    }

    /**
     * Encodes the settings.
     *
     * @param key The settings key.
     * @param value The settings value.
     * @return Whether the encoding was successful.
     */
    fun encodeSettings(key: String, value: Float): Boolean {
        return settingsStorage.encode(key, value)
    }

    /**
     * Encodes the settings.
     *
     * @param key The settings key.
     * @param value The settings value.
     * @return Whether the encoding was successful.
     */
    fun encodeSettings(key: String, value: Boolean): Boolean {
        return settingsStorage.encode(key, value)
    }

    /**
     * Encodes the settings.
     *
     * @param key The settings key.
     * @param value The settings value.
     * @return Whether the encoding was successful.
     */
    fun encodeSettings(key: String, value: MutableSet<String>): Boolean {
        return settingsStorage.encode(key, value)
    }

    /**
     * Decodes the settings string.
     *
     * @param key The settings key.
     * @return The settings value.
     */
    fun decodeSettingsString(key: String): String? {
        return settingsStorage.decodeString(key)
    }

    /**
     * Decodes the settings string.
     *
     * @param key The settings key.
     * @param defaultValue The default value.
     * @return The settings value.
     */
    fun decodeSettingsString(key: String, defaultValue: String?): String? {
        return settingsStorage.decodeString(key, defaultValue)
    }

    /**
     * Decodes the settings integer.
     *
     * @param key The settings key.
     * @param defaultValue The default value.
     * @return The settings value.
     */
    fun decodeSettingsInt(key: String, defaultValue: Int): Int {
        return settingsStorage.decodeInt(key, defaultValue)
    }

    /**
     * Decodes the settings long.
     *
     * @param key The settings key.
     * @param defaultValue The default value.
     * @return The settings value.
     */
    fun decodeSettingsLong(key: String, defaultValue: Long): Long {
        return settingsStorage.decodeLong(key, defaultValue)
    }

    /**
     * Decodes the settings float.
     *
     * @param key The settings key.
     * @param defaultValue The default value.
     * @return The settings value.
     */
    fun decodeSettingsFloat(key: String, defaultValue: Float): Float {
        return settingsStorage.decodeFloat(key, defaultValue)
    }

    /**
     * Decodes the settings boolean.
     *
     * @param key The settings key.
     * @return The settings value.
     */
    fun decodeSettingsBool(key: String): Boolean {
        return settingsStorage.decodeBool(key, false)
    }

    /**
     * Decodes the settings boolean.
     *
     * @param key The settings key.
     * @param defaultValue The default value.
     * @return The settings value.
     */
    fun decodeSettingsBool(key: String, defaultValue: Boolean): Boolean {
        return settingsStorage.decodeBool(key, defaultValue)
    }

    /**
     * Decodes the settings string set.
     *
     * @param key The settings key.
     * @return The settings value.
     */
    fun decodeSettingsStringSet(key: String): MutableSet<String>? {
        return settingsStorage.decodeStringSet(key)
    }


    /**
     * Encodes the start on boot setting.
     *
     * @param startOnBoot Whether to start on boot.
     */
    fun encodeStartOnBoot(startOnBoot: Boolean) {
        encodeSettings(PREF_IS_BOOTED, startOnBoot)
    }

    /**
     * Decodes the start on boot setting.
     *
     * @return Whether to start on boot.
     */
    fun decodeStartOnBoot(): Boolean {
        return decodeSettingsBool(PREF_IS_BOOTED, false)
    }

    //endregion

    //region WebDAV

    /**
     * Encodes the WebDAV config as JSON into storage.
     */
    fun encodeWebDavConfig(config: WebDavConfig): Boolean {
        return mainStorage.encode(KEY_WEBDAV_CONFIG, JsonUtil.toJson(config))
    }

    /**
     * Decodes the WebDAV config from storage.
     */
    fun decodeWebDavConfig(): WebDavConfig? {
        val json = mainStorage.decodeString(KEY_WEBDAV_CONFIG) ?: return null
        return JsonUtil.fromJsonSafe(json, WebDavConfig::class.java)
    }

    //endregion
}
