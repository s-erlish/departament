package com.v2ray.ang.handler

import android.content.Context
import android.content.res.AssetManager
import android.os.Build
import android.text.TextUtils
import androidx.appcompat.app.AppCompatDelegate
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.ANG_PACKAGE
import com.v2ray.ang.AppConfig.DEFAULT_SUBSCRIPTION_ID
import com.v2ray.ang.AppConfig.GEOIP_PRIVATE
import com.v2ray.ang.AppConfig.GEOSITE_PRIVATE
import com.v2ray.ang.AppConfig.TAG_DIRECT
import com.v2ray.ang.AppConfig.VPN
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.Language
import com.v2ray.ang.enums.PingMethod
import com.v2ray.ang.enums.RoutingType
import com.v2ray.ang.enums.VpnInterfaceAddressConfig
import com.v2ray.ang.handler.MmkvManager.decodeAllServerList
import com.v2ray.ang.handler.MmkvManager.decodeServerConfig
import com.v2ray.ang.handler.MmkvManager.decodeSubsList
import com.v2ray.ang.handler.MmkvManager.decodeSubscription
import com.v2ray.ang.handler.MmkvManager.encodeSubscription
import com.v2ray.ang.handler.MmkvManager.removeSubscription
import com.v2ray.ang.service.TProxyService
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.Locale
import kotlin.random.Random

object SettingsManager {

    @Volatile
    private var runtimeSocksPort: Int? = null

    fun initApp(context: Context) {
        ensureDefaultSettings()
        migrateDomesticDnsDefaultOnce()
        retireAutoFallback()
        // «Российские приложения» mimo VPN, on out of the box — but only on an install whose
        // per-app routing nobody has touched. See RussianAppsPreset.seedOnFirstRun.
        RussianAppsPreset.seedOnFirstRun()
        initRoutingRulesets(context)
        migrateServerListToSubscriptions()
        migrateHysteria2PinSHA256()
        pruneOrphanServerRawOnce()
    }

    /**
     * Initialize routing rulesets.
     * @param context The application context.
     */
    private fun initRoutingRulesets(context: Context) {
        val exist = MmkvManager.decodeRoutingRulesets()
        if (exist.isNullOrEmpty()) {
            val rulesetList = getPresetRoutingRulesets(context)
            MmkvManager.encodeRoutingRulesets(rulesetList)
        }
    }

    /**
     * Get preset routing rulesets.
     * @param context The application context.
     * @param index The index of the routing type.
     * @return A mutable list of RulesetItem.
     */
    private fun getPresetRoutingRulesets(context: Context, index: Int = 0): MutableList<RulesetItem>? {
        val fileName = RoutingType.fromIndex(index).fileName
        val assets = Utils.readTextFromAssets(context, fileName)
        if (TextUtils.isEmpty(assets)) {
            return null
        }

        return JsonUtil.fromJsonSafe(assets, Array<RulesetItem>::class.java)?.toMutableList()
    }

    /**
     * Reset routing rulesets from presets.
     * @param context The application context.
     * @param index The index of the routing type.
     */
    fun resetRoutingRulesetsFromPresets(context: Context, index: Int) {
        val rulesetList = getPresetRoutingRulesets(context, index) ?: return
        resetRoutingRulesetsCommon(rulesetList)
    }

    /**
     * Reset routing rulesets.
     * @param content The content of the rulesets.
     * @return True if successful, false otherwise.
     */
    fun resetRoutingRulesets(content: String?): Boolean {
        if (content.isNullOrEmpty()) {
            return false
        }

        try {
            val rulesetList = JsonUtil.fromJsonSafe(content, Array<RulesetItem>::class.java)?.toMutableList()
            if (rulesetList.isNullOrEmpty()) {
                return false
            }

            resetRoutingRulesetsCommon(rulesetList)
            return true
        } catch (e: Exception) {
            LogUtil.e(ANG_PACKAGE, "Failed to reset routing rulesets", e)
            return false
        }
    }

    /**
     * Common method to reset routing rulesets.
     * @param rulesetList The list of rulesets.
     */
    private fun resetRoutingRulesetsCommon(rulesetList: MutableList<RulesetItem>) {
        val rulesetNew: MutableList<RulesetItem> = mutableListOf()
        MmkvManager.decodeRoutingRulesets()?.forEach { key ->
            if (key.locked == true) {
                rulesetNew.add(key)
            }
        }

        rulesetNew.addAll(rulesetList)
        MmkvManager.encodeRoutingRulesets(rulesetNew)
    }

    /**
     * Get a routing ruleset by index.
     * @param index The index of the ruleset.
     * @return The RulesetItem.
     */
    fun getRoutingRuleset(index: Int): RulesetItem? {
        if (index < 0) return null

        val rulesetList = MmkvManager.decodeRoutingRulesets()
        if (rulesetList.isNullOrEmpty()) return null

        return rulesetList[index]
    }

