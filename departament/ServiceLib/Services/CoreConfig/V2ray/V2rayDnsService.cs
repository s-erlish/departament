namespace ServiceLib.Services.CoreConfig;

public partial class CoreConfigV2rayService
{
    private void GenDns()
    {
        try
        {
            var item = context.RawDnsItem;
            if (item is { Enabled: true })
            {
                GenDnsCustom();

                if (_coreConfig.routing.domainStrategy != Global.IPIfNonMatch)
                {
                    return;
                }

                // DNS routing
                var dnsObj = JsonUtils.SerializeToNode(_coreConfig.dns);
                if (dnsObj == null)
                {
                    return;
                }

                dnsObj["tag"] = Global.DnsTag;
                _coreConfig.dns = JsonUtils.Deserialize<Dns4Ray>(JsonUtils.Serialize(dnsObj));
                _coreConfig.routing.rules.Add(new RulesItem4Ray
                {
                    type = "field",
                    inboundTag = new List<string> { Global.DnsTag },
                    outboundTag = Global.ProxyTag,
                });
                return;
            }
            var simpleDnsItem = context.SimpleDnsItem;
            var dnsItem = _coreConfig.dns as Dns4Ray ?? new Dns4Ray();

            var strategy4Freedom = simpleDnsItem?.Strategy4Freedom ?? Global.AsIs;
            //Outbound Freedom domainStrategy
            if (strategy4Freedom.IsNotEmpty() && strategy4Freedom != Global.AsIs)
            {
                var outbound = _coreConfig.outbounds.FirstOrDefault(t => t is { protocol: "freedom", tag: Global.DirectTag });
                if (outbound != null)
                {
                    outbound.settings = new()
                    {
                        domainStrategy = strategy4Freedom,
                        userLevel = 0
                    };
                }
            }

            var strategy4Proxy = simpleDnsItem?.Strategy4Proxy ?? Global.AsIs;
            //Outbound Proxy domainStrategy
            if (strategy4Proxy.IsNotEmpty() && strategy4Proxy != Global.AsIs)
            {
                var xraySupportConfigTypeNames = Global.XraySupportConfigType
                        .Select(x => x == EConfigType.Hysteria2 ? "hysteria" : Global.ProtocolTypes[x])
                        .ToHashSet();
                _coreConfig.outbounds
                    .Where(t => xraySupportConfigTypeNames.Contains(t.protocol))
                    .ToList()
                    .ForEach(outbound => outbound.targetStrategy = strategy4Proxy);
            }

            FillDnsServers(dnsItem);
            FillDnsHosts(dnsItem);

            dnsItem.serveStale = simpleDnsItem?.ServeStale is true ? true : null;
            dnsItem.enableParallelQuery = simpleDnsItem?.ParallelQuery is true ? true : null;

            // DNS routing
            var directDnsTags = dnsItem.servers
                .Select(server =>
                {
                    var tagNode = (server as JsonObject)?["tag"];
                    return tagNode is JsonValue value && value.TryGetValue<string>(out var tag) ? tag : null;
                })
                .Where(tag => tag is not null && tag.StartsWith(Global.DirectDnsTag, StringComparison.Ordinal))
                .Select(tag => tag!)
                .ToList();
            if (directDnsTags.Count > 0)
            {
                //  ПЕРВЫМ, до правил маршрута. Свои запросы прямой DNS Xray пропускает через те же
                //  правила, что и трафик, а это правило стояло в самом конце, и любое правило по IP
                //  резолвера срабатывало раньше. Встроенный «Blacklist» шлёт 77.88.8.8 и 77.88.8.1 в
                //  proxy («публичные DNS за рубежом» — так их видит апстрим из Китая): прямой DNS на
                //  Яндексе уходил в туннель, включая запрос за адресом самого VPN-сервера, которому
                //  туннель и нужен, а в режиме TUN это петля. Китайский 119.29.29.29 ни одно правило
                //  «Blacklist» в proxy не вело, поэтому раньше это не всплывало. У sing-box такого
                //  нет: DNS-сервер без detour у него всегда ходит напрямую.
                _coreConfig.routing.rules.Insert(0, new()
                {
                    type = "field",
                    inboundTag = directDnsTags,
                    outboundTag = Global.DirectTag,
                });
            }

            var finalRule = BuildFinalRule();
            dnsItem.tag = Global.DnsTag;
            _coreConfig.routing.rules.Add(new()
            {
                type = "field",
                inboundTag = [Global.DnsTag],
                outboundTag = finalRule.outboundTag,
                balancerTag = finalRule.balancerTag,
            });

            _coreConfig.dns = dnsItem;
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
        }
    }

