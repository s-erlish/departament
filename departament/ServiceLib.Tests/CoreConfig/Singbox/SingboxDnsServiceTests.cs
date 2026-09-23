using AwesomeAssertions;
using ServiceLib.Common;
using ServiceLib.Enums;
using ServiceLib.Handler;
using ServiceLib.Manager;
using ServiceLib.Models;
using ServiceLib.Models.CoreConfigs;
using ServiceLib.Services.CoreConfig;
using Xunit;

namespace ServiceLib.Tests.CoreConfig.Singbox;

/// <summary>
/// The DNS block the generator produces must be one sing-box will actually start on — old cores
/// and 1.14+ alike. The tests below do not need a sing-box binary: they re-implement the acceptance
/// rule the core applies at startup and assert the generated shape against it.
/// </summary>
public class SingboxDnsServiceTests
{
    /// <summary>
    /// Builds the config the app hands to the sing-box that owns the TUN device: default DNS
    /// settings as shipped, no custom DNS, TUN on.
    /// </summary>
    private static SingboxConfig Generate(Action<SimpleDNSItem>? tweakDns = null,
        IEnumerable<string>? protectDomains = null, DNSItem? rawDns = null)
    {
        var config = CoreConfigTestFactory.CreateConfig(ECoreType.sing_box);
        config.SimpleDNSItem = ConfigHandler.InitBuiltinSimpleDNS();
        tweakDns?.Invoke(config.SimpleDNSItem);
        config.TunModeItem.EnableTun = true;
        CoreConfigTestFactory.BindAppManagerConfig(config);

        var node = CoreConfigTestFactory.CreateSocksNode(ECoreType.sing_box);
        node.Address = Global.Loopback;
        node.Port = AppManager.Instance.GetLocalPort(EInboundProtocol.socks);

        var context = CoreConfigTestFactory.CreateContext(config, node, ECoreType.sing_box) with
        {
            IsTunEnabled = true,
            SimpleDnsItem = config.SimpleDNSItem,
            RawDnsItem = rawDns,
            ProtectDomainList = [.. protectDomains ?? []],
        };

        var result = new CoreConfigSingboxService(context).GenerateClientConfigContent();
        result.Success.Should().BeTrue($"ret msg: {result.Msg}");
        return JsonUtils.Deserialize<SingboxConfig>(result.Data!.ToString())!;
    }

    // --- the rule sing-box applies at startup, mirrored -------------------------------------

    /// <summary>
    /// Legacy Address Filter Fields — a rule that decides by looking at the ANSWER. Without
    /// <c>match_response</c> (which no core before 1.14 knows) these are only legal while sing-box
    /// keeps legacy DNS mode on.
    /// </summary>
    private static bool HasLegacyAddressFilter(Rule4Sbox rule) =>
        rule.ip_accept_any == true
        || rule.ip_is_private == true
        || rule.ip_cidr is { Count: > 0 }
        || rule.rule_set_ip_cidr_accept_empty == true;

    /// <summary>
    /// Anything here turns legacy DNS mode OFF — for the whole rule list at once, whichever rule
    /// or sub-rule it sits on, and no matter what order the rules are in.
    /// </summary>
    private static bool DisablesLegacyDnsMode(Rule4Sbox rule) =>
        rule.query_type is { Count: > 0 }
        || rule.action is "evaluate" or "respond";

    private static IEnumerable<Rule4Sbox> Flatten(IEnumerable<Rule4Sbox>? rules)
    {
        foreach (var rule in rules ?? [])
        {
            yield return rule;
            foreach (var sub in Flatten(rule.rules))
            {
                yield return sub;
            }
        }
    }

    /// <summary>
    /// Reproduces sing-box's startup verdict: with legacy DNS mode off, a legacy address filter or
    /// a legacy rule-action <c>strategy</c> is fatal, and the core exits before the tunnel is up.
    /// </summary>
    private static string? DescribeSingboxRejection(Dns4Sbox? dns)
    {
        var rules = Flatten(dns?.rules).ToList();
        if (!rules.Any(DisablesLegacyDnsMode))
        {
            return null;
        }

        var addressFilter = rules.FindIndex(HasLegacyAddressFilter);
        if (addressFilter >= 0)
        {
            return $"dns rule[{addressFilter}]: Response Match Fields require match_response to be enabled";
        }

        var strategy = rules.FindIndex(t => t.strategy is { Length: > 0 } and not Global.AsIs);
        if (strategy >= 0)
        {
            return $"dns rule[{strategy}]: legacy `strategy` DNS rule action option";
        }

        return null;
    }

