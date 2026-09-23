namespace ServiceLib.Handler;

/// <summary>
/// Core configuration file processing class
/// </summary>
public static class CoreConfigHandler
{
    private static readonly string _tag = "CoreConfigHandler";

    public static async Task<RetResult> GenerateClientConfig(CoreConfigContext context, string? fileName)
    {
        var config = AppManager.Instance.Config;
        var result = new RetResult();
        var node = context.Node;

        if (node.ConfigType == EConfigType.Custom)
        {
            result = node.CoreType switch
            {
                ECoreType.mihomo => await new CoreConfigClashService(config).GenerateClientCustomConfig(node, fileName),
                _ => await GenerateClientCustomConfig(node, fileName)
            };
        }
        else if (context.RunCoreType == ECoreType.sing_box)
        {
            result = new CoreConfigSingboxService(context).GenerateClientConfigContent();
        }
        else
        {
            result = new CoreConfigV2rayService(context).GenerateClientConfigContent();
        }
        if (result.Success != true)
        {
            return result;
        }
        if (fileName.IsNotEmpty() && result.Data != null)
        {
            await File.WriteAllTextAsync(fileName, result.Data.ToString());
        }

        return result;
    }

    // Valid Xray top-level config keys. Anything else (e.g. a Remnawave root-level "remnawave"
    // metadata object) is stripped before the config reaches the core — mirror Android's
    // sanitizeXrayRootKeys so an unexpected top-level key can never crash the native core.
    private static readonly HashSet<string> _xrayRootKeys = new(StringComparer.Ordinal)
    {
        "log", "api", "dns", "stats", "inbounds", "outbounds", "routing", "policy",
        "transport", "reverse", "fakedns", "metrics", "observatory", "burstObservatory",
    };

    /// <summary>
    /// Build the run config for a CUSTOM (raw xray-json / departament XRAY_JSON) node.
    ///
    /// Faithful port of Android's <c>buildV2rayCustomConfig</c>: the template's own
    /// <c>outbounds</c> / <c>routing</c> / <c>dns</c> are kept AS-AUTHORED (so the provider's
    /// ad-block / geo / direct rules are applied). We only (1) strip non-Xray root keys, (2) validate
    /// outbounds exist, and (3) graft the app's standard local inbound onto it so the OS system proxy
    /// port actually routes through the app (and traffic stats keep working). A malformed / non-Xray
    /// payload is never handed to the core verbatim — we fail cleanly instead.
    /// </summary>
    private static async Task<RetResult> GenerateClientCustomConfig(ProfileItem node, string? fileName)
    {
        var ret = new RetResult();
        try
        {
            if (node == null || fileName is null)
            {
                ret.Msg = ResUI.CheckServerSettings;
                return ret;
            }

            if (File.Exists(fileName))
            {
                File.SetAttributes(fileName, FileAttributes.Normal); //If the file has a read-only attribute, direct deletion will fail
                File.Delete(fileName);
            }

            var addressFileName = node.Address;
            if (!File.Exists(addressFileName))
            {
                addressFileName = Utils.GetConfigPath(addressFileName);
            }
            //  Файла нет — возможно, узел пришёл из СТАРОГО контекста. Перезапуск ядра после падения или
            //  смены сети собирает конфиг по контексту последнего подключения, а обновление подписки
            //  (импорт аккаунта, расписание) тем временем записало конфиг провайдера в новый файл и
            //  удалило старый. Без перечитывания каждый такой перезапуск падал на «не удалось собрать
            //  конфиг», и человек оставался без VPN. Берём свежую запись того же сервера по IndexId.
            if (!File.Exists(addressFileName)
                && node.IndexId.IsNotEmpty()
                && await AppManager.Instance.GetProfileItem(node.IndexId) is { ConfigType: EConfigType.Custom } current
                && current.Address.IsNotEmpty())
            {
                addressFileName = File.Exists(current.Address) ? current.Address : Utils.GetConfigPath(current.Address);
            }
            if (!File.Exists(addressFileName))
            {
                ret.Msg = ResUI.FailedGenDefaultConfiguration;
                return ret;
            }

            // Only Xray (raw xray-json / Remnawave XRAY_JSON) custom configs get the faithful merge.
            // A sing-box (or other) custom config is copied verbatim exactly as before — its root keys
            // and inbound model differ and must not be rewritten with Xray semantics.
            if (node.CoreType != ECoreType.Xray)
            {
                File.Copy(addressFileName, fileName);
                File.SetAttributes(fileName, FileAttributes.Normal);
                if (!File.Exists(fileName))
                {
                    ret.Msg = ResUI.FailedGenDefaultConfiguration;
                    return ret;
                }
                ret.Msg = string.Format(ResUI.SuccessfulConfiguration, "");
                ret.Success = true;
                return ret;
            }

            // Parse up-front. A non-object / non-Xray payload must never be copied verbatim to the
            // core: that would break routing/port (or crash the core).
            var raw = await File.ReadAllTextAsync(addressFileName);
            if (JsonUtils.ParseJson(raw) is not JsonObject root)
            {
                ret.Msg = ResUI.FailedGenDefaultConfiguration;
                return ret;
            }

            // Keep only valid Xray root keys (drop e.g. a root-level "remnawave" metadata object).
            foreach (var key in root.Select(p => p.Key).Where(k => !_xrayRootKeys.Contains(k)).ToList())
            {
                root.Remove(key);
            }

            // A config with no outbounds cannot connect; fail cleanly instead of crashing the core.
            if (root["outbounds"] is not JsonArray outbounds || outbounds.Count == 0)
            {
                ret.Msg = ResUI.FailedGenDefaultConfiguration;
                return ret;
            }

            // Inject the app's standard local inbound (+ stats). The template's outbounds/routing/dns
            // are left untouched.
            MergeAppInbounds(root, node);

            var content = JsonUtils.Serialize(root);
            await File.WriteAllTextAsync(fileName, content);

            //check again
            if (!File.Exists(fileName))
            {
                ret.Msg = ResUI.FailedGenDefaultConfiguration;
                return ret;
            }
            File.SetAttributes(fileName, FileAttributes.Normal); //ensure writable even if the stored template was read-only

            ret.Msg = string.Format(ResUI.SuccessfulConfiguration, "");
            ret.Success = true;
            return ret;
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
            ret.Msg = ResUI.FailedGenDefaultConfiguration;
            return ret;
        }
    }