    private void FillDnsServers(Dns4Ray dnsItem)
    {
        var simpleDNSItem = context.SimpleDnsItem;

        var directDNSAddress = ParseDnsAddresses(simpleDNSItem?.DirectDNS, Global.DomainDirectDNSAddress.First());
        var remoteDNSAddress = ParseDnsAddresses(simpleDNSItem?.RemoteDNS, Global.DomainRemoteDNSAddress.First());

        var directDomainList = new List<string>();
        var directGeositeList = new List<string>();
        var proxyDomainList = new List<string>();
        var proxyGeositeList = new List<string>();
        var expectedDomainList = new List<string>();
        var expectedIPs = new List<string>();
        var regionName = string.Empty;

        var bootstrapDNSAddress = ParseDnsAddresses(simpleDNSItem?.BootstrapDNS, Global.DomainPureIPDNSAddress.First());
        var dnsServerDomains = new List<string>();

        foreach (var dns in directDNSAddress)
        {
            var (domain, _, _, _) = Utils.ParseUrl(dns);
            if (domain == "localhost")
            {
                continue;
            }
            if (Utils.IsDomain(domain))
            {
                dnsServerDomains.Add($"full:{domain}");
            }
        }
        foreach (var dns in remoteDNSAddress)
        {
            var (domain, _, _, _) = Utils.ParseUrl(dns);
            if (domain == "localhost")
            {
                continue;
            }
            if (Utils.IsDomain(domain))
            {
                dnsServerDomains.Add($"full:{domain}");
            }
        }
        dnsServerDomains = dnsServerDomains.Distinct().ToList();

        if (!string.IsNullOrEmpty(simpleDNSItem?.DirectExpectedIPs))
        {
            expectedIPs = simpleDNSItem.DirectExpectedIPs
                .Split(new[] { ',', ';' }, StringSplitOptions.RemoveEmptyEntries)
                .Select(s => s.Trim())
                .Where(s => !string.IsNullOrEmpty(s))
                .ToList();

            foreach (var region in from ip in expectedIPs
                                   where ip.StartsWith(Global.GeoIPPrefix, StringComparison.OrdinalIgnoreCase)
                                   select ip[Global.GeoIPPrefix.Length..]
                                   into region
                                   where !string.IsNullOrEmpty(region)
                                   select region)
            {
                regionName = region;
            }
        }

        var routing = context.RoutingItem;
        List<RulesItem>? rules = null;
        rules = JsonUtils.Deserialize<List<RulesItem>>(routing?.RuleSet) ?? [];
        foreach (var item in rules)
        {
            if (!item.Enabled || item.Domain is null || item.Domain.Count == 0)
            {
                continue;
            }

            if (item.RuleType == ERuleType.Routing)
            {
                continue;
            }

            foreach (var domain in item.Domain)
            {
                if (domain.StartsWith('#'))
                {
                    continue;
                }

                var normalizedDomain = domain.Replace(Global.RoutingRuleComma, ",");

                if (item.OutboundTag == Global.DirectTag)
                {
                    if (normalizedDomain.StartsWith(Global.GeoSitePrefix) || normalizedDomain.StartsWith("ext:"))
                    {
                        var isExpectedDomain = !regionName.IsNullOrEmpty()
                            && (normalizedDomain.EndsWith($"-{regionName}")
                            || normalizedDomain.EndsWith($"@{regionName}")
                            || normalizedDomain == Global.GeoSitePrefix + regionName);
                        var targetList = isExpectedDomain ? expectedDomainList : directGeositeList;
                        targetList.Add(normalizedDomain);
                    }
                    else
                    {
                        directDomainList.Add(normalizedDomain);
                    }
                }
                else if (item.OutboundTag != Global.BlockTag)
                {
                    if (normalizedDomain.StartsWith(Global.GeoSitePrefix) || normalizedDomain.StartsWith("ext:"))
                    {
                        proxyGeositeList.Add(normalizedDomain);
                    }
                    else
                    {
                        proxyDomainList.Add(normalizedDomain);
                    }
                }
            }
        }

        if (context.ProtectDomainList.Count > 0)
        {
            directDomainList.AddRange(context.ProtectDomainList);
        }

        dnsItem.servers ??= [];

        var directDnsTagIndex = 1;

        AddDnsServers(remoteDNSAddress, proxyDomainList);
        AddDnsServers(directDNSAddress, directDomainList, true);
        AddDnsServers(remoteDNSAddress, proxyGeositeList);
        AddDnsServers(directDNSAddress, directGeositeList, true);
        AddDnsServers(directDNSAddress, expectedDomainList, true, expectedIPs);
        if (dnsServerDomains.Count > 0)
        {
            //  Bootstrap — тоже прямой резолвер: имена DoH-серверов нельзя разрешать через туннель,
            //  который сам может ждать этих имён. Без метки direct-dns его вёл по правилам только
            //  «Whitelist» апстрима (китайские DNS — напрямую), а Яндекс ни в одном таком правиле нет.
            AddDnsServers(bootstrapDNSAddress, dnsServerDomains, true);
        }

        var useDirectDns = false;

        if (rules?.LastOrDefault() is { OutboundTag: Global.DirectTag } lastRule)
        {
            var noDomain = lastRule.Domain == null || lastRule.Domain.Count == 0;
            var noProcess = lastRule.Process == null || lastRule.Process.Count == 0;
            var isAnyIp = lastRule.Ip == null || lastRule.Ip.Count == 0 || lastRule.Ip.Contains("0.0.0.0/0");
            var isAnyPort = string.IsNullOrEmpty(lastRule.Port) || lastRule.Port == "0-65535";
            var isAnyNetwork = string.IsNullOrEmpty(lastRule.Network) || lastRule.Network == "tcp,udp";
            useDirectDns = noDomain && noProcess && isAnyIp && isAnyPort && isAnyNetwork;
        }

        if (!useDirectDns)
        {
            dnsItem.servers.AddRange(remoteDNSAddress);
        }
        else
        {
            foreach (var dns in directDNSAddress)
            {
                var dnsServer = CreateDnsServer(dns, []);
                dnsServer.tag = $"{Global.DirectDnsTag}-{directDnsTagIndex++}";
                dnsServer.skipFallback = false;
                dnsItem.servers.Add(JsonUtils.SerializeToNode(dnsServer,
                    new JsonSerializerOptions { DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull }));
            }
        }
        return;

        static List<string> ParseDnsAddresses(string? dnsInput, string defaultAddress)
        {
            var addresses = dnsInput?.Split(dnsInput.Contains(',') ? ',' : ';')
                .Select(addr => addr.Trim())
                .Where(addr => !string.IsNullOrEmpty(addr))
                .Select(addr => addr.StartsWith("dhcp", StringComparison.OrdinalIgnoreCase) ? "localhost" : addr)
                .Distinct()
                .ToList() ?? [defaultAddress];
            return addresses.Count > 0 ? addresses : new List<string> { defaultAddress };
        }

        static DnsServer4Ray CreateDnsServer(string dnsAddress, List<string> domains, List<string>? expectedIPs = null)
        {
            var (domain, scheme, port, path) = Utils.ParseUrl(dnsAddress);
            var domainFinal = dnsAddress;
            int? portFinal = null;
            if (scheme.IsNullOrEmpty() || scheme.StartsWith("udp", StringComparison.OrdinalIgnoreCase))
            {
                domainFinal = domain;
                portFinal = port > 0 ? port : null;
            }
            else if (scheme.StartsWith("tcp", StringComparison.OrdinalIgnoreCase))
            {
                domainFinal = scheme + "://" + domain;
                portFinal = port > 0 ? port : null;
            }
            var dnsServer = new DnsServer4Ray
            {
                address = domainFinal,
                port = portFinal,
                skipFallback = true,
                domains = domains.Count > 0 ? domains : null,
                expectedIPs = expectedIPs?.Count > 0 ? expectedIPs : null
            };
            return dnsServer;
        }

        void AddDnsServers(List<string> dnsAddresses, List<string> domains, bool isDirectDns = false, List<string>? expectedIPs = null)
        {
            if (domains.Count <= 0)
            {
                return;
            }
            foreach (var dnsAddress in dnsAddresses)
            {
                var dnsServer = CreateDnsServer(dnsAddress, domains, expectedIPs);
                if (isDirectDns)
                {
                    dnsServer.tag = $"{Global.DirectDnsTag}-{directDnsTagIndex++}";
                }
                var dnsServerNode = JsonUtils.SerializeToNode(dnsServer,
                    new JsonSerializerOptions { DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull });
                dnsItem.servers.Add(dnsServerNode);
            }
        }
    }