    /**
     * Save a routing ruleset.
     * @param index The index of the ruleset.
     * @param ruleset The RulesetItem to save.
     */
    fun saveRoutingRuleset(index: Int, ruleset: RulesetItem?) {
        if (ruleset == null) return

        var rulesetList = MmkvManager.decodeRoutingRulesets()
        if (rulesetList.isNullOrEmpty()) {
            rulesetList = mutableListOf()
        }

        if (index < 0 || index >= rulesetList.count()) {
            rulesetList.add(0, ruleset)
        } else {
            rulesetList[index] = ruleset
        }
        MmkvManager.encodeRoutingRulesets(rulesetList)
    }

    /**
     * Remove a routing ruleset by index.
     * @param index The index of the ruleset.
     */
    fun removeRoutingRuleset(index: Int) {
        if (index < 0) return

        val rulesetList = MmkvManager.decodeRoutingRulesets()
        if (rulesetList.isNullOrEmpty()) return

        rulesetList.removeAt(index)
        MmkvManager.encodeRoutingRulesets(rulesetList)
    }

    /**
     * Check if routing rulesets bypass LAN.
     *
     * OFF WHEN NOBODY ASKED (owner report 0.4.9: «обход локальной сети почему-то по дефолту
     * включён, хотя не должен быть»). `PREF_VPN_BYPASS_LAN` is a STRING, not a boolean, and that is
     * what makes this change safe: it holds "1" (on), "2" (off) or nothing at all, and only
     * [com.v2ray.ang.ui.SettingsTabFragment.toggleBypassLan] ever writes it, always with "1" or
     * "2". So «never touched» is a third, distinguishable state — an install that had the switch
     * turned on deliberately reads "1" and keeps bypassing, and only the untouched install changes
     * behaviour, which is exactly what was asked for. Nothing seeds this key on first run, so no
     * migration is needed and none is possible to get wrong.
     *
     * The fall-through below (a value that is neither "1" nor "2") derives the answer from the
     * selected server's routing rules. It stays reachable only for a value this app never writes;
     * `null` is answered here, because «unset» has to mean OFF and not «go and look».
     *
     * @return True if bypassing LAN, false otherwise.
     */
    fun routingRulesetsBypassLan(): Boolean {
        val vpnBypassLan = MmkvManager.decodeSettingsString(AppConfig.PREF_VPN_BYPASS_LAN) ?: "2"
        if (vpnBypassLan == "1") {
            return true
        } else if (vpnBypassLan == "2") {
            return false
        }

        val guid = MmkvManager.getSelectServer() ?: return false
        val config = decodeServerConfig(guid) ?: return false
        if (config.configType == EConfigType.CUSTOM) {
            // decodeRuntimeRaw transparently decrypts hidden templates; identical to
            // decodeServerRaw for ordinary custom configs. Defensive fallback to the plain
            // stored raw so a template/keystore failure can never crash this read.
            val raw = try {
                com.v2ray.ang.template.TemplateManager.decodeRuntimeRaw(guid)
            } catch (e: Exception) {
                MmkvManager.decodeServerRaw(guid)
            } ?: return false
            val v2rayConfig = JsonUtil.fromJsonSafe(raw, V2rayConfig::class.java)
            val exist = v2rayConfig?.routing?.rules?.filter { it.outboundTag == TAG_DIRECT }?.any {
                it.domain?.contains(GEOSITE_PRIVATE) == true || it.ip?.contains(GEOIP_PRIVATE) == true
            }
            return exist == true
        }

        val rulesetItems = MmkvManager.decodeRoutingRulesets()
        val exist = rulesetItems?.filter { it.enabled && it.outboundTag == TAG_DIRECT }?.any {
            it.domain?.contains(GEOSITE_PRIVATE) == true || it.ip?.contains(GEOIP_PRIVATE) == true
        }
        return exist == true
    }

    /**
     * Swap routing rulesets.
     * @param fromPosition The position to swap from.
     * @param toPosition The position to swap to.
     */
    fun swapRoutingRuleset(fromPosition: Int, toPosition: Int) {
        val rulesetList = MmkvManager.decodeRoutingRulesets()
        if (rulesetList.isNullOrEmpty()) return

        Collections.swap(rulesetList, fromPosition, toPosition)
        MmkvManager.encodeRoutingRulesets(rulesetList)
    }

    /**
     * Swap subscriptions.
     * @param fromPosition The position to swap from.
     * @param toPosition The position to swap to.
     */
    fun swapSubscriptions(fromPosition: Int, toPosition: Int) {
        val subsList = decodeSubsList()
        if (subsList.isEmpty()) return

        Collections.swap(subsList, fromPosition, toPosition)
        MmkvManager.encodeSubsList(subsList)
    }