    /// <summary>
    /// Replace the template's own socks/http/mixed proxy inbounds with the app's standard local
    /// inbound (mixed on the system-proxy / pre-service socks port, plus optional second/LAN ports)
    /// and carry the app's stats/metrics/policy. The Xray core for a custom node is deliberately
    /// kept socks-only — it never gets a `tun` inbound; in TUN mode the sing-box pre-service owns
    /// the tun device and forwards into this socks inbound. Everything else in the template
    /// (its own outbounds, routing, dns) is preserved.
    /// </summary>
    private static void MergeAppInbounds(JsonObject root, ProfileItem node)
    {
        var config = AppManager.Instance.Config;

        // Generate the app inbounds + stats exactly as a normal connection would.
        // IMPORTANT: the Xray (main) core for a CUSTOM node must NEVER carry a `tun` inbound.
        // Xray cannot create the OS wintun/utun device in this app's architecture — in FULL TUN
        // mode a separate sing-box PRE-SERVICE owns the tun adapter and forwards all captured
        // traffic into this Xray config's SOCKS inbound (see CoreConfigContextBuilder's pre-socks
        // synthesis). If we injected a `tun` inbound here the Xray core would either fail to build
        // the device or fight the sing-box tun, leaving the app "connected" with zero traffic.
        // So force IsTunEnabled=false: emit ONLY the local mixed/socks inbound the pre-service
        // (and the OS system proxy) point at.
        var inboundContext = new CoreConfigContext
        {
            Node = node,
            RunCoreType = ECoreType.Xray,
            AppConfig = config,
            IsTunEnabled = false,
            IsWindows = Utils.IsWindows(),
            IsMacOS = Utils.IsMacOS(),
        };
        var appConfig = new CoreConfigV2rayService(inboundContext).GenerateInboundsForCustom();
        var appInbounds = appConfig.inbounds ?? [];

        // Keep every template inbound EXCEPT its own socks/http/mixed proxy inbounds (which may be on
        // the wrong/absent port). A template-supplied tun inbound is kept and suppresses the app's.
        var kept = new JsonArray();
        var templateHasTun = false;
        if (root["inbounds"] is JsonArray templateInbounds)
        {
            foreach (var elem in templateInbounds)
            {
                if (elem is null)
                {
                    continue;
                }
                var protocol = GetJsonProtocol(elem);
                if (protocol is "socks" or "http" or "mixed")
                {
                    continue;
                }
                if (protocol == "tun")
                {
                    templateHasTun = true;
                }
                kept.Add(elem.DeepClone());
            }
        }

        foreach (var inbound in appInbounds)
        {
            if (inbound.protocol == "tun" && templateHasTun)
            {
                continue;
            }
            var inboundNode = JsonUtils.ParseJson(JsonUtils.Serialize(inbound));
            if (inboundNode != null)
            {
                kept.Add(inboundNode);
            }
        }

        root["inbounds"] = kept;

        // Carry the app's stats/metrics/policy so the traffic widget (which polls metrics.listen on
        // the app's StatePort) works, matching a normal connection.
        SetRootObject(root, "stats", appConfig.stats);
        SetRootObject(root, "metrics", appConfig.metrics);
        SetRootObject(root, "policy", appConfig.policy);

        // Graft the Xray HandlerService `api` surface so a server switch can hot-swap the proxy
        // outbound at runtime (Tier 2 make-before-break) without touching this config's inbounds,
        // routing, TUN adapter or the sing-box pre-service. The template's own outbounds/routing/dns
        // stay AS-AUTHORED — we only add an api inbound, the api block and the api dispatch rule.
        GraftXrayApi(root);
    }

