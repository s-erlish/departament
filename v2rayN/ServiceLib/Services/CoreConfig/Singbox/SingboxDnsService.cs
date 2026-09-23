namespace ServiceLib.Services.CoreConfig;

public partial class CoreConfigSingboxService
{
    private void GenDns()
    {
        try
        {
            var item = context.RawDnsItem;
            if (item is { Enabled: true })
            {
                GenDnsCustom();
                MigrateDnsRuleStrategies();
                return;
            }

            GenDnsServers();
            GenDnsRules();

            _coreConfig.dns ??= new Dns4Sbox();

            // NB: `independent_cache` is deliberately NOT set here. sing-box 1.14 always keys the
            // DNS cache by transport name, so the option became a no-op, was deprecated with a
            // startup WARN and is scheduled for removal in 1.16 — where an unknown field aborts
            // the core. Omitting it is the one form both old and new cores accept.

            // final dns
            var routing = context.RoutingItem;
            var useDirectDns = false;
            if (routing != null)
            {
                var rules = JsonUtils.Deserialize<List<RulesItem>>(routing.RuleSet) ?? [];

                if (rules?.LastOrDefault() is { OutboundTag: Global.DirectTag } lastRule)
                {
                    var noDomain = lastRule.Domain == null || lastRule.Domain.Count == 0;
                    var noProcess = lastRule.Process == null || lastRule.Process.Count == 0;
                    var isAnyIp = lastRule.Ip == null || lastRule.Ip.Count == 0 || lastRule.Ip.Contains("0.0.0.0/0");
                    var isAnyPort = string.IsNullOrEmpty(lastRule.Port) || lastRule.Port == "0-65535";
                    var isAnyNetwork = string.IsNullOrEmpty(lastRule.Network) || lastRule.Network == "tcp,udp";
                    useDirectDns = noDomain && noProcess && isAnyIp && isAnyPort && isAnyNetwork;
                }
            }
            _coreConfig.dns.final = useDirectDns ? Global.SingboxDirectDNSTag : Global.SingboxRemoteDNSTag;
            var simpleDnsItem = context.SimpleDnsItem;
            if ((!useDirectDns) && simpleDnsItem.FakeIP == true && simpleDnsItem.GlobalFakeIp == false)
            {
                _coreConfig.dns.rules.Add(new()
                {
                    server = Global.SingboxFakeDNSTag,
                    query_type = new List<int> { 1, 28 }, // A and AAAA
                    rewrite_ttl = 1,
                });
            }
            MigrateDnsRuleStrategies();
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
        }
    }

    /// <summary>
    /// Семейство адресов у правил DNS — без <c>strategy</c> в действии правила.
    ///
    /// sing-box 1.14 выключает устаревший режим DNS, как только в списке есть query_type, — а у нас
    /// он есть по умолчанию (блокировка HTTPS/SVCB), — и тогда strategy у действия правила фатален:
    ///   create service: initialize dns router: Legacy `strategy` DNS rule action option is deprecated…
    /// Без query_type — предупреждение и удаление в 1.16. Сюда вели непустые Strategy4Freedom /
    /// Strategy4Proxy (они ставят strategy на правила защиты, clash_mode и правила маршрута) и
    /// DNS-шаблоны с prefer_ipv4 у правил.
    ///
    /// Перенос — тот, что предлагает сам sing-box («strategy → rule items»), в форме, понятной и
    /// старым ядрам. ipv4_only / ipv6_only становятся правилом-близнецом перед исходным, которое на
    /// AAAA / A отвечает пустым NOERROR, — ровно то, что делало действие. prefer_* просто снимается:
    /// на запросы программ оно не влияет, а порядок семейств при собственных разрешениях ядра задаёт
    /// route.default_domain_resolver, где strategy законен. У логического правила близнеца не
    /// строим: вложенные правила ConvertGeo2Ruleset не переводит с geosite, — только снимаем strategy.
    /// </summary>
    private void MigrateDnsRuleStrategies()
    {
        var rules = _coreConfig.dns?.rules;
        if (rules == null)
        {
            return;
        }

        for (var i = 0; i < rules.Count; i++)
        {
            var rule = rules[i];
            var strategy = rule.strategy;
            if (strategy.IsNullOrEmpty())
            {
                continue;
            }
            rule.strategy = null;

            int? refusedType = strategy switch
            {
                "ipv4_only" => 28, // AAAA
                "ipv6_only" => 1, // A
                _ => null,
            };
            if (refusedType is not { } refused || rule.type == "logical")
            {
                continue;
            }
            //  Правило уже ограничено типами запросов, и отказного среди них нет — близнецу нечего ловить.
            if (rule.query_type is { Count: > 0 } types && !types.Contains(refused))
            {
                continue;
            }

            var twin = JsonUtils.DeepCopy(rule)!;
            twin.server = null;
            twin.rewrite_ttl = null;
            twin.disable_cache = null;
            twin.client_subnet = null;
            twin.action = "predefined";
            twin.rcode = "NOERROR";
            twin.query_type = [refused];
            rules.Insert(i, twin);
            i++;
        }
    }