    /**
     * Get server via remarks.
     * @param remarks The remarks of the server.
     * @return The ProfileItem.
     */
    fun getServerViaRemarks(remarks: String?): ProfileItem? {
        if (remarks.isNullOrEmpty()) {
            return null
        }
        // A SEQUENCE, so the scan STOPS at the match. `mapNotNull` on a List is eager: it decoded
        // every сервер on the device — one MMKV read plus one Gson parse each, ~5 µs a profile on a
        // desktop JVM and several times that on a phone — and only then looked for the name, even
        // when the answer was the first row. This is called per routing tag and per chain step
        // while a config is being built, so the waste multiplied by the number of lookups.
        return decodeAllServerList()
            .asSequence()
            .mapNotNull { guid -> decodeServerConfig(guid) }
            .firstOrNull { it.remarks == remarks }
    }

    /**
     * Collects non-empty profile remarks while excluding specific config types.
     */
    fun getProfileRemarks(excludeConfigTypes: Set<EConfigType> = setOf(EConfigType.CUSTOM)): List<String> {
        return decodeAllServerList()
            .asSequence()
            .mapNotNull { guid -> decodeServerConfig(guid) }
            .filter { profile -> profile.configType !in excludeConfigTypes }
            .map { it.remarks.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    /**
     * Removes the subscription.
     * If there are no remaining subscriptions,
     * it creates a new default subscription to ensure that ungroup
     **/
    fun removeSubscriptionWithDefault(subid: String) {
        SubscriptionUpdater.cancelOne(subId = subid)
        // Remove the subscription
        removeSubscription(subid)

        // After removal, check if there are any subscriptions left. If not, create a default subscription.
        val subsList2 = decodeSubsList()
        if (subsList2.isNotEmpty()) {
            return
        }

        // NO PLACEHOLDER NAME. This is the linkless container that holds hand-added servers, not a
        // подписка, and it is addressed by DEFAULT_SUBSCRIPTION_ID — nothing looks it up by its
        // remark. Storing the English word «Default» in it only gave every display path one more
        // string to filter out, and one of them always forgot. Blank is what it is.
        val defaultSub = SubscriptionItem()
        encodeSubscription(DEFAULT_SUBSCRIPTION_ID, defaultSub)
    }

    /**
     * Get the SOCKS port.
     * @return The SOCKS port.
     */
    fun getSocksPort(): Int {
        val port =
            if (IsDynamicSocksPort()) {
                runtimeSocksPort ?: refreshRuntimeSocksPort()
            } else {
                Utils.parseInt(MmkvManager.decodeSettingsString(AppConfig.PREF_SOCKS_PORT), AppConfig.PORT_SOCKS.toInt())
            }
        return port ?: AppConfig.PORT_SOCKS.toInt()
    }

    @Synchronized
    fun refreshRuntimeSocksPort(): Int? {
        if (IsDynamicSocksPort()) {
            runtimeSocksPort = generateRandomSocksPort()
            return runtimeSocksPort
        }
        return null
    }

    fun getSocksUsername(): String? {
        return MmkvManager.decodeSettingsString(AppConfig.PREF_SOCKS_USERNAME)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun getSocksPassword(): String? {
        return MmkvManager.decodeSettingsString(AppConfig.PREF_SOCKS_PASSWORD)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Get the SOCKS port used for LAN/hotspot proxy sharing.
     *
     * This is a separate, dedicated port from [getSocksPort]: the loopback socks inbound
     * (used by the local tun bridge) stays bound to 127.0.0.1, while the shared inbound is
     * bound to 0.0.0.0. Binding 0.0.0.0 would otherwise subsume the loopback bind, so the two
     * inbounds MUST use different ports.
     */
    fun getSocksSharePort(): Int {
        return Utils.parseInt(
            MmkvManager.decodeSettingsString(AppConfig.PREF_SOCKS_SHARE_PORT),
            AppConfig.PORT_SOCKS_SHARE.toInt()
        )
    }

    /**
     * Ensure SOCKS5 credentials exist, generating and persisting them when empty.
     *
     * The LAN/hotspot inbound is bound to 0.0.0.0, so it must NEVER be reachable without
     * authentication (an open relay). When sharing is enabled with empty credentials this
     * generates a login (dep_ + 6 hex) and password (12 hex) and persists them, mirroring the
     * generator used by the local proxy screen. Returns the effective (user, pass) pair.
     */
    @Synchronized
    fun ensureSocksShareCredentials(): Pair<String, String> {
        var user = getSocksUsername()
        var pass = getSocksPassword()
        if (user.isNullOrEmpty() || pass.isNullOrEmpty()) {
            user = "dep_" + randomHex(6)
            pass = randomHex(12)
            MmkvManager.encodeSettings(AppConfig.PREF_SOCKS_USERNAME, user)
            MmkvManager.encodeSettings(AppConfig.PREF_SOCKS_PASSWORD, pass)
        }
        return user to pass
    }

    private fun randomHex(length: Int): String {
        val chars = "0123456789abcdef"
        return buildString(length) {
            repeat(length) { append(chars[Random.nextInt(chars.length)]) }
        }
    }

    /**
     * Get the HTTP port.
     * @return The HTTP port.
     */
    fun getHttpPort(): Int {
        return getSocksPort() + if (Utils.isXray()) 0 else 1
    }

    /**
     * Whether the stable per-install device id (HWID) is attached to subscription
     * and backend requests. Default TRUE.
     */
    fun isSendHwid(): Boolean {
        return MmkvManager.decodeSettingsBool(AppConfig.PREF_SEND_HWID, true)
    }

    /**
     * Whether a notification is posted while a subscription is being refreshed. Default TRUE.
     */
    fun isNotifyOnSubscriptionUpdate(): Boolean {
        return MmkvManager.decodeSettingsBool(AppConfig.PREF_SUB_NOTIFY_ON_UPDATE, true)
    }

    /**
     * Whether every subscription is refreshed once when the app starts. Default FALSE.
     */
    fun isUpdateSubscriptionOnLaunch(): Boolean {
        return MmkvManager.decodeSettingsBool(AppConfig.PREF_SUB_UPDATE_ON_LAUNCH, false)
    }

    /**
     * Whether the latency test runs once when the app starts. Default FALSE.
     */
    fun isPingOnLaunch(): Boolean {
        return MmkvManager.decodeSettingsBool(AppConfig.PREF_PING_ON_LAUNCH, false)
    }

    /**
     * Whether the latency test runs after a subscription refresh. Default TRUE.
     */
    fun isPingOnSubscriptionUpdate(): Boolean {
        return MmkvManager.decodeSettingsBool(AppConfig.PREF_PING_ON_UPDATE, true)
    }

    /**
     * Global fallback User-Agent for subscription fetches, or null when the user has not set one —
     * a subscription's own User-Agent and then the operator default still apply, in that order.
     */
    fun getSubscriptionUserAgent(): String? {
        return MmkvManager.decodeSettingsString(AppConfig.PREF_SUB_USER_AGENT)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Server list order chosen on the provider screen, one of `AppConfig.SERVER_SORT_*`.
     */
    fun getServerSortOrder(): String {
        return MmkvManager.decodeSettingsString(AppConfig.PREF_SERVER_SORT_ORDER)
            ?.takeIf { it.isNotEmpty() } ?: AppConfig.SERVER_SORT_DEFAULT
    }

    /**
     * Reorders every stored server list to match [getServerSortOrder].
     *
     * Order is a property of the stored guid list — every screen renders servers as stored, and a
     * subscription refresh rewrites the list in the provider's order — so the choice is applied to
     * storage instead of at render time. That is also why
     * [AppConfig.SERVER_SORT_DEFAULT] does nothing here: the provider's own order is what the next
     * refresh restores.
     */
    fun applyServerSortOrder() {
        val order = getServerSortOrder()
        if (order == AppConfig.SERVER_SORT_DEFAULT) return

        val subIds = decodeSubsList()
        // Ungrouped servers live under the default subscription, which is not always in the list.
        if (!subIds.contains(DEFAULT_SUBSCRIPTION_ID)) {
            subIds.add(DEFAULT_SUBSCRIPTION_ID)
        }

        // ONE FENCED READ-MODIFY-WRITE PER ПОДПИСКА. This runs in `:bg` right after a refresh and in
        // the interface process at start-up, and it re-reads and rewrites every list — exactly the
        // shape that loses a concurrent write. See [MmkvManager.inServerListTransaction].
        subIds.forEach { subId -> MmkvManager.inServerListTransaction {
            val guids = MmkvManager.decodeServerList(subId)
            if (guids.size < 2) return@inServerListTransaction

            // Each key is read once and then sorted, never re-read per comparison: this runs on the
            // main thread during startup and one key costs an MMKV read plus a JSON parse.
            val sorted = when (order) {
                AppConfig.SERVER_SORT_PING -> guids
                    .map { guid ->
                        // Untested and unreachable servers sink to the bottom instead of leading it.
                        val delay = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: -1L
                        guid to if (delay <= 0L) Long.MAX_VALUE else delay
                    }
                    .sortedBy { it.second }
                    .map { it.first }

                AppConfig.SERVER_SORT_NAME -> guids
                    .map { guid ->
                        guid to decodeServerConfig(guid)?.remarks?.lowercase(Locale.getDefault()).orEmpty()
                    }
                    .sortedBy { it.second }
                    .map { it.first }

                else -> return@inServerListTransaction
            }
            MmkvManager.encodeServerList(sorted.toMutableList(), subId)
        } }
    }

    /**
     * Soft memory cap (in megabytes) requested for the core runtime. Default 100.
     * Only meaningful when [isMemoryLimitEnabled] is true.
     */
    fun getMemoryLimit(): Int {
        return Utils.parseInt(MmkvManager.decodeSettingsString(AppConfig.PREF_MEMORY_LIMIT), 100)
    }

    /**
     * Whether the memory limit should be enforced. Default TRUE.
     */
    fun isMemoryLimitEnabled(): Boolean {
        return MmkvManager.decodeSettingsBool(AppConfig.PREF_MEMORY_LIMIT_ENABLED, true)
    }

    private fun IsDynamicSocksPort(): Boolean {
        return MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_SOCKS_PORT, false)
    }

    private fun generateRandomSocksPort(): Int {
        return Random.nextInt(10000, 65535)
    }

    /**
     * Initialize assets.
     * @param context The application context.
     * @param assets The AssetManager.
     */
    fun initAssets(context: Context, assets: AssetManager) {
        val extFolder = Utils.userAssetPath(context)

        try {
            val geo = arrayOf(AppConfig.GEOSITE_DAT, AppConfig.GEOIP_DAT, AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT)
            assets.list("")
                ?.filter { geo.contains(it) }
                ?.filter { !File(extFolder, it).exists() }
                ?.forEach {
                    val target = File(extFolder, it)
                    assets.open(it).use { input ->
                        FileOutputStream(target).use { output ->
                            input.copyTo(output)
                        }
                    }
                    LogUtil.i(AppConfig.TAG, "Copied from apk assets folder to ${target.absolutePath}")
                }
        } catch (e: Exception) {
            LogUtil.e(ANG_PACKAGE, "asset copy failed", e)
        }
    }

    /**
     * Get domestic DNS servers from preference.
     * @return A list of domestic DNS servers.
     */
    fun getDomesticDnsServers(): List<String> {
        val domesticDns =
            MmkvManager.decodeSettingsString(AppConfig.PREF_DOMESTIC_DNS) ?: AppConfig.DNS_DIRECT
        val ret = domesticDns.split(",").filter { Utils.isPureIpAddress(it) || Utils.isCoreDNSAddress(it) }
        if (ret.isEmpty()) {
            return listOf(AppConfig.DNS_DIRECT)
        }
        return ret
    }

    /**
     * Get remote DNS servers from preference.
     * @return A list of remote DNS servers.
     */
    fun getRemoteDnsServers(): List<String> {
        val remoteDns =
            MmkvManager.decodeSettingsString(AppConfig.PREF_REMOTE_DNS) ?: AppConfig.DNS_PROXY
        val ret = remoteDns.split(",").filter { Utils.isPureIpAddress(it) || Utils.isCoreDNSAddress(it) }
        if (ret.isEmpty()) {
            return listOf(AppConfig.DNS_PROXY)
        }
        return ret
    }

    /**
     * Get VPN DNS servers from preference.
     * @return A list of VPN DNS servers.
     */
    fun getVpnDnsServers(): List<String> {
        val vpnDns = MmkvManager.decodeSettingsString(AppConfig.PREF_VPN_DNS) ?: AppConfig.DNS_VPN
        val ret = vpnDns.split(",").filter { Utils.isPureIpAddress(it) }
        if (ret.isEmpty()) {
            // Safeguard: never hand the VPN interface an empty DNS list. These values feed
            // CoreVpnService.addDnsServer(); an empty result (e.g. the blank "custom" DNS
            // preset, or a DoH URL / bare hostname that isn't a pure IP) would leave the
            // tunnel with NO resolver, so every domain fails ("no internet") even though
            // IP-level ping still succeeds. Fall back to the default, mirroring
            // getRemoteDnsServers()/getDomesticDnsServers().
            return listOf(AppConfig.DNS_VPN)
        }
        return ret
    }

    /**
     * Get delay test URL.
     * @param second Whether to use the second URL.
     * @return The delay test URL.
     */
    fun getDelayTestUrl(second: Boolean = false): String {
        return if (second) {
            AppConfig.DELAY_TEST_URL2
        } else {
            MmkvManager.decodeSettingsString(AppConfig.PREF_DELAY_TEST_URL)
                ?: AppConfig.DELAY_TEST_URL
        }
    }

    /**
     * Returns the user-selected connection-test (ping) method.
     */
    fun getPingMethod(): PingMethod =
        PingMethod.fromPref(MmkvManager.decodeSettingsString(AppConfig.PREF_PING_METHOD))

    /**
     * Get real ping concurrency.
     * @return The number of concurrent real-ping tests (clamped to 1..64).
     */
    fun getRealPingConcurrency(): Int {
        val value = MmkvManager.decodeSettingsString(AppConfig.PREF_REAL_PING_CONCURRENCY)?.toIntOrNull() ?: 16
        return value.coerceIn(1, 128)
    }

    /**
     * Get the locale the app renders in.
     *
     * departament ships **one** master locale — Russian, in `res/values/` — so the picker offers
     * Системный and Русский and nothing else (`R.array.language_select`). The retired options
     * («English» and the vendored upstream locales, whose `values-*` folders are gone) resolved to
     * translations that no longer exist, i.e. a Russian app wearing a foreign locale for dates,
     * numbers and layout direction.
     *
     * An install that stored one of those codes before they were retired is **migrated once, here**,
     * on the first read after the update: it lands on Системный, which is what the settings row
     * already shows for a value the array no longer contains. Rewriting the stored value rather than
     * only reinterpreting it keeps the preference and the row in agreement, so the next writer of
     * this key cannot resurrect a locale the app does not ship.
     *
     * @return The locale.
     */
    fun getLocale(): Locale {
        val stored = MmkvManager.decodeSettingsString(AppConfig.PREF_LANGUAGE) ?: Language.AUTO.code
        val language = when (Language.fromCode(stored)) {
            Language.RUSSIAN -> Language.RUSSIAN
            else -> Language.AUTO
        }
        if (stored != language.code) {
            MmkvManager.encodeSettings(AppConfig.PREF_LANGUAGE, language.code)
        }

        return when (language) {
            Language.RUSSIAN -> Locale.forLanguageTag("ru")
            else -> Utils.getSysLocale()
        }
    }

    /**
     * Set night mode.
     */
    fun setNightMode() {
        when (MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, "0")) {
            "0" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            "1" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "2" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
    }

    /**
     * Retrieves the currently selected VPN interface address configuration.
     * This method reads the user's preference for VPN interface addressing and returns
     * the corresponding configuration containing IPv4 and IPv6 addresses.
     *
     * @return The selected VpnInterfaceAddressConfig instance, or the default configuration
     *         if no valid selection is found or if the stored index is invalid.
     */
    fun getCurrentVpnInterfaceAddressConfig(): VpnInterfaceAddressConfig {
        val selectedIndex = MmkvManager.decodeSettingsString(AppConfig.PREF_VPN_INTERFACE_ADDRESS_CONFIG_INDEX, "0")?.toInt()
        return VpnInterfaceAddressConfig.getConfigByIndex(selectedIndex ?: 0)
    }

    /**
     * Get the VPN MTU from settings, defaulting to AppConfig.VPN_MTU.
     */
    fun getVpnMtu(): Int {
        return Utils.parseInt(MmkvManager.decodeSettingsString(AppConfig.PREF_VPN_MTU), AppConfig.VPN_MTU)
    }

    /**
     * Check if HEV TUN is being used.
     *
     * Two conditions, and the second one is not cosmetic. The user's preference decides whether we
     * WANT the hev-socks5-tunnel bridge; [TProxyService.isAvailable] decides whether the process
     * CAN have it. If `libhev-socks5-tunnel.so` is not in this APK's ABI split, or refuses to load
     * for any other reason, answering "yes" here builds a config with no tun inbound, hands the
     * core a tun fd of 0, and then dies loading the library — a VPN that neither carries traffic
     * nor survives the attempt.
     *
     * Answering "no" instead routes the start down the path the app already takes whenever a user
     * turns the preference off: the core builds its own `tun` inbound ([CoreConfigManager.needTun]),
     * receives the real fd, and owns the tunnel. Degraded, not dead.
     *
     * The preference is checked first so the library is never loaded by a process that had already
     * opted out of it.
     *
     * @return True if HEV TUN is used, false otherwise.
     */
    fun isUsingHevTun(): Boolean {
        if (!MmkvManager.decodeSettingsBool(AppConfig.PREF_USE_HEV_TUNNEL, true)) return false
        return TProxyService.isAvailable
    }

    /**
     * Check if VPN mode is enabled.
     * @return True if VPN mode is enabled, false otherwise.
     */
    fun isVpnMode(): Boolean {
        val mode = MmkvManager.decodeSettingsString(AppConfig.PREF_MODE)
        return mode == null || mode == VPN
    }

    /**
     *  Check if process routing can be used.
     */
    fun canUseProcessRouting(): Boolean {
        // Android 10+
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }

        // Must xray tun
        if (isUsingHevTun()) {
            return false
        }

        // Must have route only enabled
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_ROUTE_ONLY_ENABLED, false) == false) {
            return false
        }

        return true
    }

    /**
     * Ensure default settings are present in MMKV.
     */
    private fun ensureDefaultSettings() {
        // Write defaults in the exact order requested by the user
        ensureDefaultValue(AppConfig.PREF_MODE, VPN)
        ensureDefaultValue(AppConfig.PREF_VPN_DNS, AppConfig.DNS_VPN)
        ensureDefaultValue(AppConfig.PREF_VPN_MTU, AppConfig.VPN_MTU.toString())
        ensureDefaultValue(AppConfig.PREF_SOCKS_PORT, AppConfig.PORT_SOCKS)
        ensureDefaultValue(AppConfig.PREF_REMOTE_DNS, AppConfig.DNS_PROXY)
        ensureDefaultValue(AppConfig.PREF_DOMESTIC_DNS, AppConfig.DNS_DIRECT)
        ensureDefaultValue(AppConfig.PREF_DELAY_TEST_URL, AppConfig.DELAY_TEST_URL)
        ensureDefaultValue(AppConfig.PREF_PING_METHOD, PingMethod.PROXIED_REAL_DELAY.prefValue)
        // «в оформлении надо чтобы 1 была кнопка как в системе и чтобы оно включалось по умолчанию».
        //
        // "0" is FOLLOW_SYSTEM (setNightMode above). It was "2", pinned dark, and that value is the
        // reason the app could never follow the phone: ensureDefaultValue writes on first launch
        // whenever the key is absent, so "nothing has been chosen yet" stopped existing after the
        // very first run and setNightMode's own "0" fallback became unreachable code.
        //
        // ONLY FRESH INSTALLS MOVE. ensureDefaultValue writes nothing over an existing value, so
        // anyone already running keeps exactly what they have. That is also the honest limit of
        // this change: a user who never opened the row and a user who deliberately chose «Тёмная»
        // both hold "2" and cannot be told apart, so migrating the existing ones would be
        // indistinguishable from overriding a deliberate choice. It is not attempted.
        ensureDefaultValue(AppConfig.PREF_UI_MODE_NIGHT, "0")
        ensureDefaultValue(AppConfig.PREF_IP_API_URL, AppConfig.IP_API_URL)
        ensureDefaultValue(AppConfig.PREF_HEV_TUNNEL_RW_TIMEOUT, AppConfig.HEVTUN_RW_TIMEOUT)
        ensureDefaultValue(AppConfig.PREF_MUX_CONCURRENCY, "8")
        ensureDefaultValue(AppConfig.PREF_MUX_XUDP_CONCURRENCY, "8")
        ensureDefaultValue(AppConfig.PREF_FRAGMENT_LENGTH, "50-100")
        ensureDefaultValue(AppConfig.PREF_FRAGMENT_INTERVAL, "10-20")
    }

    private fun ensureDefaultValue(key: String, default: String) {
        if (MmkvManager.decodeSettingsString(key).isNullOrEmpty()) {
            MmkvManager.encodeSettings(key, default)
        }
    }

    /** Умолчание апстрима для «DNS для прямых соединений»: AliDNS, у v2rayNG так с 1.6 до сих пор. */
    private const val UPSTREAM_DOMESTIC_DNS = "223.5.5.5"

    /**
     * «DNS для прямых соединений» переезжает с китайского умолчания апстрима на Яндекс, один раз.
     *
     * [ensureDefaultValue] пишет умолчание в хранилище при первом запуске, поэтому у каждой
     * установки до этой версии, где строку не трогали, там лежит [UPSTREAM_DOMESTIC_DNS]. Меняется
     * ровно это значение: список, DoH, адрес с пробелом — выбор человека, и он остаётся. Отличить
     * «не трогал» от «сам вписал 223.5.5.5» нельзя, как и с темой выше, но здесь владелец решил
     * переводить: AliDNS в России своим выбором почти не бывает, а строка по-прежнему правится.
     *
     * Один раз — по отметке в хранилище: если потом кто-то вернёт себе 223.5.5.5 руками, следующий
     * запуск его не перепишет. Ожидаемых IP, которые пришлось бы менять заодно, у Android нет: ядро
     * получает geoip:cn только вместе с правилом geosite:cn, а в наборах departament его нет. Пара
     * на ПК — ConfigHandler.MigrateSimpleDnsDefaults.
     */
    internal fun migrateDomesticDnsDefaultOnce() {
        val migrationKey = "domestic_dns_yandex_migrated"
        if (MmkvManager.decodeSettingsBool(migrationKey, false)) {
            return
        }
        if (MmkvManager.decodeSettingsString(AppConfig.PREF_DOMESTIC_DNS) == UPSTREAM_DOMESTIC_DNS) {
            MmkvManager.encodeSettings(AppConfig.PREF_DOMESTIC_DNS, AppConfig.DNS_DIRECT)
        }
        MmkvManager.encodeSettings(migrationKey, true)
    }

    /**
     * SWITCHING THE SERVER BY ITSELF IS NOT A FEATURE OF THIS PRODUCT ANY MORE.
     *
     * > «нужно убрать функцию, что если сервер недоступен, то появляется уведомление что идёт поиск
     * > быстрейшего сервера, надо убрать в целом эту функцию о переключении на более быстрый сервер»
     *
     * What it was: seven seconds after a connect, the app probed the tunnel; two consecutive
     * failures and it announced «Подключение не отвечает. Переключаемся на самый быстрый рабочий
     * сервер…», measured every сервер and restarted the tunnel on whichever answered fastest. The
     * user's own choice was overridden, and the first they knew of it was the sentence.
     *
     * How it is off: the switch that armed it is gone from the settings screen, and this puts its
     * key back to `false` on every start where it is not already `false` — so an install that
     * stored `true`, or one restored from a backup that carried it, is switched off too, and the
     * health check is never armed at all. Not a one-shot migration on purpose: the value must not
     * be able to come back from anywhere.
     *
     * IT NO LONGER WRITES WHEN THERE IS NOTHING TO WRITE. The unconditional `encode` put the same
     * `false` into a MULTI_PROCESS_MODE store at every launch — an mmap append plus the store's
     * cross-process lock, on the startup path, for a value that has not changed since the build
     * that retired the feature. Reading first is one lookup, and on every install past the first
     * launch that is the whole cost.
     *
     * What is NOT touched, because the owner asked for the switching to go and nothing else
     * (`PORT-DELTA.md` П-26): the latency measurement itself, all four check methods, «обновить»,
     * the 30-second probe that draws the «мс» figure on Главная, and picking a сервер by hand.
     */
    private fun retireAutoFallback() {
        if (!MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_FALLBACK, false)) return
        MmkvManager.encodeSettings(AppConfig.PREF_AUTO_FALLBACK, false)
    }

