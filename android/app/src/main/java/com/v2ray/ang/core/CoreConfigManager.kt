package com.v2ray.ang.core

import android.content.Context
import android.text.TextUtils
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.ConfigResult
import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.enums.BalancerStrategyType
import com.v2ray.ang.enums.CoreResolvedType
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.template.TemplateManager
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.PackageUidResolver
import com.v2ray.ang.util.Utils
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object CoreConfigManager {
    private var initConfigCache: String? = null
    private var initConfigCacheWithTun: String? = null

    //region get config function

    /**
     * Build the runtime configuration for normal startup.
     */
    fun getV2rayConfig(context: Context, guid: String): ConfigResult {
        try {
            val configContext = CoreConfigContextBuilder.build(context, guid)
                ?: return ConfigResult(status = false, guid = guid, errorMessage = "Failed to build config context")
            if (configContext.isCustom) {
                return buildV2rayCustomConfig(configContext)
            }
            val v2rayConfig = buildUnifiedConfig(configContext)
            // THE PRE-RESOLUTION BELONGS TO THE CONNECT AND TO NOTHING ELSE. It used to be the last
            // line of [buildUnifiedConfig], which the latency test also goes through — and the very
            // next thing the test does is `postProcessForSpeedtest`, whose `v2rayConfig.dns = null`
            // throws away the only thing the lookups produced. So «Проверить все» over 100 серверов
            // performed 100 blocking DNS queries whose answers were discarded three lines later,
            // and the native `measureOutboundDelay` then resolved every one of them again itself.
            resolveOutboundDomainsToHosts(v2rayConfig)
            return toConfigResult(configContext, v2rayConfig)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to get V2ray config", e)
            return ConfigResult(
                status = false,
                guid = guid,
                errorMessage = "Failed to get V2ray config: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    /**
     * Build a lightweight configuration for latency testing.
     *
     * The core flow is reused, then non-essential sections are removed.
     */
    fun getV2rayConfig4Speedtest(context: Context, guid: String): ConfigResult {
        try {
            val configContext = CoreConfigContextBuilder.build(context, guid)
                ?: return ConfigResult(status = false, guid = guid, errorMessage = "Failed to build config context")
            if (configContext.isCustom) {
                return buildV2rayCustomConfig4Speedtest(configContext)
            }
            val v2rayConfig = buildUnifiedConfig(configContext)
            postProcessForSpeedtest(v2rayConfig)

            return toConfigResult(configContext, v2rayConfig)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to get V2ray config for speedtest", e)
            return ConfigResult(
                status = false,
                guid = guid,
                errorMessage = "Failed to get V2ray config for speedtest: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    /**
     * Build configuration for custom profiles.
     */
    private fun buildV2rayCustomConfig(configContext: CoreConfigContext): ConfigResult {
        val context = configContext.context
        // Hidden/locked templates are stored encrypted; decodeRuntimeRaw transparently
        // decrypts them and returns non-locked raw configs unchanged. All the template's
        // routing/DNS/obfuscation rules are applied as-authored from this point on.
        // Defensive: if template/keystore decoding ever throws (rare OEM Keystore breakage,
        // corrupt payload), fall back to the plain stored raw so an ordinary/custom config can
        // never be blocked from connecting by the template layer.
        val raw = try {
            TemplateManager.decodeRuntimeRaw(configContext.guid)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "buildV2rayCustomConfig: template decode failed, using plain raw", e)
            MmkvManager.decodeServerRaw(configContext.guid)
        } ?: return ConfigResult(status = false, guid = configContext.guid, errorMessage = "Custom config is empty")
        // Parse once up-front so we can sanitize/validate even when tun is not needed.
        // A malformed or non-Xray payload must never reach the native core (an unrecoverable
        // native panic there kills the whole app process — the "снова произошёл сбой" dialog).
        val json = JsonUtil.parseString(raw)?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return ConfigResult(
                status = false,
                guid = configContext.guid,
                errorMessage = "Custom config is not a JSON object"
            )

        // Remnawave XRAY_JSON templates carry a root-level "remnawave" metadata object (and
        // may carry other non-Xray keys). libv2ray/Xray can choke on unexpected top-level
        // keys, so keep only valid Xray root keys before handing the config to the core.
        sanitizeXrayRootKeys(json, configContext.guid)

        // A config with no outbounds can crash the core on start; fail cleanly instead.
        val outboundsJson = json.get("outbounds")?.takeIf { it.isJsonArray }?.asJsonArray
        if (outboundsJson == null || outboundsJson.size() == 0) {
            return ConfigResult(
                status = false,
                guid = configContext.guid,
                errorMessage = "Custom config has no outbounds"
            )
        }

        val result = JsonUtil.toJsonPretty(json)?.let { ConfigResult(true, configContext.guid, it) }
            ?: ConfigResult(true, configContext.guid, raw)
        if (!needTun()) {
            return result
        }

        // Check whether package names need to be replaced with UIDs
        if (SettingsManager.canUseProcessRouting()) {
            val rulesJson = json.get("routing")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: JsonArray()

            for (elem in rulesJson) {
                val rule = elem.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val process = rule.get("process")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
                val packages = process.mapNotNull {
                    it.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                }.takeIf { it.isNotEmpty() } ?: continue
                val uids = PackageUidResolver.packageNamesToUids(context, packages).takeIf { it.isNotEmpty() } ?: continue

                rule.add("process", JsonArray().apply { uids.forEach { add(it) } })
            }
        }

        // check if tun inbound exists
        val inboundsJson = json.get("inbounds")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: JsonArray().also { json.add("inbounds", it) }
        val tunNotExists = inboundsJson.none { elem ->
            elem.isJsonObject && elem.asJsonObject.get("protocol")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString == "tun"
        }

        if (tunNotExists) {
            // add tun inbound from template
            val templateConfig = initV2rayConfig(configContext)
            templateConfig.inbounds.firstOrNull { it.tag == "tun" }?.let { inboundTun ->
                inboundTun.settings?.mtu = SettingsManager.getVpnMtu()
                // Only append a well-formed inbound object; a null element here would be handed
                // to the native core as `null` inside the inbounds array and can crash it.
                JsonUtil.parseString(JsonUtil.toJson(inboundTun))
                    ?.takeIf { it.isJsonObject }
                    ?.let { inboundsJson.add(it) }
            }
        }

        return JsonUtil.toJsonPretty(json)?.let { ConfigResult(true, configContext.guid, it) } ?: result
    }

    /**
     * Build a minimal, always-measurable configuration for latency testing of CUSTOM
     * (raw xray-json / locked template) profiles.
     *
     * The stored raw config is used for a real connection as-authored, but for a delay test
     * the full config often cannot be measured by the native `measureOutboundDelay`:
     *  - Balancer / "Auto" / "Hybrid" (Автовыбор) templates carry an `observatory` +
     *    `routing.balancers` block with multiple proxy outbounds. The native delay tester
     *    strips app features and has no observatory probe results, so it cannot pick a
     *    balancer member and returns -1 (or the request hangs until timeout).
     *  - xHTTP / Shadowsocks and other single-proxy templates ship with routing/dns/multiple
     *    outbounds (proxy + direct + block). If the proxy outbound is not first, the test
     *    request can fall through to `direct`/`freedom` and never exercise the proxy.
     *
     * This builder parses the raw config, keeps only `log` + `outbounds`, promotes the first
     * real proxy outbound to index 0, and drops routing/balancer/observatory/dns/inbounds so a
     * single measurable proxy remains. The result measures connectivity through the first usable
     * proxy server inside the config (which is exactly what the user wants for a per-server ping).
     */
    private fun buildV2rayCustomConfig4Speedtest(configContext: CoreConfigContext): ConfigResult {
        val guid = configContext.guid
        val raw = try {
            TemplateManager.decodeRuntimeRaw(guid)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "buildV2rayCustomConfig4Speedtest: template decode failed, using plain raw", e)
            MmkvManager.decodeServerRaw(guid)
        } ?: return ConfigResult(status = false, guid = guid, errorMessage = "Custom config is empty")

        val json = JsonUtil.parseString(raw)?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return ConfigResult(status = false, guid = guid, errorMessage = "Custom config is not a JSON object")

        sanitizeXrayRootKeys(json, guid)

        val outboundsJson = json.get("outbounds")?.takeIf { it.isJsonArray }?.asJsonArray
        if (outboundsJson == null || outboundsJson.size() == 0) {
            return ConfigResult(status = false, guid = guid, errorMessage = "Custom config has no outbounds")
        }

        // Promote the outbound that actually carries this template's traffic to index 0, so the delay
        // request goes through it once routing/balancer are removed.
        //
        // Which one that is has to come from the template's own routing: operator templates commonly
        // carry several proxy outbounds and select one with a rule, so a template whose FIRST proxy
        // outbound is a decoy would otherwise have its latency measured against a host that is not
        // its server — which never answers, so the profile appears unpingable. Fall back to the first
        // proxy outbound only when routing names nothing.
        val routedTag = effectiveOutboundTag(raw)
        val taggedIndex = routedTag?.let { tag ->
            outboundsJson.indexOfFirst { elem ->
                elem.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("tag")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString.equals(tag, ignoreCase = true)
            }
        } ?: -1
        val proxyIndex = if (taggedIndex >= 0) taggedIndex else outboundsJson.indexOfFirst { elem ->
            val protocol = elem.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("protocol")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString ?: return@indexOfFirst false
            isProxyProtocol(protocol)
        }
        if (proxyIndex > 0) {
            // Rebuild the array with the proxy outbound first, preserving the rest in order.
            val reordered = JsonArray()
            reordered.add(outboundsJson.get(proxyIndex))
            outboundsJson.forEachIndexed { i, elem -> if (i != proxyIndex) reordered.add(elem) }
            json.add("outbounds", reordered)
        } else if (proxyIndex < 0) {
            LogUtil.w(AppConfig.TAG, "Speedtest custom config $guid has no recognizable proxy outbound; measuring first outbound")
        }

        // Keep only what the delay tester needs: log + outbounds. Everything that can trip up
        // the native tester (balancer/observatory/routing/dns/inbounds/stats/policy/...) is dropped.
        val keysToRemove = json.keySet().filter { it != "log" && it != "outbounds" }
        keysToRemove.forEach { json.remove(it) }

        json.get("outbounds")?.asJsonArray?.forEach { elem ->
            (elem as? JsonObject)?.remove("mux")
        }

        val content = JsonUtil.toJsonPretty(json)
            ?: return ConfigResult(status = false, guid = guid, errorMessage = "Failed to serialize speedtest config")
        return ConfigResult(status = true, guid = guid, content = content)
    }

    /**
     * Tag of the outbound this raw config's routing actually sends ordinary traffic to, or null when
     * routing names none (or the payload cannot be modelled). Delegates to the same resolution the
     * rest of the app uses for a custom profile's server, so the delay probe and the displayed server
     * can never disagree about which outbound the profile is.
     */
    private fun effectiveOutboundTag(raw: String): String? {
        return try {
            JsonUtil.fromJsonSafe(raw, V2rayConfig::class.java)?.getProxyOutbound()?.tag
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "effectiveOutboundTag: could not model custom config: ${e.message}")
            null
        }
    }

    /**
     * True when the protocol string names a real proxy outbound (as opposed to
     * freedom/blackhole/dns/loopback helper outbounds).
     */
    private fun isProxyProtocol(protocol: String): Boolean {
        return EConfigType.entries.any {
            it != EConfigType.CUSTOM &&
                it != EConfigType.POLICYGROUP &&
                it != EConfigType.PROXYCHAIN &&
                it.name.equals(protocol, ignoreCase = true)
        }
    }

    /**
     * Valid Xray top-level configuration keys. Anything else (e.g. Remnawave's root-level
     * "remnawave" metadata object) is stripped before the config reaches the native core.
     */
    private val XRAY_ROOT_KEYS = setOf(
        "log", "dns", "inbounds", "outbounds", "routing", "policy",
        "api", "stats", "reverse", "observatory", "burstObservatory",
        "fakedns", "metrics"
    )

    /**
     * Remove non-Xray root keys from a parsed custom config in place.
     *
     * Remnawave XRAY_JSON subscriptions embed a root-level "remnawave" object (and possibly
     * other panel metadata). libv2ray/Xray may reject or mishandle unknown top-level keys,
     * which manifests as a process-killing native crash on start. Keeping only the known-good
     * Xray keys makes these templates safe to load. Ordinary/self-authored custom configs only
     * contain valid Xray keys, so this is a no-op for them.
     */
    private fun sanitizeXrayRootKeys(json: JsonObject, guid: String) {
        val unknownKeys = json.keySet().filter { it !in XRAY_ROOT_KEYS }
        if (unknownKeys.isNotEmpty()) {
            LogUtil.w(AppConfig.TAG, "Stripping non-Xray root keys from custom config $guid: $unknownKeys")
            unknownKeys.forEach { json.remove(it) }
        }
    }

    /**
     * Build one unified configuration for every non-custom profile type.
     *
     * The analyzed outbound plan is consumed in order and converted to concrete
     * outbounds before routing, DNS, and runtime extras are assembled.
     */
    private fun buildUnifiedConfig(configContext: CoreConfigContext): V2rayConfig {
        require(configContext.resolvedOutbounds.isNotEmpty()) { "resolvedOutbounds must not be empty for a non-CUSTOM context" }
        val primaryResolvedOutbound = configContext.resolvedOutbounds.first()

        val v2rayConfig = initV2rayConfig(configContext)
        v2rayConfig.log.loglevel = MmkvManager.decodeSettingsString(AppConfig.PREF_LOGLEVEL) ?: "warning"
        v2rayConfig.remarks = primaryResolvedOutbound.profile.remarks

        configureInbounds(v2rayConfig)

        if (v2rayConfig.outbounds.isNotEmpty()) {
            v2rayConfig.outbounds.removeAt(0)
        }
        val existingTags = v2rayConfig.outbounds.mapTo(mutableSetOf()) { it.tag }
        val policyGroupBalancerTags = mutableMapOf<String, String>()
        val balancerStrategies = mutableListOf<BalancerStrategy>()

        // resolvedOutbounds is a single ordered plan: index 0 is primary and must be prepended,
        // the rest are routing outbounds and can be appended.
        var primaryBuilt = false
        configContext.resolvedOutbounds.forEachIndexed { index, spec ->
            val built = buildOutbounds(
                resolvedOutbound = spec,
                prepend = index == 0,
                existingTags = existingTags,
                v2rayConfig = v2rayConfig,
                policyGroupBalancerTags = policyGroupBalancerTags,
                balancerStrategies = balancerStrategies,
            )
            if (index == 0) primaryBuilt = built
        }

        // A CONFIG WITHOUT THE PRIMARY OUTBOUND IS NOT A CONFIG, IT IS A LEAK.
        //
        // The template's placeholder `proxy` outbound was removed above, so at this point the only
        // outbounds a skipped primary leaves behind are `direct` (freedom) and `block` — and Xray
        // sends anything that matches no rule through the FIRST outbound in the list. The core would
        // have started, the shade would have said «Подключено», and every byte would have gone out
        // unproxied. Every skip in [buildOutbounds] is a `LogUtil.w` and a `return`, and each of them
        // could reach this state: a profile whose stored settings cannot be read (see
        // [CoreOutboundBuilder.convert]), a policy group whose members are all broken, a resolved
        // entry with no profiles at all.
        //
        // Failing here instead means «Не удалось подключиться» with the reason in «Журнал» — the
        // honest answer, and the only safe one.
        if (!primaryBuilt) {
            error("No usable outbound for «${primaryResolvedOutbound.profile.remarks}»: check the server's settings")
        }

        // User routing rules (policyGroupBalancerTags rewrites TAG_PROXY→balancer when main is POLICYGROUP).
        configureRouting(configContext, v2rayConfig, policyGroupBalancerTags)
        configureFakeDns(v2rayConfig)
        configureDns(v2rayConfig, policyGroupBalancerTags)
        configureLocalDns(v2rayConfig)

        // (added by getDns / getCustomLocalDns) to use the balancer, then add
        // the catch-all balancer rule.
        if (primaryResolvedOutbound.resolvedType == CoreResolvedType.POLICYGROUP) {
            if (v2rayConfig.routing.domainStrategy == "IPIfNonMatch") {
                v2rayConfig.routing.rules.add(
                    V2rayConfig.RoutingBean.RulesBean(
                        ip = arrayListOf("0.0.0.0/0", "::/0"),
                        balancerTag = AppConfig.TAG_BALANCER,
                    )
                )
            } else {
                v2rayConfig.routing.rules.add(
                    V2rayConfig.RoutingBean.RulesBean(
                        network = "tcp,udp",
                        balancerTag = AppConfig.TAG_BALANCER,
                    )
                )
            }
        }

        applyObservability(v2rayConfig, balancerStrategies)
        applySpeedDisabled(v2rayConfig)

        return v2rayConfig
    }

    /**
     * Convert one analyzed outbound entry into concrete outbounds and register
     * them to the runtime configuration.
     *
     * @return whether this entry actually put an outbound into the runtime configuration. Every
     *   refusal below is a `LogUtil.w` and a skip, which is right for a ROUTING entry — the rule
     *   falls back to the proxy — and fatal for the PRIMARY one, whose absence turns the config
     *   into `direct` first and every byte unproxied. The caller needs the two told apart, and it
     *   cannot ask `existingTags`: a policy group registers its MEMBERS' tags
     *   («proxy-proxy-1-…») and never its own, so its own tag is missing from that set even on a
     *   perfectly built group.
     */
    private fun buildOutbounds(
        resolvedOutbound: CoreConfigContext.ResolvedOutbound,
        prepend: Boolean,
        existingTags: MutableSet<String>,
        v2rayConfig: V2rayConfig,
        policyGroupBalancerTags: MutableMap<String, String>,
        balancerStrategies: MutableList<BalancerStrategy>,
    ): Boolean {
        if (resolvedOutbound.tag in existingTags) {
            LogUtil.w(AppConfig.TAG, "Resolved outbound tag '${resolvedOutbound.tag}' already exists, skipping duplicated entry")
            return false
        }

        return when (resolvedOutbound.resolvedType) {
            CoreResolvedType.NORMAL -> handleNormalResolvedOutbound(
                resolvedOutbound = resolvedOutbound,
                prepend = prepend,
                existingTags = existingTags,
                v2rayConfig = v2rayConfig,
            )

            CoreResolvedType.PROXYCHAIN -> handleProxyChainResolvedOutbound(
                resolvedOutbound = resolvedOutbound,
                prepend = prepend,
                existingTags = existingTags,
                v2rayConfig = v2rayConfig,
            )

            CoreResolvedType.POLICYGROUP -> handlePolicyGroupResolvedOutbound(
                resolvedOutbound = resolvedOutbound,
                prepend = prepend,
                existingTags = existingTags,
                v2rayConfig = v2rayConfig,
                policyGroupBalancerTags = policyGroupBalancerTags,
                balancerStrategies = balancerStrategies,
            )
        }
    }

    /**
     * Build and insert a single-node outbound entry.
     */
    private fun handleNormalResolvedOutbound(
        resolvedOutbound: CoreConfigContext.ResolvedOutbound,
        prepend: Boolean,
        existingTags: MutableSet<String>,
        v2rayConfig: V2rayConfig,
    ): Boolean {
        val profile = resolvedOutbound.resolvedProfiles.firstOrNull() ?: run {
            LogUtil.w(AppConfig.TAG, "NORMAL resolved outbound '${resolvedOutbound.tag}' has empty resolvedProfiles, skipping")
            return false
        }
        val outbound = convertProfile2Outbound(profile) ?: run {
            LogUtil.w(AppConfig.TAG, "Could not convert NORMAL resolved outbound '${resolvedOutbound.tag}' profile to outbound, skipping")
            return false
        }
        outbound.tag = resolvedOutbound.tag
        if (prepend) {
            v2rayConfig.outbounds.add(0, outbound)
        } else {
            v2rayConfig.outbounds.add(outbound)
        }
        existingTags.add(resolvedOutbound.tag)
        return true
    }

    /**
     * Build and insert a multi-hop chain entry.
     */
    private fun handleProxyChainResolvedOutbound(
        resolvedOutbound: CoreConfigContext.ResolvedOutbound,
        prepend: Boolean,
        existingTags: MutableSet<String>,
        v2rayConfig: V2rayConfig,
    ): Boolean {
        val chainOutbounds = resolvedOutbound.resolvedProfiles
            .mapNotNull { convertProfile2Outbound(it) }
            .toMutableList()
        if (chainOutbounds.isEmpty()) {
            LogUtil.w(AppConfig.TAG, "PROXYCHAIN resolved outbound '${resolvedOutbound.tag}' has no valid profiles, skipping")
            return false
        }
        if (chainOutbounds.size == 1) {
            val outbound = chainOutbounds.first()
            outbound.tag = resolvedOutbound.tag
            if (prepend) {
                v2rayConfig.outbounds.add(0, outbound)
            } else {
                v2rayConfig.outbounds.add(outbound)
            }
            existingTags.add(resolvedOutbound.tag)
            return true
        }

        val chainTags = chainOutbounds.mapIndexed { index, _ ->
            if (index == 0) {
                resolvedOutbound.tag
            } else {
                "${AppConfig.TAG_PROXY}-${resolvedOutbound.tag}-$index"
            }
        }
        if (chainTags.any { it in existingTags }) {
            LogUtil.w(
                AppConfig.TAG,
                "PROXYCHAIN resolved outbound '${resolvedOutbound.tag}' has colliding hop tags, skipping"
            )
            return false
        }

        chainOutbounds.forEachIndexed { index, outbound ->
            outbound.tag = chainTags[index]
        }
        for (i in 0 until chainOutbounds.size - 1) {
            chainOutbounds[i].ensureSockopt().dialerProxy = chainOutbounds[i + 1].tag
        }

        if (prepend) {
            v2rayConfig.outbounds.addAll(0, chainOutbounds)
        } else {
            v2rayConfig.outbounds.addAll(chainOutbounds)
        }
        chainOutbounds.forEach { existingTags.add(it.tag) }
        return true
    }

    /**
     * Build and insert a policy-group entry and its balancer metadata.
     */
    private fun handlePolicyGroupResolvedOutbound(
        resolvedOutbound: CoreConfigContext.ResolvedOutbound,
        prepend: Boolean,
        existingTags: MutableSet<String>,
        v2rayConfig: V2rayConfig,
        policyGroupBalancerTags: MutableMap<String, String>,
        balancerStrategies: MutableList<BalancerStrategy>,
    ): Boolean {
        val memberPairs = resolvedOutbound.resolvedProfiles.mapNotNull { profile ->
            convertProfile2Outbound(profile)?.let { ob -> ob to profile }
        }
        if (memberPairs.isEmpty()) {
            LogUtil.w(AppConfig.TAG, "POLICYGROUP resolved outbound '${resolvedOutbound.tag}' has no valid member outbounds, skipping")
            return false
        }

        val memberTagPrefix = "${AppConfig.TAG_PROXY}-${resolvedOutbound.tag}-"
        val membersToAdd = mutableListOf<V2rayConfig.OutboundBean>()
        memberPairs.forEachIndexed { index, (outbound, profile) ->
            val memberTag = "$memberTagPrefix${index + 1}-${profile.remarks.trim()}"
            if (memberTag in existingTags) {
                return@forEachIndexed
            }
            outbound.tag = memberTag
            membersToAdd.add(outbound)
            existingTags.add(memberTag)
        }

        if (membersToAdd.isEmpty()) {
            LogUtil.w(
                AppConfig.TAG,
                "POLICYGROUP resolved outbound '${resolvedOutbound.tag}' produced no unique member tags, skipping"
            )
            return false
        }

        if (prepend) {
            v2rayConfig.outbounds.addAll(0, membersToAdd)
        } else {
            v2rayConfig.outbounds.addAll(membersToAdd)
        }

        val balancerTag = if (resolvedOutbound.tag == AppConfig.TAG_PROXY) {
            AppConfig.TAG_BALANCER
        } else {
            "${AppConfig.TAG_BALANCER_PRE}-${resolvedOutbound.tag}"
        }
        val strategy = buildBalancerStrategy(
            policyGroupType = resolvedOutbound.profile.policyGroupType,
            selector = listOf(memberTagPrefix),
            balancerTag = balancerTag,
        )
        val existingBalancers = v2rayConfig.routing.balancers?.toMutableList() ?: mutableListOf()
        if (existingBalancers.none { it.tag == balancerTag }) {
            existingBalancers.add(strategy.balancer)
            v2rayConfig.routing.balancers = existingBalancers
        }
        balancerStrategies.add(strategy)
        policyGroupBalancerTags[resolvedOutbound.tag] = balancerTag
        return true
    }

    /**
     * Trim runtime sections that are not needed for latency testing.
     */
    private fun postProcessForSpeedtest(v2rayConfig: V2rayConfig) {
        v2rayConfig.log.loglevel = MmkvManager.decodeSettingsString(AppConfig.PREF_LOGLEVEL) ?: "warning"
        v2rayConfig.inbounds.clear()
        v2rayConfig.routing.rules.clear()
        // Balancer/observatory configs (POLICYGROUP "Auto"/Hybrid): the native
        // measureOutboundDelay strips app features and cannot resolve a balancer that
        // depends on observatory probe results, so it returns -1. For a latency test we
        // only need connectivity through one member, so drop the balancer + observatory
        // and let the request fall through to the first (primary) proxy outbound, which
        // buildUnifiedConfig always places at index 0.
        v2rayConfig.routing.balancers = null
        v2rayConfig.observatory = null
        v2rayConfig.burstObservatory = null
        v2rayConfig.dns = null
        v2rayConfig.fakedns = null
        v2rayConfig.stats = null
        v2rayConfig.policy = null
        v2rayConfig.outbounds.forEach { key -> key.mux = null }
    }

    /**
     * Serialize a runtime configuration into a standard result object.
     */
    private fun toConfigResult(configContext: CoreConfigContext, v2rayConfig: V2rayConfig): ConfigResult {
        return ConfigResult(
            status = true,
            guid = configContext.guid,
            content = JsonUtil.toJsonPretty(v2rayConfig) ?: ""
        )
    }

    /**
     * Load the base template from cache or assets and parse it.
     */
    private fun initV2rayConfig(configContext: CoreConfigContext): V2rayConfig {
        val context = configContext.context
        val assets: String
        if (needTun()) {
            assets = initConfigCacheWithTun ?: Utils.readTextFromAssets(context, "v2ray_config_with_tun.json")
            if (TextUtils.isEmpty(assets)) {
                error("Missing asset: v2ray_config_with_tun.json")
            }
            initConfigCacheWithTun = assets
        } else {
            assets = initConfigCache ?: Utils.readTextFromAssets(context, "v2ray_config.json")
            if (TextUtils.isEmpty(assets)) {
                error("Missing asset: v2ray_config.json")
            }
            initConfigCache = assets
        }
        return JsonUtil.fromJson(assets, V2rayConfig::class.java)
            ?: error("Failed to parse config template")
    }


    //endregion


    //region some sub function

    private fun needTun(): Boolean {
        return SettingsManager.isVpnMode() && !SettingsManager.isUsingHevTun()
    }

    /**
     * Configure inbound listeners and related runtime options.
     */
    private fun configureInbounds(v2rayConfig: V2rayConfig) {
        val vpn = SettingsManager.isVpnMode()
        val useHev = SettingsManager.isUsingHevTun()
        val forcedByHev = vpn && useHev

        val enableLocalProxy = forcedByHev || MmkvManager.decodeSettingsBool(AppConfig.PREF_ENABLE_LOCAL_PROXY, true)

        val socksPort = SettingsManager.getSocksPort()
        // Two-inbound topology for LAN/hotspot sharing:
        //  - inbound1 ("socks") is ALWAYS bound to 127.0.0.1 (loopback) and ALWAYS "noauth".
        //    The local tun bridge (hev-socks5-tunnel) and internal okhttp connect to this SAME
        //    inbound on 127.0.0.1 with NO credentials, so requiring auth here (or rebinding it to
        //    0.0.0.0) rejected the local tunnel and killed the phone's VPN. It must never change.
        //  - When PREF_PROXY_SHARING is ON a SEPARATE authenticated "socks-lan" inbound is added
        //    below, bound to 0.0.0.0 on a dedicated share port, so tethered devices can point
        //    their SOCKS5 client at the phone and ride its VPN without exposing an open relay.
        val proxySharing = MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING) == true
        val inbound1 = v2rayConfig.inbounds[0]
        if (inbound1.settings == null) {
            inbound1.settings = V2rayConfig.InboundBean.InSettingsBean()
        }

        // Loopback-only, unconditionally: never rebind the local inbound to 0.0.0.0.
        inbound1.listen = AppConfig.LOOPBACK
        inbound1.port = socksPort
        inbound1.settings?.udp = MmkvManager.decodeSettingsBool(AppConfig.PREF_SOCKS_ENABLE_UDP, true)
        // Always noauth so the loopback tun bridge is never locked out; LAN sharing uses the
        // separate authenticated "socks-lan" inbound instead of ever weakening this one.
        inbound1.settings?.auth = "noauth"
        inbound1.settings?.accounts = null
        val fakedns = MmkvManager.decodeSettingsBool(AppConfig.PREF_FAKE_DNS_ENABLED) == true
        val sniffAllTlsAndHttp =
            MmkvManager.decodeSettingsBool(AppConfig.PREF_SNIFFING_ENABLED, true) != false
        inbound1.sniffing?.enabled = fakedns || sniffAllTlsAndHttp
        inbound1.sniffing?.routeOnly =
            MmkvManager.decodeSettingsBool(AppConfig.PREF_ROUTE_ONLY_ENABLED, false)
        if (!sniffAllTlsAndHttp) {
            inbound1.sniffing?.destOverride?.clear()
        }
        if (fakedns) {
            inbound1.sniffing?.destOverride?.add("fakedns")
        }

        if (!Utils.isXray()) {
            val inbound2 = JsonUtil.fromJson(JsonUtil.toJson(inbound1), V2rayConfig.InboundBean::class.java)
                ?: error("Failed to clone inbound template")
            inbound2.tag = EConfigType.HTTP.name.lowercase()
            // HTTP is cleartext: keep it loopback-only, never expose it on the LAN.
            inbound2.listen = AppConfig.LOOPBACK
            inbound2.port = SettingsManager.getHttpPort()
            inbound2.protocol = EConfigType.HTTP.name.lowercase()
            inbound2.settings?.auth = null
            inbound2.settings?.udp = null
            v2rayConfig.inbounds.add(inbound2)
        }

        // Authenticated LAN/hotspot SOCKS5 inbound. Added only when sharing is enabled AND the
        // local proxy is active. Bound to 0.0.0.0 on a dedicated port, ALWAYS password-authed:
        // credentials are auto-generated + persisted first when empty, so this can never become
        // an open relay. Routing/outbounds key off the outbound tag (not the inbound), so this
        // inbound rides the same VPN as the loopback one.
        if (proxySharing && enableLocalProxy) {
            val (shareUser, sharePass) = SettingsManager.ensureSocksShareCredentials()
            val lanInbound = JsonUtil.fromJson(JsonUtil.toJson(inbound1), V2rayConfig.InboundBean::class.java)
            if (lanInbound != null) {
                lanInbound.tag = "socks-lan"
                lanInbound.protocol = EConfigType.SOCKS.name.lowercase()
                lanInbound.listen = "0.0.0.0"
                lanInbound.port = SettingsManager.getSocksSharePort()
                if (lanInbound.settings == null) {
                    lanInbound.settings = V2rayConfig.InboundBean.InSettingsBean()
                }
                lanInbound.settings?.auth = "password"
                lanInbound.settings?.udp = inbound1.settings?.udp
                lanInbound.settings?.accounts = listOf(
                    V2rayConfig.InboundBean.InSettingsBean.SocksAccountBean(shareUser, sharePass)
                )
                v2rayConfig.inbounds.add(lanInbound)
            } else {
                LogUtil.w(AppConfig.TAG, "Failed to clone socks-lan inbound; LAN sharing inbound not added")
            }
        }

        if (!enableLocalProxy) {
            v2rayConfig.inbounds.removeIf { it.protocol == "socks" || it.protocol == "http" }
        }

        if (needTun()) {
            val inboundTun = v2rayConfig.inbounds.firstOrNull { e -> e.tag == "tun" }
            inboundTun?.settings?.mtu = SettingsManager.getVpnMtu()
            inboundTun?.sniffing = inbound1.sniffing
        }
    }

    /**
     * Enable fake DNS when local DNS and fake DNS are both enabled.
     */
    private fun configureFakeDns(v2rayConfig: V2rayConfig) {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED) == true
            && MmkvManager.decodeSettingsBool(AppConfig.PREF_FAKE_DNS_ENABLED) == true
        ) {
            v2rayConfig.fakedns = listOf(V2rayConfig.FakednsBean())
        }
    }

    /**
     * Collect domain rules that target one outbound tag.
     */
    private fun collectUserRuleDomainsByTag(tag: String): ArrayList<String> {
        val domain = ArrayList<String>()

        val rulesetItems = MmkvManager.decodeRoutingRulesets()
        rulesetItems?.forEach { key ->
            if (key.enabled && key.outboundTag == tag && !key.domain.isNullOrEmpty()) {
                key.domain?.forEach {
                    domain.add(it)
                }
            }
        }

        return domain
    }

    /**
     * Collect domain rules that target non-builtin outbound tags.
     */
    private fun collectCustomOutboundDomains(): ArrayList<String> {
        val domain = ArrayList<String>()

        val rulesetItems = MmkvManager.decodeRoutingRulesets()
        rulesetItems?.forEach { key ->
            if (key.enabled && !AppConfig.BUILTIN_OUTBOUND_TAGS.contains(key.outboundTag)
                && !key.domain.isNullOrEmpty()
            ) {
                key.domain?.forEach {
                    domain.add(it)
                }
            }
        }

        return domain
    }

    /**
     * Configure local DNS inbounds, outbounds, and routing rules.
     */
    private fun configureLocalDns(v2rayConfig: V2rayConfig) {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED) != true) {
            return
        }

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_FAKE_DNS_ENABLED) == true) {
            val geositeCn = arrayListOf(AppConfig.GEOSITE_CN)
            val proxyDomain = collectUserRuleDomainsByTag(AppConfig.TAG_PROXY)
            val directDomain = collectUserRuleDomainsByTag(AppConfig.TAG_DIRECT)
            val finalDomain = geositeCn.plus(proxyDomain).plus(directDomain).distinct()
            // fakedns with all domains to make it always top priority
            v2rayConfig.dns?.servers?.add(
                0,
                V2rayConfig.DnsBean.ServersBean(
                    address = "fakedns",
                    domains = finalDomain
                )
            )
        }

        if (SettingsManager.isVpnMode()) {
            if (SettingsManager.isUsingHevTun()) {
                //hev-socks5-tunnel dns routing
                v2rayConfig.routing.rules.add(
                    0, V2rayConfig.RoutingBean.RulesBean(
                        inboundTag = arrayListOf("socks"),
                        outboundTag = "dns-out",
                        port = "53",
                    )
                )
            } else {
                v2rayConfig.routing.rules.add(
                    0, V2rayConfig.RoutingBean.RulesBean(
                        inboundTag = arrayListOf("tun"),
                        outboundTag = "dns-out",
                        port = "53",
                    )
                )
            }
        }

        // DNS outbound
        if (v2rayConfig.outbounds.none { e -> e.protocol == "dns" && e.tag == "dns-out" }) {
            v2rayConfig.outbounds.add(
                V2rayConfig.OutboundBean(
                    protocol = "dns",
                    tag = "dns-out",
                    settings = null,
                    streamSettings = null,
                    mux = null
                )
            )
        }
    }

    /**
     * Remove speed-test runtime sections when the feature is disabled.
     */
    private fun applySpeedDisabled(v2rayConfig: V2rayConfig) {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_SPEED_ENABLED) != true) {
            v2rayConfig.stats = null
            v2rayConfig.policy = null
        }
    }

    /**
     * Configure DNS servers, hosts, and DNS routing rules.
     */
    private fun configureDns(
        v2rayConfig: V2rayConfig,
        policyGroupBalancerTags: Map<String, String>,
    ) {
        val hosts = mutableMapOf<String, Any>()
        val servers = ArrayList<Any>()

        //remote Dns
        val remoteDns = SettingsManager.getRemoteDnsServers()
        val proxyDomain = (collectUserRuleDomainsByTag(AppConfig.TAG_PROXY) + collectCustomOutboundDomains()).distinct()
        remoteDns.forEach {
            servers.add(it)
        }
        if (proxyDomain.isNotEmpty()) {
            servers.add(
                V2rayConfig.DnsBean.ServersBean(
                    address = remoteDns.first(),
                    domains = proxyDomain,
                )
            )
        }

        // domestic DNS
        val domesticDns = SettingsManager.getDomesticDnsServers()
        val directDomain = collectUserRuleDomainsByTag(AppConfig.TAG_DIRECT)
        val isCnRoutingMode = directDomain.contains(AppConfig.GEOSITE_CN)
        val cnRegionFilter = { domain: String ->
            domain.startsWith("geosite:") && (domain.endsWith("-cn") || domain.endsWith("@cn"))
                    || domain == AppConfig.GEOSITE_CN
        }
        val finalDirectDomain = if (isCnRoutingMode) directDomain.filterNot {
            cnRegionFilter(it)
        } else directDomain
        val domesticDnsTags = mutableListOf<String>()
        domesticDns.forEachIndexed { index, element ->
            val tag = AppConfig.TAG_DOMESTIC_DNS + index
            servers.add(
                V2rayConfig.DnsBean.ServersBean(
                    address = element,
                    domains = finalDirectDomain,
                    skipFallback = true,
                    tag = tag
                )
            )
            domesticDnsTags.add(tag)
        }
        if (isCnRoutingMode) {
            val geoipCn = arrayListOf(AppConfig.GEOIP_CN)
            val cnRegionDomain = directDomain.filter { cnRegionFilter(it) }
            domesticDns.forEachIndexed { index, element ->
                val geositeCnDnsTag = AppConfig.TAG_DOMESTIC_DNS + index + "_cn_expect"
                servers.add(
                    V2rayConfig.DnsBean.ServersBean(
                        address = element,
                        domains = cnRegionDomain,
                        expectIPs = geoipCn,
                        skipFallback = true,
                        tag = geositeCnDnsTag
                    )
                )
                domesticDnsTags.add(geositeCnDnsTag)
            }
        }

        //block dns
        val blkDomain = collectUserRuleDomainsByTag(AppConfig.TAG_BLOCKED)
        if (blkDomain.isNotEmpty()) {
            hosts.putAll(blkDomain.map { it to AppConfig.LOOPBACK })
        }

        // hardcode googleapi rule to fix play store problems
        hosts[AppConfig.GOOGLEAPIS_CN_DOMAIN] = AppConfig.GOOGLEAPIS_COM_DOMAIN

        // hardcode popular Android Private DNS rule to fix localhost DNS problem
        hosts[AppConfig.DNS_ALIDNS_DOMAIN] = AppConfig.DNS_ALIDNS_ADDRESSES
        hosts[AppConfig.DNS_CLOUDFLARE_ONE_DOMAIN] = AppConfig.DNS_CLOUDFLARE_ONE_ADDRESSES
        hosts[AppConfig.DNS_CLOUDFLARE_DNS_COM_DOMAIN] = AppConfig.DNS_CLOUDFLARE_DNS_COM_ADDRESSES
        hosts[AppConfig.DNS_CLOUDFLARE_DNS_DOMAIN] = AppConfig.DNS_CLOUDFLARE_DNS_ADDRESSES
        hosts[AppConfig.DNS_DNSPOD_DOMAIN] = AppConfig.DNS_DNSPOD_ADDRESSES
        hosts[AppConfig.DNS_GOOGLE_DOMAIN] = AppConfig.DNS_GOOGLE_ADDRESSES
        hosts[AppConfig.DNS_QUAD9_DOMAIN] = AppConfig.DNS_QUAD9_ADDRESSES
        hosts[AppConfig.DNS_YANDEX_DOMAIN] = AppConfig.DNS_YANDEX_ADDRESSES

        //User DNS hosts
        val userHosts = MmkvManager.decodeSettingsString(AppConfig.PREF_DNS_HOSTS)
        if (userHosts.isNotNullEmpty()) {
            val userHostsMap = userHosts?.split(",")
                ?.filter { it.isNotEmpty() }
                ?.filter { it.contains(":") }
                ?.associate { it.split(":").let { (k, v) -> k to v } }
            if (userHostsMap != null) {
                hosts.putAll(userHostsMap)
            }
        }

        // DNS dns
        v2rayConfig.dns = V2rayConfig.DnsBean(
            servers = servers,
            hosts = hosts,
            tag = AppConfig.TAG_DNS,
            enableParallelQuery = if ((domesticDns.size + remoteDns.size) > 2) true else null
        )

        // DNS routing
        v2rayConfig.routing.rules.add(
            V2rayConfig.RoutingBean.RulesBean(
                outboundTag = AppConfig.TAG_DIRECT,
                inboundTag = domesticDnsTags,
                domain = null
            )
        )
        val dnsProxyBalancerTag = policyGroupBalancerTags[AppConfig.TAG_PROXY]
        if (dnsProxyBalancerTag != null) {
            v2rayConfig.routing.rules.add(
                V2rayConfig.RoutingBean.RulesBean(
                    balancerTag = dnsProxyBalancerTag,
                    inboundTag = arrayListOf(AppConfig.TAG_DNS),
                    domain = null
                )
            )
        } else {
            v2rayConfig.routing.rules.add(
                V2rayConfig.RoutingBean.RulesBean(
                    outboundTag = AppConfig.TAG_PROXY,
                    inboundTag = arrayListOf(AppConfig.TAG_DNS),
                    domain = null
                )
            )
        }
    }


    //endregion


    //region outbound related functions


    /**
     * How long the whole pre-resolution may hold up a connect, and how many names may be in flight.
     *
     * The deadline is a budget for ALL the names together, not for one of them: past it whatever
     * came back is used and the rest are left to the core, which resolves at dial time anyway — that
     * is precisely what setting «2» of [AppConfig.PREF_OUTBOUND_DOMAIN_RESOLVE_METHOD] does full
     * time. So the worst case of a dead resolver costs this, once, instead of the resolver's own
     * timeout per сервер.
     */
    private const val OUTBOUND_RESOLVE_BUDGET_MS = 2000L
    private const val OUTBOUND_RESOLVE_PARALLELISM = 8

    /**
     * Resolve outbound domains to IPs and write resolved hosts to DNS map.
     *
     * **THIS RUNS ON THE CONNECT PATH, AND IT USED TO RUN THERE ONE NAME AT A TIME, ON THE MAIN
     * THREAD OF THE DAEMON PROCESS.** `CoreVpnService.onStartCommand` → `startCoreLoop` →
     * `getV2rayConfig` is one synchronous chain on `:RunSoLibV2RayDaemon`'s main thread, and this
     * function sat at the end of it calling a blocking `InetAddress.getAllByName` per proxy outbound
     * in a `for` loop. Two separate consequences, both real:
     *
     *  - **It is O(серверы).** A policy group of twenty locations is twenty DNS round-trips in
     *    series before the core is handed anything. Measured cold against a datacentre resolver,
     *    twenty names cost 222 ms with the slowest single name at 36 ms; on mobile data, at
     *    50-300 ms a name, the same loop is one to six seconds of frozen service. On a network that
     *    answers nothing — captive Wi-Fi, the case where people press connect hardest — Android's
     *    resolver spends seconds per name before giving up, and the sum walks into the foreground
     *    service's start deadline.
     *  - **In «Только прокси» it did not work at all.** `CoreVpnService.onCreate` installs a
     *    `permitAll` StrictMode policy; `CoreProxyOnlyService` does not, so on that path every
     *    lookup threw `NetworkOnMainThreadException`, was swallowed by `resolveHostToIP`, and left
     *    one «Failed to resolve host to IP» in «Журнал» per сервер and nothing in `dns.hosts`.
     *
     * Both are the same fix: do the lookups on worker threads (no main-thread network policy to
     * violate), all at once rather than one after another, and give the whole batch ONE budget. The
     * cost of the step becomes the slowest single name, capped — not the sum of all of them.
     *
     * Called from [getV2rayConfig] alone. It is NOT part of [buildUnifiedConfig] any more: the
     * latency test builds through the same function and then drops `dns` entirely, so every
     * measured сервер used to pay for a lookup nobody read.
     */
    private fun resolveOutboundDomainsToHosts(v2rayConfig: V2rayConfig) {
        if (MmkvManager.decodeSettingsString(AppConfig.PREF_OUTBOUND_DOMAIN_RESOLVE_METHOD, "1") != "1") {
            return
        }

        val proxyOutboundList = v2rayConfig.getAllProxyOutbound()
        val dns = v2rayConfig.dns ?: return
        val newHosts = dns.hosts?.toMutableMap() ?: mutableMapOf()
        val preferIpv6 = MmkvManager.decodeSettingsBool(AppConfig.PREF_PREFER_IPV6) == true

        // A group of twenty серверы behind one hostname is ONE lookup: the names are collected and
        // de-duplicated before anything is resolved, which the per-outbound loop could not do.
        val wanted = LinkedHashSet<String>()
        for (item in proxyOutboundList) {
            val domain = item.getServerAddress()
            if (domain.isNullOrEmpty() || newHosts.containsKey(domain)) continue
            wanted.add(domain)
        }

        if (wanted.isNotEmpty()) {
            resolveInParallel(wanted.toList(), preferIpv6).forEach { (domain, ips) ->
                newHosts[domain] = if (ips.size == 1) ips[0] else ips
            }
        }

        // `UseIP` is written for exactly the outbounds whose address ENDED UP in the map, which is
        // the rule the serial loop applied too: an address that is already an IP, or a name nothing
        // could resolve, keeps whatever the profile asked for and is left to the core.
        for (item in proxyOutboundList) {
            val domain = item.getServerAddress()
            if (domain.isNullOrEmpty() || !newHosts.containsKey(domain)) continue
            item.ensureSockopt().domainStrategy = "UseIP"
            item.ensureSockopt().happyEyeballs = V2rayConfig.OutboundBean.StreamSettingsBean.HappyEyeballsBean(
                prioritizeIPv6 = preferIpv6,
                interleave = 2
            )
        }

        dns.hosts = newHosts
    }

    /**
     * [domains] → their addresses, for as many as answer within [OUTBOUND_RESOLVE_BUDGET_MS].
     *
     * A name that does not answer in time is simply absent from the result; it is not an error and
     * nothing retries it, because the core will resolve it itself when it dials. The pool is shut
     * down before returning, and the tasks left running are interrupted — a lookup nobody is waiting
     * for must not keep a thread alive into the session.
     */
    private fun resolveInParallel(domains: List<String>, preferIpv6: Boolean): Map<String, List<String>> {
        val pool = Executors.newFixedThreadPool(minOf(domains.size, OUTBOUND_RESOLVE_PARALLELISM))
        return try {
            val tasks = domains.map { domain ->
                Callable { domain to HttpUtil.resolveHostToIP(domain, preferIpv6) }
            }
            val started = System.currentTimeMillis()
            val futures = pool.invokeAll(tasks, OUTBOUND_RESOLVE_BUDGET_MS, TimeUnit.MILLISECONDS)
            val out = LinkedHashMap<String, List<String>>()
            futures.forEach { future ->
                if (future.isCancelled) return@forEach
                val (domain, ips) = runCatching { future.get() }.getOrNull() ?: return@forEach
                if (!ips.isNullOrEmpty()) out[domain] = ips
            }
            if (out.size < domains.size) {
                LogUtil.i(
                    AppConfig.TAG,
                    "Outbound pre-resolve: ${out.size} of ${domains.size} names in ${System.currentTimeMillis() - started} ms, the rest are left to the core"
                )
            }
            out
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            emptyMap()
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Convert one profile object into one outbound object.
     */
    private fun convertProfile2Outbound(profileItem: ProfileItem): V2rayConfig.OutboundBean? {
        return CoreOutboundBuilder.convert(profileItem)
    }

    //endregion


    //region routing related functions


    /**
     * Merge probe settings from all balancer strategies into the runtime config.
     */
    private fun applyObservability(v2rayConfig: V2rayConfig, strategies: List<BalancerStrategy>) {
        val allObsSelectors = strategies
            .mapNotNull { it.observatory?.subjectSelector }
            .flatten()
            .distinct()
        val obsTemplate = strategies.firstNotNullOfOrNull { it.observatory }
        if (obsTemplate != null && allObsSelectors.isNotEmpty()) {
            v2rayConfig.observatory = V2rayConfig.ObservatoryObject(
                subjectSelector = allObsSelectors,
                probeUrl = obsTemplate.probeUrl,
                probeInterval = obsTemplate.probeInterval,
                enableConcurrency = obsTemplate.enableConcurrency
            )
        }

        val allBurstSelectors = strategies
            .mapNotNull { it.burstObservatory?.subjectSelector }
            .flatten()
            .distinct()
        val burstTemplate = strategies.firstNotNullOfOrNull { it.burstObservatory }
        if (burstTemplate != null && allBurstSelectors.isNotEmpty()) {
            v2rayConfig.burstObservatory = V2rayConfig.BurstObservatoryObject(
                subjectSelector = allBurstSelectors,
                pingConfig = burstTemplate.pingConfig
            )
        }
    }

    /**
     * Configure routing domain strategy and append enabled user rules.
     */
    private fun configureRouting(
        configContext: CoreConfigContext,
        v2rayConfig: V2rayConfig,
        policyGroupBalancerTags: Map<String, String>
    ) {

        v2rayConfig.routing.domainStrategy =
            MmkvManager.decodeSettingsString(AppConfig.PREF_ROUTING_DOMAIN_STRATEGY)
                ?: "AsIs"

        val rulesetItems = MmkvManager.decodeRoutingRulesets()
        rulesetItems?.forEach { key ->
            appendRoutingUserRule(configContext, key, v2rayConfig, policyGroupBalancerTags)
        }
    }

    /**
     * Convert one rule item and append it to routing rules.
     */
    private fun appendRoutingUserRule(
        configContext: CoreConfigContext,
        item: RulesetItem?,
        v2rayConfig: V2rayConfig,
        policyGroupBalancerTags: Map<String, String>
    ) {
        val context = configContext.context
        if (item == null || !item.enabled) {
            return
        }

        val rule = JsonUtil.fromJson(JsonUtil.toJson(item), V2rayConfig.RoutingBean.RulesBean::class.java) ?: return

        // Replace specific geoip rules with ext versions
        rule.ip?.let { ipList ->
            val updatedIpList = ArrayList<String>()
            ipList.forEach { ip ->
                when (ip) {
                    AppConfig.GEOIP_CN -> updatedIpList.add("ext:${AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT}:cn")
                    AppConfig.GEOIP_PRIVATE -> updatedIpList.add("ext:${AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT}:private")
                    else -> updatedIpList.add(ip)
                }
            }
            rule.ip = updatedIpList
        }

        if (SettingsManager.canUseProcessRouting()) {
            // Convert process package names to UIDs
            rule.process?.let { processList ->
                if (processList.isNotEmpty()) {
                    val uids = PackageUidResolver.packageNamesToUids(context, processList)
                    rule.process = uids.ifEmpty { null }
                }
            }
        } else {
            rule.process = null
        }

        val outboundTag = rule.outboundTag

        // Route rules targeting a custom policy-group tag should hit its balancer.
        policyGroupBalancerTags[outboundTag]?.let { balancerTag ->
            rule.outboundTag = null
            rule.balancerTag = balancerTag
        }

        // If the outbound tag is a custom one that failed to inject, fall back to proxy
        if (!outboundTag.isNullOrBlank()
            && outboundTag !in policyGroupBalancerTags
            && outboundTag !in AppConfig.BUILTIN_OUTBOUND_TAGS
            && v2rayConfig.outbounds.none { it.tag == outboundTag }
        ) {
            LogUtil.w(AppConfig.TAG, "Outbound tag '$outboundTag' not found, falling back to '${AppConfig.TAG_PROXY}'")
            rule.outboundTag = AppConfig.TAG_PROXY
        }

        v2rayConfig.routing.rules.add(rule)
    }


    /**
     * Build balancer and probe settings from one policy-group strategy value.
     */
    private fun buildBalancerStrategy(
        policyGroupType: String?,
        selector: List<String>,
        balancerTag: String = AppConfig.TAG_BALANCER,
    ): BalancerStrategy {
        val probeUrl = MmkvManager.decodeSettingsString(AppConfig.PREF_DELAY_TEST_URL) ?: AppConfig.DELAY_TEST_URL
        val strategyType = BalancerStrategyType.from(policyGroupType)
        val balancer = V2rayConfig.RoutingBean.BalancerBean(
            tag = balancerTag,
            selector = selector,
            strategy = V2rayConfig.RoutingBean.StrategyObject(type = strategyType.policyGroupType)
        )
        val observatory = if (strategyType.requiresObservatory) {
            V2rayConfig.ObservatoryObject(
                subjectSelector = selector,
                probeUrl = probeUrl,
                probeInterval = "3m",
                enableConcurrency = true
            )
        } else null
        val burstObservatory = if (strategyType.requiresBurstObservatory) {
            V2rayConfig.BurstObservatoryObject(
                subjectSelector = selector,
                pingConfig = V2rayConfig.BurstObservatoryObject.PingConfigObject(
                    destination = probeUrl,
                    interval = "5m",
                    sampling = 2,
                    timeout = "30s"
                )
            )
        } else null
        return BalancerStrategy(balancer, observatory, burstObservatory)
    }

    /**
     * Carry balancer data plus optional probe settings for later merge.
     */
    private data class BalancerStrategy(
        val balancer: V2rayConfig.RoutingBean.BalancerBean,
        val observatory: V2rayConfig.ObservatoryObject? = null,
        val burstObservatory: V2rayConfig.BurstObservatoryObject? = null,
    )

    //endregion
}