    private void GenDnsServers()
    {
        var simpleDnsItem = context.SimpleDnsItem;
        var finalDns = GenBootstrapDns();

        var directDns = ParseDnsAddress(simpleDnsItem.DirectDNS ?? Global.DomainDirectDNSAddress.First());
        directDns.tag = Global.SingboxDirectDNSTag;
        directDns.domain_resolver = Global.SingboxLocalDNSTag;

        var remoteDns = ParseDnsAddress(simpleDnsItem.RemoteDNS ?? Global.DomainRemoteDNSAddress.First());
        remoteDns.tag = Global.SingboxRemoteDNSTag;
        remoteDns.detour = Global.ProxyTag;
        remoteDns.domain_resolver = Global.SingboxLocalDNSTag;

        var hostsDns = new Server4Sbox
        {
            tag = Global.SingboxHostsDNSTag,
            type = "hosts",
            predefined = new(),
        };
        if (simpleDnsItem.AddCommonHosts == true)
        {
            hostsDns.predefined = Global.PredefinedHosts;
        }

        if (simpleDnsItem.UseSystemHosts == true)
        {
            var systemHosts = Utils.GetSystemHosts();
            if (systemHosts is { Count: > 0 })
            {
                foreach (var host in systemHosts)
                {
                    hostsDns.predefined.TryAdd(host.Key, new List<string> { host.Value });
                }
            }
        }

        foreach (var kvp in Utils.ParseHostsToDictionary(simpleDnsItem.Hosts))
        {
            // only allow full match
            // like example.com and full:example.com,
            // but not domain:example.com, keyword:example.com or regex:example.com etc.
            var testRule = new Rule4Sbox();
            if (!ParseV2Domain(kvp.Key, testRule))
            {
                continue;
            }
            if (testRule.domain_keyword?.Count > 0 && !kvp.Key.Contains(':'))
            {
                testRule.domain = testRule.domain_keyword;
                testRule.domain_keyword = null;
            }
            if (testRule.domain?.Count == 1)
            {
                hostsDns.predefined[testRule.domain.First()] = kvp.Value.Where(Utils.IsIpAddress).ToList();
            }
        }

        foreach (var host in hostsDns.predefined)
        {
            if (finalDns.server == host.Key)
            {
                finalDns.domain_resolver = Global.SingboxHostsDNSTag;
            }
            if (remoteDns.server == host.Key)
            {
                remoteDns.domain_resolver = Global.SingboxHostsDNSTag;
            }
            if (directDns.server == host.Key)
            {
                directDns.domain_resolver = Global.SingboxHostsDNSTag;
            }
        }

        _coreConfig.dns ??= new Dns4Sbox();
        _coreConfig.dns.servers ??= [];
        _coreConfig.dns.servers.Add(remoteDns);
        _coreConfig.dns.servers.Add(directDns);
        _coreConfig.dns.servers.Add(hostsDns);

        // fake ip
        if (simpleDnsItem.FakeIP == true)
        {
            var fakeip = new Server4Sbox
            {
                tag = Global.SingboxFakeDNSTag,
                type = "fakeip",
                inet4_range = "198.18.0.0/15",
                inet6_range = "fc00::/18",
            };
            _coreConfig.dns.servers.Add(fakeip);
        }
    }