    /**
     * Clears the raw-template backlog left by the builds that never deleted from that store.
     *
     * Every subscription refresh replaces a провайдер's whole server set, and until now the raw
     * config of each replaced сервер stayed behind — so the store grew by a full copy of the
     * server list on every update, for the life of the install, with nothing able to read those
     * entries again. The delete paths carry the raw entry with its profile now
     * ([MmkvManager.removeServer] and friends); this clears what accumulated before that, once.
     */
    private fun pruneOrphanServerRawOnce() {
        val migrationKey = "server_raw_orphans_pruned"
        if (MmkvManager.decodeSettingsBool(migrationKey, false)) {
            return
        }
        val removed = MmkvManager.pruneOrphanServerRaw()
        if (removed > 0) {
            LogUtil.i(AppConfig.TAG, "Pruned $removed orphaned raw server configs")
        }
        MmkvManager.encodeSettings(migrationKey, true)
    }

    private fun migrateHysteria2PinSHA256() {
        // Check if migration has already been done
        val migrationKey = "hysteria2_pin_sha256_migrated"
        if (MmkvManager.decodeSettingsBool(migrationKey, false)) {
            return
        }

        val serverList = decodeAllServerList()

        for (guid in serverList) {
            val profile = decodeServerConfig(guid) ?: continue
            if (profile.configType != EConfigType.HYSTERIA2) {
                continue
            }
            if (profile.pinSHA256.isNullOrEmpty() || !profile.pinnedCA256.isNullOrEmpty()) {
                continue
            }
            profile.pinnedCA256 = profile.pinSHA256
            profile.pinSHA256 = null
            MmkvManager.encodeServerConfig(guid, profile)
        }

        MmkvManager.encodeSettings(migrationKey, true)
    }