    /// <summary>
    /// Add the Xray HandlerService <c>api</c> surface to a CUSTOM (raw xray-json) config: a
    /// dokodemo-door inbound tagged "api" on the deterministic <see cref="AppManager.ApiPort"/>, an
    /// <c>api</c> block advertising HandlerService, and a routing rule dispatching api-tagged traffic
    /// to it (prepended so it wins over any catch-all proxy rule). This is what lets
    /// <c>CoreManager</c> run <c>xray api rmo/ado</c> to re-point the proxy outbound live. If the
    /// template already ships its own api block we leave it untouched (respect the provider); a
    /// template api that lacks HandlerService simply means the hot-swap tier is skipped and the switch
    /// degrades to the Xray-only restart tier. Never throws into config generation.
    /// </summary>
    private static void GraftXrayApi(JsonObject root)
    {
        try
        {
            // Respect a template that already declares its own api surface.
            if (root["api"] is JsonObject)
            {
                return;
            }

            var apiPort = AppManager.Instance.ApiPort;

            if (root["inbounds"] is not JsonArray inbounds)
            {
                inbounds = new JsonArray();
                root["inbounds"] = inbounds;
            }
            inbounds.Add(new JsonObject
            {
                ["tag"] = Global.ApiTag,
                ["listen"] = Global.Loopback,
                ["port"] = apiPort,
                ["protocol"] = Global.InboundAPIProtocol,
                ["settings"] = new JsonObject { ["address"] = Global.Loopback },
            });

            root["api"] = new JsonObject
            {
                ["tag"] = Global.ApiTag,
                ["services"] = new JsonArray { "HandlerService" },
            };

            if (root["routing"] is not JsonObject routing)
            {
                routing = new JsonObject();
                root["routing"] = routing;
            }
            if (routing["rules"] is not JsonArray rules)
            {
                rules = new JsonArray();
                routing["rules"] = rules;
            }
            rules.Insert(0, new JsonObject
            {
                ["type"] = "field",
                ["inboundTag"] = new JsonArray { Global.ApiTag },
                ["outboundTag"] = Global.ApiTag,
            });
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
        }
    }

    private static void SetRootObject(JsonObject root, string key, object? value)
    {
        if (value == null)
        {
            return;
        }
        var node = JsonUtils.ParseJson(JsonUtils.Serialize(value));
        if (node != null)
        {
            root[key] = node;
        }
    }

    private static string? GetJsonProtocol(JsonNode? elem)
    {
        if (elem is JsonObject obj
            && obj["protocol"] is JsonValue v
            && v.TryGetValue<string>(out var s))
        {
            return s;
        }
        return null;
    }

    public static async Task<RetResult> GenerateClientSpeedtestConfig(Config config, string fileName, List<ServerTestItem> selecteds, ECoreType coreType)
    {
        var result = new RetResult();
        var dummyNode = new ProfileItem
        {
            CoreType = coreType
        };
        var builderResult = await CoreConfigContextBuilder.Build(config, dummyNode);
        var context = builderResult.Context;
        foreach (var testItem in selecteds)
        {
            var node = testItem.Profile;
            var (actNode, _) = await CoreConfigContextBuilder.ResolveNodeAsync(context, node, true);
            if (node.IndexId == actNode.IndexId)
            {
                continue;
            }
            context.ServerTestItemMap[node.IndexId] = actNode.IndexId;
        }
        if (coreType == ECoreType.sing_box)
        {
            result = new CoreConfigSingboxService(context).GenerateClientSpeedtestConfig(selecteds);
        }
        else if (coreType == ECoreType.Xray)
        {
            result = new CoreConfigV2rayService(context).GenerateClientSpeedtestConfig(selecteds);
            //The Xray service skips CUSTOM (raw xray-json) nodes; graft each one's proxy outbound +
            //a dedicated inbound + routing rule into the batch config so its latency is measurable.
            InjectCustomSpeedtestNodes(result, selecteds);
        }
        if (result.Success != true)
        {
            return result;
        }
        await File.WriteAllTextAsync(fileName, result.Data.ToString());
        return result;
    }