    private Server4Sbox GenBootstrapDns()
    {
        var finalDns = ParseDnsAddress(context.SimpleDnsItem?.BootstrapDNS ?? Global.DomainPureIPDNSAddress.First());
        finalDns.tag = Global.SingboxLocalDNSTag;
        _coreConfig.dns ??= new Dns4Sbox();
        _coreConfig.dns.servers ??= [];
        _coreConfig.dns.servers.Add(finalDns);
        return finalDns;
    }

    /// <summary>
    /// First DNS rule: send the domains the `hosts` server can actually answer to that server.
    ///
    /// The old form was <c>{ ip_accept_any: true, server: hosts }</c> — "route here, and only keep
    /// the answer if an address came back". That is a Legacy Address Filter Field. sing-box 1.14
    /// turns its legacy DNS mode OFF as soon as ANY rule (or sub-rule of a logical rule) carries
    /// <c>query_type</c> or <c>ip_version</c> — which our own rules do, both to block HTTPS/SVCB
    /// and for fakeip — and with legacy mode off a rule carrying ip_accept_any / ip_cidr /
    /// ip_is_private without <c>match_response</c> is fatal:
    ///   FATAL create service: initialize dns router: validate dns rule[0]: Response Match Fields
    ///   (…, ip_accept_any, …) require match_response to be enabled
    /// The core then exits at startup and the tunnel never comes up.
    ///
    /// sing-box's own migration offers <c>preferred_by</c>, but that DNS rule item only exists from
    /// 1.14, so it would break every older core. Listing the domains explicitly needs nothing newer
    /// than a plain <c>domain</c> matcher, so one config satisfies old and new cores alike — and the
    /// list is exact, because the `hosts` server answers precisely these predefined entries.
    /// </summary>
    private void AddHostsDnsRule()
    {
        var hostsDomains = _coreConfig.dns?.servers?
            .FirstOrDefault(t => t.tag == Global.SingboxHostsDNSTag)?
            .predefined?
            .Where(t => t.Value is { Count: > 0 })
            .Select(t => t.Key)
            .ToList() ?? [];

        // No predefined entry means the hosts server has nothing to answer; a rule without a
        // matcher would swallow every query and NXDOMAIN it, so leave it out entirely.
        if (hostsDomains.Count == 0)
        {
            return;
        }

        _coreConfig.dns.rules.Add(new()
        {
            server = Global.SingboxHostsDNSTag,
            domain = hostsDomains,
            // The hosts server only ever answers A and AAAA; every other type it turns into
            // NXDOMAIN. ip_accept_any used to let those fall through to real DNS, so pin the rule
            // to A/AAAA to keep exactly that behaviour instead of NXDOMAIN-ing, say, an HTTPS
            // query for a domain the owner pinned in Hosts.
            query_type = new List<int> { 1, 28 },
        });
    }