    private void FillDnsHosts(Dns4Ray dnsItem)
    {
        var simpleDNSItem = context.SimpleDnsItem;
        if (simpleDNSItem.AddCommonHosts == false && simpleDNSItem.UseSystemHosts == false && simpleDNSItem.Hosts.IsNullOrEmpty())
        {
            return;
        }
        dnsItem.hosts ??= new Dictionary<string, object>();
        if (simpleDNSItem.AddCommonHosts == true)
        {
            dnsItem.hosts = Global.PredefinedHosts.ToDictionary(
                kvp => kvp.Key,
                kvp => (object)kvp.Value
            );
        }

        if (simpleDNSItem.UseSystemHosts == true)
        {
            var systemHosts = Utils.GetSystemHosts();
            var normalHost = dnsItem.hosts;

            if (normalHost != null && systemHosts?.Count > 0)
            {
                foreach (var host in systemHosts)
                {
                    normalHost.TryAdd(host.Key, new List<string> { host.Value });
                }
            }
        }

        foreach (var kvp in Utils.ParseHostsToDictionary(simpleDNSItem.Hosts))
        {
            dnsItem.hosts[kvp.Key] = kvp.Value;
        }
    }

    private void GenDnsCustom()
    {
        try
        {
            var item = context.RawDnsItem;
            var customDNS = context.IsTunEnabled ? item?.TunDNS : item?.NormalDNS;
            var domainStrategy4Freedom = item?.DomainStrategy4Freedom;
            if (customDNS.IsNullOrEmpty())
            {
                customDNS = EmbedUtils.GetEmbedText(Global.DNSV2rayNormalFileName);
            }

            //Outbound Freedom domainStrategy
            if (domainStrategy4Freedom.IsNotEmpty())
            {
                var outbound = _coreConfig.outbounds.FirstOrDefault(t => t is { protocol: "freedom", tag: Global.DirectTag });
                if (outbound != null)
                {
                    outbound.settings = new()
                    {
                        domainStrategy = domainStrategy4Freedom,
                        userLevel = 0,
                    };
                }
            }

            var obj = JsonUtils.ParseJson(customDNS);
            if (obj is null)
            {
                List<string> servers = [];
                var arrDNS = customDNS.Split(',');
                foreach (var str in arrDNS)
                {
                    servers.Add(str);
                }
                obj = JsonUtils.ParseJson("{}");
                obj["servers"] = JsonUtils.SerializeToNode(servers);
            }

            // Append to dns settings
            if (item.UseSystemHosts)
            {
                var systemHosts = Utils.GetSystemHosts();
                if (systemHosts.Count > 0)
                {
                    var normalHost1 = obj["hosts"];
                    if (normalHost1 != null)
                    {
                        foreach (var host in systemHosts)
                        {
                            if (normalHost1[host.Key] != null)
                            {
                                continue;
                            }

                            normalHost1[host.Key] = host.Value;
                        }
                    }
                }
            }
            var normalHost = obj["hosts"];
            if (normalHost != null)
            {
                foreach (var hostProp in normalHost.AsObject().ToList())
                {
                    if (hostProp.Value is JsonValue value && value.TryGetValue<string>(out var ip))
                    {
                        normalHost[hostProp.Key] = new JsonArray(ip);
                    }
                }
            }

            FillDnsDomainsCustom(obj);

            _coreConfig.dns = obj;
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
        }
    }

