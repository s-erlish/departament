package com.v2ray.ang.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.AssetManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.SubscriptionUpdateResult
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.ServerAffiliationInfo
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isGroupType
import com.v2ray.ang.template.TemplateManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.handler.SubscriptionRefreshIdentity
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.v2ray.ang.enums.PingMethod
import kotlinx.coroutines.withContext
import java.util.Collections

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private var serverList = mutableListOf<String>() // MmkvManager.decodeServerList()
    var subscriptionId: String = MmkvManager.decodeSettingsString(AppConfig.CACHE_SUBSCRIPTION_ID, "").orEmpty()

    /**
     * THE TWO OTHER FILTERS THAT USED TO NARROW [serversCache] ARE GONE, and the reason is the same
     * for both: the surface that set them is gone and nothing has set them since.
     *
     * `keywordFilter` was the Серверы tab's search box and `protocolFilter` its protocol chips.
     * With the tab removed, `filterConfig()` and `applyProtocolFilter()` had no callers, so both
     * fields held their initial value for the life of the process — and every reader of them was a
     * branch that could only ever go one way, including a `Regex` compiled per rebuild against a
     * string that was always empty.
     *
     * **[serversCache] IS A VIEW AND [subscriptionId] IS THE ONE THING THAT STILL NARROWS IT.** That
     * distinction is the one to keep: a filtered-to-zero cache is not an empty store, and code that
     * mistakes the two takes the whole bottom navigation off screen (see
     * `MainActivity.updateBottomNavVisibility`). A search added here later must not be able to
     * reintroduce that.
     */
    val serversCache = mutableListOf<ServersCache>()
    val isRunning by lazy { MutableLiveData<Boolean>() }

    /**
     * THE SERVER THE LIVE TUNNEL IS ACTUALLY ON — which is not the same thing as the selected one,
     * and the whole «Переподключиться» offer exists because it is not.
     *
     * Picking a server while a tunnel is up SELECTS it and leaves the connection alone
     * (`MainActivity.setSelectServer`), so the moment the user declines that offer the selection
     * runs AHEAD of the tunnel and stays ahead. Nothing in this process could tell the two apart:
     * the core runs in `:RunSoLibV2RayDaemon` and its `currentConfig` is that process's field, so
     * the shell only ever had `getSelectServer()` to go on — and that is the selection, by
     * definition. This field closes that gap without asking the daemon anything.
     *
     * Written from the daemon's own state broadcasts, never guessed:
     *  - a START that succeeded ran `startContextService`, which reads `getSelectServer()` at that
     *    instant, so the selection IS the running server at exactly that moment and only then;
     *  - a plain RUNNING is the handshake answer to `MSG_REGISTER_CLIENT` (a fresh Activity asking
     *    a core that was already up). It adopts the selection ONLY when nothing is known yet,
     *    because after a process restart there is no better answer, and overwriting a value we do
     *    know would erase the very divergence this field exists to record;
     *  - anything that means "not running" clears it.
     */
    var runningGuid: String? = null
        private set

    /**
     * ОТПЕЧАТОК ТОГО, С ЧЕМ ТУННЕЛЬ ПОДНЯТ: не только какой сервер, но и в каких настройках.
     *
     * Пока обновление подписки выдавало каждому серверу новый guid, [runningGuid] отвечал на оба
     * вопроса сразу: изменились настройки - изменился guid - строка уже «не та, на которой туннель»,
     * и нажатие на неё предлагало «Переподключиться». Только он же предлагал это и после обновления,
     * в котором не поменялось ничего. Теперь неизменившийся сервер сохраняет guid
     * (`SubscriptionRefreshIdentity`), а конфиг провайдера сохраняет его и тогда, когда поменялся
     * его шаблон. Этот отпечаток - вторая половина: предложение появляется, когда туннель на другом
     * сервере ИЛИ на прежних настройках этого (см. [isTunnelOn]). Это то же правило, что на ПК
     * (`MainWindowViewModel._runningServerFingerprint`): переподключать есть смысл, только если
     * сервер правда изменился. Разница одна - на Android туннель сам не перезапускается, решает
     * человек.
     *
     * Пишется там же, где [runningGuid], и с той же оговоркой: при рукопожатии после перезапуска
     * процесса лучшего ответа, чем «то, что лежит сейчас», нет.
     */
    private var runningFingerprint: String? = null

    /**
     * Поднят ли туннель ровно на [guid] и ровно в тех настройках, что лежат под ним сейчас.
     *
     * Отпечаток, который не удалось прочитать, считается изменившимся: здесь это не перезапуск, а
     * только предложение его сделать, и лишнее предложение дешевле пропущенного.
     */
    fun isTunnelOn(guid: String): Boolean {
        if (runningGuid != guid) return false
        val running = runningFingerprint ?: return false
        return running == SubscriptionRefreshIdentity.fingerprintOfStored(guid)
    }

    /** Запоминает сервер, на котором туннель поднят, вместе с его отпечатком. */
    private fun recordRunning(guid: String?) {
        runningGuid = guid
        runningFingerprint = guid?.let { SubscriptionRefreshIdentity.fingerprintOfStored(it) }
    }

    /**
     * [MmkvManager.serversRevision] на момент последнего перечитывания списка. См.
     * [reloadServerListIfStale].
     */
    private var seenServersRevision = Long.MIN_VALUE

    val updateListAction by lazy { MutableLiveData<Int>() }
    val updateSpeedAction by lazy { MutableLiveData<Pair<Long, Long>>() }
    val delayResultAction by lazy { MutableLiveData<Long>() }

    // NO AUTO-FALLBACK STATE HERE ANY MORE. «Переключаться на более быстрый сервер» is gone by the
    // owner's instruction, and the bookkeeping it needed went with it: the fast-connect event
    // LiveData and its one-shot guard, the "already fired this session" latch and the "our own
    // restart is in flight" flag. They had no readers left once Главная dropped its half — keeping
    // them would have left a working switch with nothing but the wiring cut.
    //
    // What is NOT touched, because the owner asked for the switching to go and nothing else
    // (`PORT-DELTA.md` П-26): [testAllServers] and all four check methods, «обновить», the
    // 30-second probe behind the «мс» figure on Главная, and picking a сервер by hand.

    /**
     * The scope every latency measurement runs in.
     *
     * SupervisorJob and a handler are both load-bearing, not defensive habit. With a plain Job one
     * server that throws cancels its siblings and then the scope's own Job, and a cancelled Job
     * never accepts another child - so a single unmeasurable profile would silently disable ping
     * for the rest of the process, with no error anywhere. Without a handler the same throw reaches
     * the thread's default handler and takes the app down. One row we cannot measure is a dash in
     * one cell; it is not the end of measuring.
     */
    private val tcpingTestScope by lazy {
        CoroutineScope(
            Dispatchers.IO + SupervisorJob() +
                CoroutineExceptionHandler { _, e ->
                    LogUtil.w(AppConfig.TAG, "Delay test coroutine failed: ${e.message}")
                }
        )
    }

    /**
     * The servers whose measurement is in flight right now, so the list can show that a row is
     * being measured without inventing a stored result for it.
     *
     * In memory on purpose. This used to be a -2 written into each server's stored delay, and that
     * had two consequences: a row nothing was going to measure kept its -2 forever (it spun for a
     * result that was never coming), and every reader that treats a negative delay as "unreachable"
     * read -2 as a failure — «Удалить недоступные» deleted rows that were merely still measuring.
     * A display state must not be able to delete a server. Held in the ViewModel it also dies with
     * the work it describes: whatever kills the coroutines clears the spinners with them.
     */
    private val measuringGuids: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())

    /** True while a latency check is in flight for [guid]. Read by the list to show its spinner. */
    fun isMeasuring(guid: String): Boolean = measuringGuids.contains(guid)

    /**
     * Whether the real-ping batch this ViewModel is waiting on is still the one running.
     *
     * `CoreTestService` reports per-server results and one finish for whichever batch it is running,
     * and those messages carry no identity. Once we have cancelled a batch and started something
     * else, its trailing messages are about work nobody is waiting for, and acting on them would
     * clear the marks belonging to the run that replaced it.
     */
    private var awaitingRealPingBatch = false

    // Bounded concurrency for the direct probes, held for the ViewModel's life rather than built
    // per run: a fresh Semaphore each time would let a restarted check add its own permits on top
    // of the previous run's still-draining probes, so pressing the button twice would double the
    // bound the user was promised. One gate per probe kind, because they cost different things:
    // an HTTP probe holds a connection and a TLS handshake, ICMP forks a process, a TCP connect is
    // a socket and a wait (64 = the parallelism Dispatchers.IO gave it implicitly before).
    private val tcpProbeGate = Semaphore(64)
    private val httpProbeGate = Semaphore(24)
    private val icmpProbeGate = Semaphore(12)

    /**
     * Whether [mMsgReceiver] is currently registered on the Application context.
     *
     * The registration is bound to this ViewModel's life, not to the Activity's: it is made
     * against the **Application** context and released in [onCleared], and a configuration change
     * runs neither. The Activity, however, calls [startListenBroadcast] again on every recreate,
     * so without this flag the same receiver instance was registered N times — and Android
     * delivers to a receiver once per registration, so after ten rotations every service
     * broadcast, including the speed update that arrives about once a second while connected, was
     * handled ten times.
     */
    private var broadcastRegistered = false

    /**
     * Refer to the official documentation for [registerReceiver](https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int):
     * `registerReceiver(Context, BroadcastReceiver, IntentFilter, int)`.
     *
     * Safe to call on every Activity recreate, and meant to be: the handshake below is what makes
     * a running service re-announce its state to the fresh Activity, so it is sent every time
     * whether or not a registration was needed.
     */
    fun startListenBroadcast() {
        // «NOT RUNNING» IS ONLY PUBLISHED WHEN NOTHING IS KNOWN YET. This is called again on every
        // Activity recreate — a rotation, a theme change, a language change — and this ViewModel
        // OUTLIVES all three, so it already holds the answer at that point. Overwriting it with
        // `false` threw that answer away for the few hundred milliseconds until the daemon replied
        // to the handshake below, and in that window the screen showed a live tunnel as
        // disconnected: the session clock stopped and restarted, the hero played its exit, and the
        // connect object offered to CONNECT something that was already connected — a press there
        // used to arrive at the daemon as a duplicate start and tear the tunnel down (see
        // `CoreVpnService.onStartCommand`).
        //
        // A cold start still seeds `false`, because then it is true: nothing is running that this
        // process knows of, and the screen has to paint something while the handshake is in flight.
        if (isRunning.value == null) isRunning.value = false
        if (!broadcastRegistered) {
            val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY)
            ContextCompat.registerReceiver(getApplication(), mMsgReceiver, mFilter, Utils.receiverFlags())
            broadcastRegistered = true
        }
        MessageUtil.sendMsg2Service(getApplication(), AppConfig.MSG_REGISTER_CLIENT, "")
    }

    /**
     * Called when the ViewModel is cleared.
     */
    override fun onCleared() {
        if (broadcastRegistered) {
            broadcastRegistered = false
            getApplication<AngApplication>().unregisterReceiver(mMsgReceiver)
        }
        // The real-ping batch is deliberately left alone: it is a foreground service with its own
        // notification and is meant to outlive this screen.
        cancelMeasurementsInFlight()
        LogUtil.i(AppConfig.TAG, "Main ViewModel is cleared")
        super.onCleared()
    }

    /**
     * Reloads the server list based on current subscription filter.
     *
     * THE SELECTION IS REPAIRED FIRST, and this is the one place in the app that does it. Every
     * path that can invalidate the selection ends here — an import, a delete, a subscription
     * refresh (including the unattended one, see [AppConfig.MSG_STATE_SERVERS_CHANGED]), a finished
     * check — so a list that has servers is a list with one of them selected, by construction. See
     * [MmkvManager.ensureSelectedServer].
     */
    fun reloadServerList() {
        MmkvManager.ensureSelectedServer()

        seenServersRevision = MmkvManager.serversRevision()
        serverList = if (subscriptionId.isEmpty()) {
            MmkvManager.decodeAllServerList()
        } else {
            MmkvManager.decodeServerList(subscriptionId)
        }

        updateCache()
        updateListAction.value = -1
    }

    /**
     * Re-reads the store ONLY when it no longer agrees with what this cache holds.
     *
     * The cache is a snapshot of guids, and a subscription refresh mints new ones for every server
     * it does not recognise — so after one has run, a row on screen can address a profile that has
     * been deleted. Tapping such a row stored a dead guid as the selection, and from that moment
     * Главная said «Выберите сервер в списке ниже» over a full list and the connect object was
     * disabled.
     *
     * The refresh announces itself now, but an announcement can be missed (the app was not running,
     * the broadcast was dropped), so the shell also asks this on every resume. The comparison is a
     * list of strings against a list of strings — no profile is parsed unless something actually
     * changed, which is what keeps it off the "лишняя нагрузка" list.
     *
     * …AND ONE NUMBER. A recognised server keeps its guid across a refresh now, and a провайдер's
     * config may change under it (SubscriptionRefreshIdentity), which no list comparison can see.
     * [MmkvManager.serversRevision] moves exactly when a refresh changed something, so a refresh that
     * changed nothing still costs nothing here.
     *
     * @return true when the list was stale and has been reloaded.
     */
    fun reloadServerListIfStale(): Boolean {
        val stored = if (subscriptionId.isEmpty()) {
            MmkvManager.decodeAllServerList()
        } else {
            MmkvManager.decodeServerList(subscriptionId)
        }
        val listMatches = stored == serverList && MmkvManager.serversRevision() == seenServersRevision
        val selectionHealthy = stored.isEmpty() || MmkvManager.getSelectServer() != null
        if (listMatches && selectionHealthy) return false
        reloadServerList()
        return true
    }

    /**
     * Removes a server by its GUID.
     * @param guid The GUID of the server to remove.
     */
    fun removeServer(guid: String) {
        serverList.remove(guid)
        MmkvManager.removeServer(guid)
        val index = getPosition(guid)
        if (index >= 0) {
            serversCache.removeAt(index)
        }
    }

    // `swapServer()` USED TO STAND HERE: the drag-to-reorder write behind
    // `MainRecyclerAdapter.onItemMove`. That adapter answers the move `false` and no
    // `ItemTouchHelper` is attached to Главная's list, so nothing has been able to reach this since
    // the Серверы tab went. The provider's own order is what the list shows, and
    // `SettingsManager.applyServerSortOrder` is what rewrites it.

    /**
     * Updates the cache of servers: every profile of [subscriptionId] (or of the whole store when
     * it is blank), decoded once, in the stored order.
     *
     * The keyword and protocol branches that used to sit inside this loop are gone with the fields
     * that fed them — @see [serversCache].
     */
    @Synchronized
    fun updateCache() {
        serversCache.clear()
        for (guid in serverList) {
            val profile = MmkvManager.decodeServerConfig(guid) ?: continue
            serversCache.add(ServersCache(guid, profile))
        }
    }

    /**
     * Updates the configuration via subscription for all servers.
     * @return Detailed result of the subscription update operation.
     */
    fun updateConfigViaSubAll(): SubscriptionUpdateResult {
        if (subscriptionId.isEmpty()) {
            return AngConfigManager.updateConfigViaSubAll()
        } else {
            val subItem = MmkvManager.decodeSubscription(subscriptionId) ?: return SubscriptionUpdateResult()
            return AngConfigManager.updateConfigViaSub(SubscriptionCache(subscriptionId, subItem))
        }
    }

    // «Экспортировать все» (exportAllServer) used to live here and was reachable from the Серверы
    // tab's overflow. That tab is gone and is not coming back, and the handler outlived every
    // caller — a function that copies every server link to the clipboard, that nothing can invoke,
    // reading as though the product still offers it. Sharing one server is a live feature and keeps
    // its own route (the server row's actions sheet: share link, QR). A whole-list export needs a
    // deliberate surface and a decision about handing an operator's entire server set to the
    // clipboard in one tap; until that decision is made the feature is filed (M-51), not implied.

    /**
     * Resolves the host:port to ping for a server row.
     *
     * Ordinary profiles (vmess/vless/trojan/…) expose [ProfileItem.server] / [ProfileItem.serverPort]
     * directly. For [EConfigType.CUSTOM] xray-json profiles those fields are empty, so we parse the
     * stored raw config and read address/port from its first proxy outbound (vnext/servers →
     * address/port) — otherwise these rows would never get a direct ping. Group entries
     * (PolicyGroup "Auto"/balancer, ProxyChain) have no single address and return null so they stay
     * untested (blank) rather than showing a red "-1ms".
     *
     * @return host to (port) pair, or null when the row is not directly pingable.
     */
    private fun resolvePingHostPort(item: ServersCache): Pair<String, Int>? {
        val profile = item.profile
        // Balancer / "Auto" / proxy-chain rows have no single address to ping.
        if (profile.configType.isGroupType()) return null

        val directHost = profile.server
        val directPort = profile.serverPort?.toIntOrNull()
        if (!directHost.isNullOrEmpty() && directPort != null) {
            return directHost to directPort
        }

        // CUSTOM xray-json: pull host:port out of the stored outbound.
        if (profile.configType == EConfigType.CUSTOM) {
            val raw = try {
                TemplateManager.decodeRuntimeRaw(item.guid)
            } catch (e: Exception) {
                MmkvManager.decodeServerRaw(item.guid)
            } ?: return null
            val v2rayConfig = JsonUtil.fromJsonSafe(raw, V2rayConfig::class.java) ?: return null
            val outbound = v2rayConfig.getProxyOutbound() ?: return null
            val host = outbound.getServerAddress()
            val port = outbound.getServerPort()
            if (!host.isNullOrEmpty() && port != null) {
                return host to port
            }
        }
        return null
    }

    /**
     * Cancels every direct probe in flight and clears the marks that say a row is being measured,
     * so a restarted check never leaves a row spinning for work nobody is doing any more. The
     * probes themselves are cancellation-aware, so this stops the sockets and the `ping` processes
     * too, not only the coroutines counting them.
     */
    private fun cancelMeasurementsInFlight() {
        tcpingTestScope.coroutineContext[Job]?.cancelChildren()
        SpeedtestManager.closeAllTcpSockets()
        awaitingRealPingBatch = false
        measuringGuids.clear()
    }

    /**
     * Stops the real-ping batch running in `CoreTestService`, if any.
     *
     * Every check starts with this, including the direct ones: the batch writes its results
     * straight into the same store, so a batch left running would keep dropping proxied numbers
     * into a list the user has since asked to re-measure some other way.
     */
    private fun cancelRealPingBatch() {
        MessageUtil.sendMsg2TestService(
            getApplication(),
            TestServiceMessage(key = AppConfig.MSG_MEASURE_CONFIG_CANCEL)
        )
    }

    /**
     * Shared body of the three direct (no-tunnel) checks.
     *
     * Only the rows this run can actually address are marked as being measured: [resolvePingHostPort]
     * returns null for a group row and for a CUSTOM profile with no parseable address, nothing will
     * ever probe those, and marking them would leave them spinning for a result that is never
     * coming. They keep the blank cell that says "not measured", which is the truth.
     *
     * @param gate bound on how many probes of this kind run at once.
     * @param autoRemoveInvalid whether a failure from this probe is grounds for the automatic
     *        «удалять недоступные после проверки» to delete the server — see [onTestsFinished].
     * @param probe the measurement itself, run on the test scope for one server.
     */
    private fun runDirectTest(
        gate: Semaphore,
        autoRemoveInvalid: Boolean,
        probe: suspend (host: String, port: Int) -> Long,
    ) {
        cancelMeasurementsInFlight()
        cancelRealPingBatch()
        val serversCopy = serversCache.toList()
        MmkvManager.clearAllTestDelayResults(serversCopy.map { it.guid })

        val targets = serversCopy.mapNotNull { item -> resolvePingHostPort(item)?.let { item.guid to it } }
        measuringGuids.addAll(targets.map { it.first })
        updateListAction.value = -1

        tcpingTestScope.launch {
            // supervisorScope, so one server that throws cannot cancel its siblings, and so this
            // coroutine resumes only once every probe has finished.
            supervisorScope {
                targets.forEach { (guid, address) ->
                    launch {
                        val result = try {
                            gate.withPermit { probe(address.first, address.second) }
                        } catch (e: CancellationException) {
                            throw e // abandoned, not failed: publish nothing.
                        } catch (e: Exception) {
                            LogUtil.w(AppConfig.TAG, "Delay test failed for $guid: ${e.message}")
                            ServerAffiliationInfo.FAILED
                        }
                        withContext(Dispatchers.Main) { publishMeasurement(guid, result) }
                    }
                }
            }
            withContext(Dispatchers.Main) { onTestsFinished(autoRemoveInvalid) }
        }
    }

    /** Stores one server's result, drops its measuring mark and repaints its row. Main thread. */
    private fun publishMeasurement(guid: String, delayMillis: Long) {
        measuringGuids.remove(guid)
        MmkvManager.encodeServerTestDelayMillis(guid, delayMillis)
        updateListAction.value = getPosition(guid)
    }

    /**
     * Tests the TCP ping for all servers.
     *
     * A failure here is a refused or unanswered TCP handshake straight from this device, which is
     * as close to "this server is not reachable" as a direct probe gets — so it is allowed to feed
     * the automatic removal of unreachable servers.
     */
    fun testAllTcping() {
        runDirectTest(gate = tcpProbeGate, autoRemoveInvalid = true) { host, port ->
            SpeedtestManager.tcping(host, port)
        }
    }

    /**
     * Tests the real ping for all servers.
     */
    fun testAllRealPing() {
        cancelMeasurementsInFlight()
        cancelRealPingBatch()
        val serversCopy = serversCache.toList()
        MmkvManager.clearAllTestDelayResults(serversCopy.map { it.guid })
        // Every displayed row is measured by the worker: it either builds a throwaway core for that
        // server or reports the failure, so each one ends with a result and none stays marked.
        measuringGuids.addAll(serversCopy.map { it.guid })
        awaitingRealPingBatch = serversCopy.isNotEmpty()
        updateListAction.value = -1

        viewModelScope.launch(Dispatchers.Default) {
            if (serversCopy.isEmpty()) {
                return@launch
            }
            MessageUtil.sendMsg2TestService(
                getApplication(),
                TestServiceMessage(
                    key = AppConfig.MSG_MEASURE_CONFIG_START,
                    subscriptionId = subscriptionId,
                    // Empty = "let the service resolve the set from subscriptionId", which is what
                    // this has always sent: the explicit list was only ever built for a keyword
                    // filter, and there is none. @see serversCache
                    serverGuids = emptyList()
                )
            )
        }
    }

    /**
     * Tests the real ping for the current server.
     */
    fun testCurrentServerRealPing() {
        MessageUtil.sendMsg2Service(getApplication(), AppConfig.MSG_MEASURE_DELAY, "")
    }

    /**
     * Runs the "test all" using the user's selected ping method (Settings → ping method).
     */
    fun testAllServers() {
        when (SettingsManager.getPingMethod()) {
            PingMethod.TCP_CONNECT -> testAllTcping()
            PingMethod.HTTP_URL -> testAllDirectHttp()
            PingMethod.ICMP -> testAllIcmp()
            PingMethod.PROXIED_REAL_DELAY -> testAllRealPing()
        }
    }

    /**
     * Direct HTTP latency test across the current server list: time to first byte of a HEAD to the
     * server's own address, no tunnel.
     *
     * A failure here says only "nothing spoke HTTP(S) on that port", which most VPN servers do not
     * and are not meant to — so it must NOT feed the automatic removal of unreachable servers. Set
     * against the wrong probe that switch would empty the whole list in one press.
     */
    fun testAllDirectHttp() {
        runDirectTest(gate = httpProbeGate, autoRemoveInvalid = false) { host, port ->
            val hostPart = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
            val url = if (port == 443) "https://$hostPart/" else "https://$hostPart:$port/"
            SpeedtestManager.httpPing(url)
        }
    }

    /**
     * ICMP latency test across the current server list.
     *
     * Most servers and CDNs drop ICMP, so a failure here usually means "filtered", not "down"
     * (docs/ping-methods-design.md, method C: its -1 must not be treated as a dead server). Never
     * allowed to feed the automatic removal.
     */
    fun testAllIcmp() {
        runDirectTest(gate = icmpProbeGate, autoRemoveInvalid = false) { host, _ ->
            SpeedtestManager.icmpPing(host)
        }
    }

    // `fastConnect()` and `selectFastestServer()` USED TO STAND HERE, and they are gone on the
    // owner's word: «надо убрать в целом эту функцию о переключении на более быстрый сервер».
    // Between them they were the acting half of the auto-fallback — measure every сервер, take the
    // lowest latency, move the selection onto it and hand it to Главная to restart the tunnel on.
    // The deciding half (Главная's post-connect health probe) and the setting are already gone;
    // [SettingsManager.retireAutoFallback] additionally forces the stored key false on every start,
    // so an install that once had it on cannot bring it back.
    //
    // Do not reintroduce a "pick the fastest for the user" path here. The failure mode is the one
    // the owner objected to: the app quietly moving him off the сервер he chose.

    // `subscriptionIdChanged()` and `getSubscriptions()` USED TO STAND HERE, and they went
    // together: the second built the подписка picker's rows and was the only caller of the first.
    // Both belonged to the Серверы tab's group selector, which the owner removed — Главная shows
    // every server in one provider-grouped list and its карусель is built from [getProviderGroups].
    // `subscriptionId` itself stays: it is still what narrows the cache, and the shell writes it.

    /**
     * Gets the position of a server by its GUID.
     * @param guid The GUID of the server.
     * @return The position of the server.
     */
    fun getPosition(guid: String): Int {
        serversCache.forEachIndexed { index, it ->
            if (it.guid == guid)
                return index
        }
        return -1
    }

    // «Удалить дубликаты» (removeDuplicateServer) used to live here and lost its caller with the
    // Серверы tab. It is a bulk delete with no prompt, no count shown before the fact and no undo,
    // and it was left standing with nothing able to call it. That is the shape of defect M-19 —
    // a delete reachable by accident rather than by intent — so it is not left lying next to the
    // path that runs unattended. Restoring it means giving it a door the user opens knowingly:
    // a confirmation that names how many серверы go, and a way back. Filed as M-51.

    /**
     * Removes all servers.
     * @return The number of removed servers.
     */
    fun removeAllServer(): Int {
        val count =
            if (subscriptionId.isEmpty()) {
                MmkvManager.removeAllServer()
            } else {
                val serversCopy = serversCache.toList()
                for (item in serversCopy) {
                    MmkvManager.removeServer(item.guid)
                }
                serversCache.toList().count()
            }
        return count
    }

    /**
     * Removes the servers whose last check failed.
     *
     * A server being measured right now is never one of them, and it takes two independent guards
     * to say that honestly, because this runs unattended: [onTestsFinished] calls it whenever
     * «Автоудаление нерабочих серверов» is on, so a wrong answer here deletes the user's servers
     * with no prompt and no undo.
     *
     * *This* guard is about the run in progress. `MmkvManager.removeInvalidServer("")` sweeps the
     * whole store and cannot see which rows are in flight, so the guids are walked here and
     * anything in [measuringGuids] is skipped.
     *
     * The other guard is about the store. Nothing in this build writes an "in progress" value into
     * MMKV — a shipped build did, `-2` on every cached row including the rows its own test then
     * skipped, and MMKV kept it — so `MmkvManager` excludes that sentinel by value rather than
     * assuming it is gone. Neither guard is redundant: this one protects a row nobody has finished
     * measuring, that one protects a row nobody ever started.
     *
     * @return The number of removed servers.
     */
    fun removeInvalidServer(): Int {
        val candidates = if (subscriptionId.isEmpty()) {
            MmkvManager.decodeAllServerList()
        } else {
            serversCache.map { it.guid }
        }
        var count = 0
        for (guid in candidates.toList()) {
            if (isMeasuring(guid)) continue
            count += MmkvManager.removeInvalidServer(guid)
        }
        return count
    }

    /**
     * Sorts servers by their test results.
     */
    fun sortByTestResults() {
        if (subscriptionId.isEmpty()) {
            MmkvManager.decodeSubsList().forEach { guid ->
                sortByTestResultsForSub(guid)
            }
        } else {
            sortByTestResultsForSub(subscriptionId)
        }
    }

    /**
     * Sorts servers by their test results for a specific subscription.
     * @param subId The subscription ID to sort servers for.
     */
    private fun sortByTestResultsForSub(subId: String) {
        data class ServerDelay(var guid: String, var testDelayMillis: Long)

        // Fenced: this reads a list and writes it back, and `:bg` can be replacing the same list
        // from a subscription refresh at that moment. See [MmkvManager.inServerListTransaction].
        MmkvManager.inServerListTransaction {
            val serverDelays = mutableListOf<ServerDelay>()
            val serverListToSort = MmkvManager.decodeServerList(subId)

            serverListToSort.forEach { key ->
                val delay = MmkvManager.decodeServerAffiliationInfo(key)?.testDelayMillis ?: 0L
                serverDelays.add(ServerDelay(key, if (delay <= 0L) 999999 else delay))
            }
            serverDelays.sortBy { it.testDelayMillis }

            val sortedServerList = serverDelays.map { it.guid }.toMutableList()

            // Save the sorted list for this subscription
            MmkvManager.encodeServerList(sortedServerList, subId)
        }
    }


    /**
     * Initializes assets.
     * @param assets The asset manager.
     */
    fun initAssets(assets: AssetManager) {
        viewModelScope.launch(Dispatchers.Default) {
            SettingsManager.initAssets(getApplication<AngApplication>(), assets)
        }
    }

    // `filterConfig()`, `applyProtocolFilter()` and `availableProtocols()` USED TO STAND HERE.
    // They were the Серверы tab's search box and its protocol chips — the writers behind the two
    // filter fields described on [serversCache] — and they lost their surface with the tab. Nothing
    // has called any of them since, so the filters they set could only ever hold their initial
    // value.

    /**
     * The подписки the user actually has, pinned first.
     *
     * `__default_subscription__` IS EXCLUDED, AND THAT IS THE FIX FOR THE PHANTOM CARD.
     * It is not a подписка: it is the internal storage bucket that holds servers with no
     * subscription of their own, and `SettingsManager.migrateServerListToSubscriptions` calls
     * `ensureDefaultSubscription()` before it checks whether there is anything to migrate — so
     * every fresh install writes a `SubscriptionItem(remarks = "Default")` under that key, and
     * `MmkvManager.initSubsList` then adopts every key in `subStorage` as the subscription list.
     *
     * Drawn as a card it becomes «Подписка» (the heading falls back for a blank or "Default"
     * remark) with «Ещё не обновлялась» and an empty body — the second, nameless подписка the
     * owner saw appear next to his real one the moment he added it: «при добавлении пишет другую
     * подписку без названия». It only surfaced after the first add because with no servers at all
     * the gate block replaces the carousel entirely.
     *
     * Nothing is lost by leaving it out. Servers in that bucket still reach the list —
     * `MmkvManager.decodeAllServerList` reads it explicitly, and `MainRecyclerAdapter.rebuildRows`
     * collects everything without a matching подписка into its own «Локальные» section.
     */
    fun getProviderGroups(): List<GroupMapItem> {
        return MmkvManager.decodeSubscriptions()
            .filterNot { it.guid == AppConfig.DEFAULT_SUBSCRIPTION_ID }
            .sortedByDescending { it.subscription.pinned }
            .map { GroupMapItem(id = it.guid, remarks = it.subscription.remarks) }
    }

    fun findSubscriptionIdBySelect(): String? {
        // Get the selected server GUID
        val selectedGuid = MmkvManager.getSelectServer()
        if (selectedGuid.isNullOrEmpty()) {
            return null
        }

        val config = MmkvManager.decodeServerConfig(selectedGuid)
        return config?.subscriptionId
    }

    /**
     * Called once a whole check has finished, whichever method ran it, and nowhere else: this is
     * what drives «удалять недоступные» / «сортировать» after a test. It used to also pick a
     * сервер for the retired auto-fallback; it does not choose for the user any more.
     *
     * @param autoRemoveInvalid whether this method's failures are evidence that a server is
     *        unreachable. TCP connect and the proxied real delay say yes. Direct HTTP and ICMP say
     *        no: their failures are routine for a healthy server (no HTTP on the transport port,
     *        ICMP filtered), and deleting on that evidence would silently wipe a working list. The
     *        manual «Удалить недоступные» is a different matter — the user asks for it, sees the
     *        count and gets an undo; this one runs unattended.
     */
    fun onTestsFinished(autoRemoveInvalid: Boolean = true) {
        viewModelScope.launch(Dispatchers.Default) {
            if (autoRemoveInvalid && MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_REMOVE_INVALID_AFTER_TEST)) {
                removeInvalidServer()
            }

            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_SORT_AFTER_TEST)) {
                sortByTestResults()
            }

            withContext(Dispatchers.Main) {
                reloadServerList()
            }
        }
    }

    private val mMsgReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING -> {
                    // The handshake answer: a core that was already up telling a fresh Activity so.
                    // It carries no guid, and the selection may already have moved past the tunnel,
                    // so it only fills [runningGuid] in when nothing is known — see that field.
                    isRunning.value = true
                    if (runningGuid == null) recordRunning(MmkvManager.getSelectServer())
                }

                AppConfig.MSG_STATE_NOT_RUNNING -> {
                    isRunning.value = false
                    recordRunning(null)
                }

                AppConfig.MSG_STATE_START_SUCCESS -> {
                    // No green "Службы успешно запущены" toast: the connect screen now reflects
                    // the connected state with the neutral gray «Прокси подключён» toast + shield,
                    // driven by MainActivity's isRunning observer.
                    isRunning.value = true
                    // A start that just succeeded ran on whatever the selection held when
                    // `startContextService` read it, so this is the one moment the two are the same
                    // thing — and therefore the one moment worth recording.
                    recordRunning(MmkvManager.getSelectServer())
                }

                AppConfig.MSG_STATE_START_FAILURE -> {
                    // No system-style error toast here: MainActivity reports the failure as the
                    // neutral gray «Не удалось подключиться» toast (via connectInProgress) when
                    // this flips isRunning to false during an in-progress connect.
                    isRunning.value = false
                    recordRunning(null)
                }

                AppConfig.MSG_STATE_STOP_SUCCESS -> {
                    isRunning.value = false
                    recordRunning(null)
                }

                AppConfig.MSG_STATE_SERVERS_CHANGED -> {
                    // A подписка refresh rewrote this провайдер's servers, in a worker that owns no
                    // list: new rows, rows gone, a провайдер config changed under its old guid, and
                    // the card's traffic and «обновлено» with them. So the cache is rebuilt from the
                    // store — which also repairs the selection (reloadServerList). A refresh that
                    // changed no server rebuilds the same rows with the same guids, delays and
                    // selection, so nothing on screen moves. Receivers run on the main thread, so
                    // the LiveData set inside is on the right one.
                    reloadServerList()
                }

                // NO `MSG_MEASURE_DELAY_SUCCESS` BRANCH ANY MORE. It carried the 30-second probe's
                // human-readable sentence — «Успешно, задержка 123 мс», optionally with the exit IP
                // — for a status line this product does not have, and the one observer it reached
                // (`HomeFragment`'s [updateTestResultAction]) never read the string: it rebuilt the
                // whole server list instead, twice per probe, decoding every подписка out of MMKV to
                // do it. The daemon no longer sends it; what the screen reads is the NUMBER, on the
                // channel below. @see CoreServiceManager.measureV2rayDelay

                AppConfig.MSG_STATE_DELAY_RESULT -> {
                    (intent.getSerializableExtra("content") as? Long)?.let {
                        delayResultAction.value = it
                    }
                }

                AppConfig.MSG_STATE_SPEED_UPDATE -> {
                    (intent.getSerializableExtra("content") as? LongArray)?.let {
                        if (it.size >= 2) updateSpeedAction.value = it[0] to it[1]
                    }
                }

                AppConfig.MSG_MEASURE_CONFIG_SUCCESS -> {
                    val content = intent.getStringExtra("content")
                    if (awaitingRealPingBatch) content?.let { measuringGuids.remove(it) }
                    updateListAction.value = getPosition(content ?: "")
                }

                // NO `MSG_MEASURE_CONFIG_NOTIFY` BRANCH ANY MORE, AND NO BATCH PROGRESS CHANNEL
                // BEHIND IT. `CoreTestService` broadcast one of these per finished server, on top
                // of the `MSG_MEASURE_CONFIG_SUCCESS` it already sent for the same server, and it
                // carried nothing but an internal tally («3 / 57») that no surface has printed
                // since the shade stopped showing it. The one observer left — `HomeFragment`'s
                // `updateTestResultAction` — read none of the text and answered every message with
                // `refreshServerList(-1)`: a full rebuild plus `notifyDataSetChanged()` over the
                // whole list. So each server of a check cost a targeted repaint of its own row AND
                // a repaint of every other row on screen, in bursts, on the main thread. The row
                // that changed is named by SUCCESS above; a batch that ends is announced by FINISH
                // below. Nothing else was ever read.

                AppConfig.MSG_MEASURE_CONFIG_FINISH -> {
                    val content = intent.getStringExtra("content")
                    // Only the batch we are still waiting on, and only when it ran to the end.
                    // A cancelled batch reports "-1" after the fact, and whoever cancelled it
                    // already cleared its marks — treating that as "the measurement finished"
                    // would blank the spinners of the run that replaced it.
                    if (awaitingRealPingBatch && content == "0") {
                        awaitingRealPingBatch = false
                        measuringGuids.clear()
                        updateListAction.value = -1
                        onTestsFinished()
                    }
                }
            }
        }
    }
}