    private void GenDnsRules()
    {
        var simpleDnsItem = context.SimpleDnsItem;
        _coreConfig.dns ??= new Dns4Sbox();
        _coreConfig.dns.rules ??= [];

        AddHostsDnsRule();

        if (context.ProtectDomainList.Count > 0)
        {
            _coreConfig.dns.rules.Add(new()
            {
                server = Global.SingboxDirectDNSTag,
                strategy = Utils.DomainStrategy4Sbox(simpleDnsItem.Strategy4Freedom),
                domain = context.ProtectDomainList.ToList(),
            });
        }

        _coreConfig.dns.rules.AddRange(new[]
        {
            new Rule4Sbox
            {
                server = Global.SingboxRemoteDNSTag,
                strategy = Utils.DomainStrategy4Sbox(simpleDnsItem.Strategy4Proxy),
                clash_mode = nameof(ERuleMode.Global)
            },
            new Rule4Sbox
            {
                server = Global.SingboxDirectDNSTag,
                strategy = Utils.DomainStrategy4Sbox(simpleDnsItem.Strategy4Freedom),
                clash_mode = nameof(ERuleMode.Direct)
            }
        });

        foreach (var kvp in Utils.ParseHostsToDictionary(simpleDnsItem.Hosts))
        {
            var predefined = kvp.Value.First();
            if (predefined.IsNullOrEmpty())
            {
                continue;
            }
            var rule = new Rule4Sbox()
            {
                query_type = [1, 5, 28], // A, CNAME and AAAA
                action = "predefined",
                rcode = "NOERROR",
            };
            if (!ParseV2Domain(kvp.Key, rule))
            {
                continue;
            }
            // see: https://xtls.github.io/en/config/dns.html#dnsobject
            // The matching format (domain:, full:, etc.) is the same as the domain
            // in the commonly used Routing System. The difference is that without a prefix,
            // it defaults to using the full: prefix (similar to the common hosts file syntax).
            if (rule.domain_keyword?.Count > 0 && !kvp.Key.Contains(':'))
            {
                rule.domain = rule.domain_keyword;
                rule.domain_keyword = null;
            }
            // example.com #0 -> example.com with NOERROR
            if (predefined.StartsWith('#') && int.TryParse(predefined.AsSpan(1), out var rcode))
            {
                rule.rcode = rcode switch
                {
                    0 => "NOERROR",
                    1 => "FORMERR",
                    2 => "SERVFAIL",
                    3 => "NXDOMAIN",
                    4 => "NOTIMP",
                    5 => "REFUSED",
                    _ => "NOERROR",
                };
            }
            else if (Utils.IsDomain(predefined))
            {
                // example.com CNAME target.com -> example.com with CNAME target.com
                rule.answer = new List<string> { $"*. IN CNAME {predefined}." };
            }
            else if (Utils.IsIpAddress(predefined) && (rule.domain?.Count ?? 0) == 0)
            {
                // not full match, but an IP address, treat it as predefined answer
                if (Utils.IsIpv6(predefined))
                {
                    rule.answer = new List<string> { $"*. IN AAAA {predefined}" };
                }
                else
                {
                    rule.answer = new List<string> { $"*. IN A {predefined}" };
                }
            }
            else
            {
                continue;
            }
            _coreConfig.dns.rules.Add(rule);
        }

        if (simpleDnsItem.BlockBindingQuery == true)
        {
            _coreConfig.dns.rules.Add(new()
            {
                query_type = [64, 65],
                action = "predefined",
                rcode = "NOERROR"
            });
        }

        if (simpleDnsItem.FakeIP == true && simpleDnsItem.GlobalFakeIp == true)
        {
            var fakeipFilterRule = JsonUtils.Deserialize<Rule4Sbox>(EmbedUtils.GetEmbedText(Global.SingboxFakeIPFilterFileName));
            fakeipFilterRule.invert = true;
            var rule4Fake = new Rule4Sbox
            {
                server = Global.SingboxFakeDNSTag,
                type = "logical",
                mode = "and",
                rewrite_ttl = 1,
                rules =
                [
                    new()
                    {
                        query_type = [1, 28], // A and AAAA
                    },
                    fakeipFilterRule
                ]
            };

            _coreConfig.dns.rules.Add(rule4Fake);
        }

        var routing = context.RoutingItem;
        if (routing == null)
        {
            return;
        }

        var rules = JsonUtils.Deserialize<List<RulesItem>>(routing.RuleSet) ?? [];
        var expectedIPCidr = new List<string>();
        var expectedIPsRegions = new List<string>();
        var regionName = string.Empty;

        if (!string.IsNullOrEmpty(simpleDnsItem?.DirectExpectedIPs))
        {
            var ipItems = simpleDnsItem.DirectExpectedIPs
                .Split(new[] { ',', ';' }, StringSplitOptions.RemoveEmptyEntries)
                .Select(s => s.Trim())
                .Where(s => !string.IsNullOrEmpty(s))
                .ToList();

            foreach (var ip in ipItems)
            {
                if (ip.StartsWith(Global.GeoIPPrefix, StringComparison.OrdinalIgnoreCase))
                {
                    var region = ip[Global.GeoIPPrefix.Length..];
                    if (string.IsNullOrEmpty(region))
                    {
                        continue;
                    }

                    expectedIPsRegions.Add(region);
                    regionName = region;
                }
                else
                {
                    expectedIPCidr.Add(ip);
                }
            }
        }

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

            var rule = new Rule4Sbox();
            var validDomains = item.Domain.Count(it => ParseV2Domain(it, rule));
            if (validDomains <= 0)
            {
                continue;
            }

            if (item.OutboundTag == Global.DirectTag)
            {
                rule.server = Global.SingboxDirectDNSTag;
                rule.strategy = Utils.DomainStrategy4Sbox(simpleDnsItem.Strategy4Freedom);

                if (expectedIPsRegions.Count > 0 && rule.geosite?.Count > 0 && !regionName.IsNullOrEmpty())
                {
                    var regionGeosite = rule.geosite.Where(g => g.EndsWith($"-{regionName}", StringComparison.OrdinalIgnoreCase)
                                                     || g.EndsWith($"@{regionName}", StringComparison.OrdinalIgnoreCase)
                                                     || g == regionName).ToList();
                    if (regionGeosite.Count > 0)
                    {
                        rule.geosite.RemoveAll(regionGeosite.Contains);
                        var rule4ExpectedIPs = JsonUtils.DeepCopy(rule);
                        rule4ExpectedIPs.geosite = regionGeosite;
                        if (expectedIPsRegions.Count > 0)
                        {
                            rule4ExpectedIPs.geoip = expectedIPsRegions;
                        }
                        if (expectedIPCidr.Count > 0)
                        {
                            rule4ExpectedIPs.ip_cidr = expectedIPCidr;
                        }
                        _coreConfig.dns.rules.Add(rule4ExpectedIPs);
                    }
                }
            }
            else if (item.OutboundTag == Global.BlockTag)
            {
                rule.action = "predefined";
                rule.rcode = "NXDOMAIN";
            }
            else
            {
                if (simpleDnsItem.FakeIP == true && simpleDnsItem.GlobalFakeIp == false)
                {
                    var rule4Fake = JsonUtils.DeepCopy(rule);
                    rule4Fake.server = Global.SingboxFakeDNSTag;
                    rule4Fake.query_type = new List<int> { 1, 28 }; // A and AAAA
                    rule4Fake.rewrite_ttl = 1;
                    _coreConfig.dns.rules.Add(rule4Fake);
                }
                rule.server = Global.SingboxRemoteDNSTag;
                rule.strategy = Utils.DomainStrategy4Sbox(simpleDnsItem.Strategy4Proxy);
            }

            _coreConfig.dns.rules.Add(rule);
        }
    }

    private void GenMinimizedDns()
    {
        GenDnsServers();
        foreach (var server in _coreConfig.dns!.servers.Where(s => !string.IsNullOrEmpty(s.detour)).ToList())
        {
            _coreConfig.dns.servers.Remove(server);
        }
        _coreConfig.dns ??= new();
        _coreConfig.dns.rules ??= [];
        _coreConfig.dns.rules.Clear();
        _coreConfig.dns.final = Global.SingboxDirectDNSTag;
        _coreConfig.route.default_domain_resolver = new()
        {
            server = Global.SingboxDirectDNSTag,
        };
    }

    private void GenDnsCustom()
    {
        try
        {
            var item = context.RawDnsItem;
            var strDNS = string.Empty;
            if (context.IsTunEnabled)
            {
                strDNS = string.IsNullOrEmpty(item?.TunDNS) ? EmbedUtils.GetEmbedText(Global.TunSingboxDNSFileName) : item?.TunDNS;
            }
            else
            {
                strDNS = string.IsNullOrEmpty(item?.NormalDNS) ? EmbedUtils.GetEmbedText(Global.DNSSingboxNormalFileName) : item?.NormalDNS;
            }

            var dns4Sbox = JsonUtils.Deserialize<Dns4Sbox>(strDNS);
            if (dns4Sbox is null)
            {
                return;
            }
            _coreConfig.dns = dns4Sbox;
            GenDnsProtectCustom();
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
        }
    }

    private void GenDnsProtectCustom()
    {
        var dnsItem = context.RawDnsItem;
        var dns4Sbox = _coreConfig.dns ?? new();
        dns4Sbox.servers ??= [];
        dns4Sbox.rules ??= [];

        var tag = Global.SingboxLocalDNSTag;
        dns4Sbox.rules.Insert(0, new()
        {
            server = tag,
            clash_mode = nameof(ERuleMode.Direct)
        });
        dns4Sbox.rules.Insert(0, new()
        {
            server = dns4Sbox.servers.Where(t => t.detour == Global.ProxyTag).Select(t => t.tag).FirstOrDefault() ?? "remote",
            clash_mode = nameof(ERuleMode.Global)
        });

        var finalDnsAddress = string.IsNullOrEmpty(dnsItem?.DomainDNSAddress) ? Global.DomainPureIPDNSAddress.FirstOrDefault() : dnsItem?.DomainDNSAddress;

        var localDnsServer = ParseDnsAddress(finalDnsAddress);
        localDnsServer.tag = tag;

        dns4Sbox.servers.Add(localDnsServer);
        var protectDomainRule = BuildProtectDomainRule();
        if (protectDomainRule != null)
        {
            dns4Sbox.rules.Insert(0, protectDomainRule);
        }

        _coreConfig.dns = dns4Sbox;
    }

    private Rule4Sbox? BuildProtectDomainRule()
    {
        if (context.ProtectDomainList.Count == 0)
        {
            return null;
        }
        return new()
        {
            server = Global.SingboxLocalDNSTag,
            domain = context.ProtectDomainList.ToList(),
        };
    }

    private static Server4Sbox? ParseDnsAddress(string address)
    {
        var addressFirst = address?.Split(address.Contains(',') ? ',' : ';').FirstOrDefault()?.Trim();
        if (string.IsNullOrEmpty(addressFirst))
        {
            return null;
        }

        var server = new Server4Sbox();

        if (addressFirst is "local" or "localhost")
        {
            server.type = "local";
            return server;
        }

        var (domain, scheme, port, path) = Utils.ParseUrl(addressFirst);

        if (scheme.Equals("dhcp", StringComparison.OrdinalIgnoreCase))
        {
            server.type = "dhcp";
            if ((!domain.IsNullOrEmpty()) && domain != "auto")
            {
                server.server = domain;
            }
            return server;
        }

        if (scheme.IsNullOrEmpty())
        {
            // udp dns
            server.type = "udp";
        }
        else
        {
            // server.type = scheme.ToLower();

            // remove "+local" suffix
            // TODO: "+local" suffix decide server.detour = "direct" ?
            server.type = scheme.Replace("+local", "", StringComparison.OrdinalIgnoreCase).ToLower();
        }

        server.server = domain;
        if (port != 0)
        {
            server.server_port = port;
        }
        if ((server.type == "https" || server.type == "h3") && !string.IsNullOrEmpty(path) && path != "/")
        {
            server.path = path;
        }
        return server;
    }
}