    /// <summary>
    /// Add CUSTOM (raw xray-json) nodes to a batch Xray speedtest config. For each custom node we
    /// parse its stored template, strip non-Xray root keys, take the single real proxy outbound
    /// (mirroring Android's <c>buildV2rayCustomConfig4Speedtest</c> — routing/balancer/observatory/
    /// dns are dropped so the delay routes straight through the proxy), retag it uniquely and wire it
    /// to its own local inbound via a routing rule. The node's <see cref="ServerTestItem.Port"/> is
    /// set to that local inbound port so the real-ping request goes through the proxy.
    /// </summary>
    private static void InjectCustomSpeedtestNodes(RetResult result, List<ServerTestItem> selecteds)
    {
        var customs = selecteds.Where(s => s.ConfigType == EConfigType.Custom && s.Profile != null).ToList();
        if (customs.Count == 0)
        {
            return;
        }

        if (JsonUtils.ParseJson(result.Data?.ToString()) is not JsonObject root)
        {
            return;
        }
        if (root["inbounds"] is not JsonArray inbounds)
        {
            inbounds = new JsonArray();
            root["inbounds"] = inbounds;
        }
        if (root["outbounds"] is not JsonArray outbounds)
        {
            outbounds = new JsonArray();
            root["outbounds"] = outbounds;
        }
        if (root["routing"] is not JsonObject routing)
        {
            routing = new JsonObject();
            root["routing"] = routing;
        }
        if (routing["rules"] is not JsonArray rules)
        {
            rules = new JsonArray();
            routing["rules"] = rules;
        }

        // Reserve ports that the Xray service already assigned to the non-custom items.
        var usedPorts = new HashSet<int>(selecteds.Where(s => s.Port > 0).Select(s => s.Port));
        var seed = AppManager.Instance.GetLocalPort(EInboundProtocol.speedtest);

        foreach (var it in customs)
        {
            it.AllowTest = false;

            var addressFileName = it.Profile.Address;
            if (!File.Exists(addressFileName))
            {
                addressFileName = Utils.GetConfigPath(addressFileName);
            }
            if (!File.Exists(addressFileName))
            {
                continue;
            }

            if (JsonUtils.ParseJson(File.ReadAllText(addressFileName)) is not JsonObject customRoot)
            {
                continue;
            }
            if (customRoot["outbounds"] is not JsonArray customOutbounds)
            {
                continue;
            }

            var proxy = customOutbounds
                .OfType<JsonObject>()
                .FirstOrDefault(o => XrayJsonTemplateFmt.IsProxyProtocol(GetJsonProtocol(o)));
            if (proxy == null)
            {
                continue;
            }

            int port;
            while (true)
            {
                port = Utils.GetFreePort(seed++);
                if (usedPorts.Add(port))
                {
                    break;
                }
            }

            var inboundTag = $"{EInboundProtocol.mixed}{port}";
            var proxyTag = $"{Global.ProxyTag}{port}";

            inbounds.Add(new JsonObject
            {
                ["tag"] = inboundTag,
                ["listen"] = Global.Loopback,
                ["port"] = port,
                ["protocol"] = nameof(EInboundProtocol.mixed),
                ["settings"] = new JsonObject { ["udp"] = true, ["auth"] = "noauth" },
            });

            var proxyClone = (JsonObject)proxy.DeepClone();
            proxyClone["tag"] = proxyTag;
            proxyClone.Remove("mux");
            outbounds.Add(proxyClone);

            rules.Add(new JsonObject
            {
                ["type"] = "field",
                ["inboundTag"] = new JsonArray { inboundTag },
                ["outboundTag"] = proxyTag,
            });

            it.Port = port;
            it.AllowTest = true;
        }

        result.Data = JsonUtils.Serialize(root);
    }

    public static async Task<RetResult> GenerateClientSpeedtestConfig(Config config, CoreConfigContext context, ServerTestItem testItem, string fileName)
    {
        var result = new RetResult();
        var initPort = AppManager.Instance.GetLocalPort(EInboundProtocol.speedtest);
        var port = Utils.GetFreePort(initPort + testItem.QueueNum);
        testItem.Port = port;

        if (context.RunCoreType == ECoreType.sing_box)
        {
            result = new CoreConfigSingboxService(context).GenerateClientSpeedtestConfig(port);
        }
        else
        {
            result = new CoreConfigV2rayService(context).GenerateClientSpeedtestConfig(port);
        }
        if (result.Success != true)
        {
            return result;
        }

        await File.WriteAllTextAsync(fileName, result.Data.ToString());
        return result;
    }
}