    // --- tests -----------------------------------------------------------------------------

    [Theory]
    // fakeIp, globalFakeIp, blockBindingQuery — every combination the shipped settings can reach
    [InlineData(false, true, true)]
    [InlineData(false, true, false)]
    [InlineData(true, true, true)]
    [InlineData(true, false, true)]
    [InlineData(true, false, false)]
    public void GenDns_DnsRules_ShouldBeAcceptedBySingbox114(bool fakeIp, bool globalFakeIp, bool blockBindingQuery)
    {
        var cfg = Generate(dns =>
        {
            dns.FakeIP = fakeIp;
            dns.GlobalFakeIp = globalFakeIp;
            dns.BlockBindingQuery = blockBindingQuery;
        });

        DescribeSingboxRejection(cfg.dns).Should().BeNull();
    }

    [Fact]
    public void GenDns_HostsRule_ShouldMatchByDomainInsteadOfIpAcceptAny()
    {
        var cfg = Generate();

        var predefined = cfg.dns!.servers
            .Single(t => t.tag == Global.SingboxHostsDNSTag)
            .predefined!;
        predefined.Should().NotBeEmpty();

        var hostsRule = cfg.dns.rules.Single(t => t.server == Global.SingboxHostsDNSTag);
        hostsRule.ip_accept_any.Should().BeNull("ip_accept_any is fatal on sing-box 1.14");
        hostsRule.domain.Should().BeEquivalentTo(predefined.Keys);
        // the hosts server answers A and AAAA only; everything else must fall through as before
        hostsRule.query_type.Should().BeEquivalentTo(new[] { 1, 28 });
    }

    [Fact]
    public void GenDns_WithoutPredefinedHosts_ShouldOmitHostsRule()
    {
        var cfg = Generate(dns =>
        {
            dns.AddCommonHosts = false;
            dns.UseSystemHosts = false;
            dns.Hosts = string.Empty;
        });

        // a hosts rule with nothing to match on would swallow every query and NXDOMAIN it
        cfg.dns!.rules.Should().NotContain(t => t.server == Global.SingboxHostsDNSTag);
    }

    [Theory]
    // Strategy4Freedom, Strategy4Proxy — «Только IPv4/IPv6» и «Предпочитать», каждое на свою сторону
    [InlineData("ForceIPv4", "UseIPv6")]
    [InlineData("UseIPv4", "ForceIPv6")]
    [InlineData("ForceIPv6", "ForceIPv4")]
    public void GenDns_RuleStrategies_ShouldMoveToRuleItemsAndBeAcceptedBySingbox114(string freedom, string proxy)
    {
        var cfg = Generate(dns =>
        {
            dns.Strategy4Freedom = freedom;
            dns.Strategy4Proxy = proxy;
        }, protectDomains: ["vpn.example.com"]);

        var rules = cfg.dns!.rules;
        // strategy у действия правила при любом query_type в списке фатален на 1.14
        Flatten(rules).Should().NotContain(r => r.strategy != null);
        DescribeSingboxRejection(cfg.dns).Should().BeNull();

        // «Только одно семейство» осталось в силе: перед правилом стоит близнец с пустым ответом на
        // отказное семейство, с теми же признаками. «Предпочитать» на запросы программ не влияет.
        AssertFamilyTwin(rules, r => r.domain?.Contains("vpn.example.com") == true && r.server == Global.SingboxDirectDNSTag, freedom);
        AssertFamilyTwin(rules, r => r.clash_mode == nameof(ERuleMode.Direct) && r.server == Global.SingboxDirectDNSTag, freedom);
        AssertFamilyTwin(rules, r => r.clash_mode == nameof(ERuleMode.Global) && r.server == Global.SingboxRemoteDNSTag, proxy);
    }