    /**
     * Migrates server list from legacy KEY_ANG_CONFIGS to subscription-based storage.
     * This method should be called once during app initialization after the storage structure change.
     * Servers are grouped by their subscriptionId into respective subscription's serverList.
     * Servers without subscription are moved to the default subscription.
     * After migration, KEY_ANG_CONFIGS is removed.
     */
    private fun migrateServerListToSubscriptions() {
        // Check if migration has already been done
        val migrationKey = "server_list_to_subscriptions_migrated"
        if (MmkvManager.decodeSettingsBool(migrationKey, false)) {
            return
        }

        // Ensure default subscription exists before migration
        ensureDefaultSubscription()

        // Read existing server list from legacy KEY_ANG_CONFIGS
        val oldJson = MmkvManager.readLegacyServerList()
        if (oldJson.isNullOrBlank()) {
            // No data to migrate, mark as done
            MmkvManager.encodeSettings(migrationKey, true)
            return
        }

        val guids = JsonUtil.fromJsonSafe(oldJson, Array<String>::class.java) ?: run {
            MmkvManager.encodeSettings(migrationKey, true)
            return
        }

        val subscriptionServerMap = mutableMapOf<String, MutableList<String>>()

        // Group servers by subscription (use default subscription for empty subscriptionId)
        guids.forEach { guid ->
            val config = decodeServerConfig(guid) ?: return@forEach
            val subId = config.subscriptionId.ifEmpty { DEFAULT_SUBSCRIPTION_ID }

            subscriptionServerMap.getOrPut(subId) { mutableListOf() }.add(guid)
        }

        // Update each subscription's serverList (including default subscription)
        subscriptionServerMap.forEach { (subId, serverGuids) ->
            MmkvManager.encodeServerList(serverGuids, subId)
        }


        // Mark migration as complete
        MmkvManager.encodeSettings(migrationKey, true)
    }

    /**
     * Ensures the default subscription exists for ungrouped servers.
     * This subscription is used internally to store servers without a subscription.
     * Made public for migration in SettingsManager.
     */
    private fun ensureDefaultSubscription() {
        if (decodeSubscription(DEFAULT_SUBSCRIPTION_ID) == null) {
            // Blank, not «Default» — see the note in removeSubscriptionWithDefault.
            val defaultSub = SubscriptionItem()
            encodeSubscription(DEFAULT_SUBSCRIPTION_ID, defaultSub)

            // Move top
            val subsList = decodeSubsList()
            if (subsList.count() > 1) {
                swapSubscriptions(0, subsList.count() - 1)
            }
        }
    }

}