    private void FillDnsDomainsCustom(JsonNode dns)
    {
        var servers = dns["servers"];
        if (servers == null)
        {
            return;
        }

        var domainList = context.ProtectDomainList;
        if (domainList.Count <= 0)
        {
            return;
        }

        var dnsItem = context.RawDnsItem;
        //  Хосты VPN-серверов разрешаются мимо туннеля: метка и правило «напрямую» первым — по той же
        //  причине, что у прямого DNS в GenDns. Раньше их вело туда только правило «Whitelist» для
        //  китайских DNS, а резолвер вне этого списка (Яндекс, например) уходил в proxy, которому
        //  этот адрес и нужен. И сам сервер — первым в списке: из совпавших по домену Xray спрашивает
        //  первый, а в своём DNS бывает сервер на целую зону (domain:ru), куда попадает и хост VPN.
        var protectTag = $"{Global.DirectDnsTag}-protect";
        var dnsServer = new DnsServer4Ray()
        {
            address = string.IsNullOrEmpty(dnsItem?.DomainDNSAddress) ? Global.DomainPureIPDNSAddress.FirstOrDefault() : dnsItem?.DomainDNSAddress,
            skipFallback = true,
            domains = domainList.ToList(),
            tag = protectTag,
        };
        servers.AsArray().Insert(0, JsonUtils.SerializeToNode(dnsServer));
        _coreConfig.routing.rules.Insert(0, new()
        {
            type = "field",
            inboundTag = [protectTag],
            outboundTag = Global.DirectTag,
        });
    }
}