    private static void AssertFamilyTwin(List<Rule4Sbox> rules, Func<Rule4Sbox, bool> isRoute, string v2Strategy)
    {
        var index = rules.FindIndex(r => isRoute(r));
        index.Should().BeGreaterThan(0);
        var refused = v2Strategy switch { "ForceIPv4" => 28, "ForceIPv6" => 1, _ => 0 };
        var before = rules[index - 1];
        var isTwin = before.action == "predefined" && before.rcode == "NOERROR" && before.server == null
                     && before.domain?.SequenceEqual(rules[index].domain ?? []) != false
                     && before.clash_mode == rules[index].clash_mode;
        if (refused == 0)
        {
            (isTwin && before.query_type is [1] or [28]).Should().BeFalse("prefer_* keeps no twin");
            return;
        }
        isTwin.Should().BeTrue();
        before.query_type.Should().Equal(refused);
    }

    [Fact]
    public void GenDns_CustomDnsTemplate_ShouldDropLegacyRuleStrategy()
    {
        // Встроенный шаблон «весь трафик» держит prefer_ipv4 у каждого правила: предупреждение на
        // 1.14, отказ на 1.16. Сами правила и глобальный strategy шаблона остаются.
        var cfg = Generate(rawDns: new DNSItem { Enabled = true, CoreType = ECoreType.sing_box });

        Flatten(cfg.dns!.rules).Should().NotContain(r => r.strategy != null);
        cfg.dns.rules.Should().Contain(r => r.server == "local" && r.domain_suffix != null && r.domain_suffix.Contains("ru"));
        cfg.dns.strategy.Should().Be("prefer_ipv4");
    }

    [Fact]
    public void GenerateClientConfigContent_MissingLocalRuleSet_ShouldDownloadThroughProxyViaHttpClient()
    {
        // Локального набора нет (в каталоге тестов bin/srss пуст) — набор удалённый. Качается через
        // proxy формой 1.14: download_detour там устарел и в 1.16 роняет ядро.
        var config = CoreConfigTestFactory.CreateConfig(ECoreType.sing_box);
        CoreConfigTestFactory.BindAppManagerConfig(config);
        var node = CoreConfigTestFactory.CreateSocksNode(ECoreType.sing_box);
        var context = CoreConfigTestFactory.CreateContext(config, node, ECoreType.sing_box) with
        {
            RoutingItem = new RoutingItem
            {
                Id = "r-geo",
                Remarks = "geo",
                RuleSet = JsonUtils.Serialize(new List<RulesItem>
                {
                    new() { Enabled = true, OutboundTag = Global.DirectTag, Domain = ["geosite:private"] },
                    new() { Enabled = true, OutboundTag = Global.DirectTag, Ip = ["geoip:private"] },
                }),
                DomainStrategy = Global.AsIs,
                DomainStrategy4Singbox = string.Empty,
            },
        };
        var result = new CoreConfigSingboxService(context).GenerateClientConfigContent();
        result.Success.Should().BeTrue($"ret msg: {result.Msg}");
        var cfg = JsonUtils.Deserialize<SingboxConfig>(result.Data!.ToString())!;

        var remote = cfg.route.rule_set!.Where(r => r.type == "remote").ToList();
        remote.Should().NotBeEmpty();
        remote.Should().OnlyContain(r => r.http_client != null && r.http_client.detour == Global.ProxyTag && r.download_detour == null);
    }

    [Fact]
    public void GenerateClientConfigContent_WithoutRoutingItem_ShouldStillGenerate()
    {
        var config = CoreConfigTestFactory.CreateConfig(ECoreType.sing_box);
        CoreConfigTestFactory.BindAppManagerConfig(config);
        var node = CoreConfigTestFactory.CreateSocksNode(ECoreType.sing_box);
        var context = CoreConfigTestFactory.CreateContext(config, node, ECoreType.sing_box) with { RoutingItem = null };

        var result = new CoreConfigSingboxService(context).GenerateClientConfigContent();

        result.Success.Should().BeTrue($"ret msg: {result.Msg}");
    }

    [Fact]
    public void GenDns_ShouldNotEmitIndependentCache()
    {
        // deprecated in sing-box 1.14, removed in 1.16 — where an unknown field aborts the core
        Generate().dns!.independent_cache.Should().BeNull();
    }
}
