using AwesomeAssertions;
using ServiceLib.Common;
using ServiceLib.Enums;
using ServiceLib.Handler;
using ServiceLib.Manager;
using ServiceLib.Models;
using ServiceLib.Services.CoreConfig;
using Xunit;

namespace ServiceLib.Tests.CoreConfig;

/// <summary>
/// Что уходит ядрам без участия человека: прямой DNS, bootstrap, резолвер хоста VPN-сервера,
/// встроенные шаблоны «своего DNS». Ни китайского резолвера, ни проверки ответов на geoip:cn.
/// Маршруты — встроенные наборы апстрима, как их получает человек. Что прямой DNS обходит правило
/// «Blacklist», которое шлёт IP Яндекса в proxy, проверяет V2rayDirectDnsRoutingTests.
/// </summary>
public class RussianDnsDefaultsTests
{
    private const string Yandex = "77.88.8.8";

    private static readonly string[] ChineseResolvers =
    [
        "119.29.29.29", "223.5.5.5", "223.6.6.6", "114.114.114.114", "180.76.76.76", "1.12.12.12",
        "120.53.53.53", "doh.pub", "dot.pub", "alidns", "dnspod", "360.cn", "onedns",
    ];

    private static Config DefaultConfig(bool tun)
    {
        var config = CoreConfigTestFactory.CreateConfig(ECoreType.Xray);
        config.SimpleDNSItem = ConfigHandler.InitBuiltinSimpleDNS();
        config.TunModeItem.EnableTun = tun;
        CoreConfigTestFactory.BindAppManagerConfig(config);
        return config;
    }

    private static V2rayConfig Xray(string preset, bool tun, DNSItem? rawDns = null, string host = "vpn.example.com")
    {
        var config = DefaultConfig(tun);
        var node = CoreConfigTestFactory.CreateVmessNode(ECoreType.Xray);
        node.Address = host;
        var context = CoreConfigTestFactory.CreateContext(config, node, ECoreType.Xray) with
        {
            IsTunEnabled = tun,
            RoutingItem = CoreConfigTestFactory.CreateBuiltinRouting(preset),
            RawDnsItem = rawDns,
            ProtectDomainList = [host],
        };
        var result = new CoreConfigV2rayService(context).GenerateClientConfigContent();
        result.Success.Should().BeTrue($"ret msg: {result.Msg}");
        return JsonUtils.Deserialize<V2rayConfig>(result.Data!.ToString())!;
    }

    private static SingboxConfig Singbox(string preset, bool tun, bool preService, Action<SimpleDNSItem>? tweak = null)
    {
        var config = DefaultConfig(tun);
        tweak?.Invoke(config.SimpleDNSItem);
        ProfileItem node;
        if (preService)
        {
            //  sing-box, который держит туннель для CUSTOM-узла (BuildPreSocksIfNeeded): SOCKS в Xray.
            node = CoreConfigTestFactory.CreateSocksNode(ECoreType.sing_box);
            node.Address = Global.Loopback;
            node.Port = AppManager.Instance.GetLocalPort(EInboundProtocol.socks);
        }
        else
        {
            node = CoreConfigTestFactory.CreateVmessNode(ECoreType.sing_box);
            node.Address = "vpn.example.com";
        }
        var context = CoreConfigTestFactory.CreateContext(config, node, ECoreType.sing_box) with
        {
            IsTunEnabled = tun,
            RoutingItem = CoreConfigTestFactory.CreateBuiltinRouting(preset),
            ProtectDomainList = ["vpn.example.com"],
        };
        var result = new CoreConfigSingboxService(context).GenerateClientConfigContent();
        result.Success.Should().BeTrue($"ret msg: {result.Msg}");
        return JsonUtils.Deserialize<SingboxConfig>(result.Data!.ToString())!;
    }

    private static void ShouldNotBeChinese(string? address)
    {
        foreach (var bad in ChineseResolvers)
        {
            (address ?? string.Empty).Should().NotContain(bad);
        }
    }

    [Fact]
    public void Presets_ShouldPutYandexFirst()
    {
        Global.DomainDirectDNSAddress.First().Should().Be(Yandex);
        Global.DomainPureIPDNSAddress.First().Should().Be(Yandex);
        Global.DomainDirectDNSAddress.Concat(Global.DomainPureIPDNSAddress).ToList().ForEach(ShouldNotBeChinese);
    }

    [Theory]
    [InlineData("white", false)]
    [InlineData("white", true)]
    [InlineData("black", false)]
    [InlineData("black", true)]
    [InlineData("global", true)]
    public void Xray_Defaults_ShouldResolveDirectDomainsAndServerHostWithYandex(string preset, bool tun)
    {
        var servers = CoreConfigTestFactory.XrayDnsServers(Xray(preset, tun));

        servers.ForEach(s => ShouldNotBeChinese(s.address));
        servers.Should().NotContain(s => s.expectedIPs != null, "ожидаемые IP по умолчанию пусты");
        var serverHost = servers.Single(s => s.domains != null && s.domains.Contains("vpn.example.com"));
        serverHost.address.Should().Be(Yandex);
        serverHost.tag.Should().StartWith(Global.DirectDnsTag);
    }

    [Theory]
    [InlineData("white")]
    [InlineData("black")]
    public void Xray_CustomDnsTemplate_ServerHostShouldBeResolvedDirectlyByYandexFirst(string preset)
    {
        // Свой DNS без текста — встроенный шаблон (там сервер Яндекса для domain:ru). Хост VPN-сервера
        // в зоне .ru совпадает с обоими; первым обязан отвечать защитный сервер, и напрямую.
        var cfg = Xray(preset, true, new DNSItem { Enabled = true, CoreType = ECoreType.Xray }, "vpn.example.ru");

        var servers = CoreConfigTestFactory.XrayDnsServers(cfg);
        servers.ForEach(s => ShouldNotBeChinese(s.address));
        servers.Should().NotContain(s => s.expectedIPs != null);
        var protect = servers.FindIndex(s => s.domains != null && s.domains.Contains("vpn.example.ru"));
        var ru = servers.FindIndex(s => s.domains != null && s.domains.Contains("domain:ru"));
        protect.Should().Be(0);
        ru.Should().BeGreaterThan(protect);
        servers[protect].address.Should().Be(Yandex);
        servers[protect].tag.Should().Be($"{Global.DirectDnsTag}-protect");

        var rules = cfg.routing.rules;
        var direct = rules.FindIndex(r => r.outboundTag == Global.DirectTag && r.inboundTag?.Contains(servers[protect].tag!) == true);
        direct.Should().BeGreaterThan(-1);
        var toProxy = rules.FindIndex(r => r.outboundTag == Global.ProxyTag && r.ip?.Contains(Yandex) == true);
        if (toProxy >= 0)
        {
            direct.Should().BeLessThan(toProxy);
        }
    }

    [Theory]
    [InlineData("white", false, false)]
    [InlineData("black", true, false)]
    [InlineData("white", true, true)]
    [InlineData("black", true, true)]
    [InlineData("global", true, true)]
    public void Singbox_Defaults_ShouldUseYandexForDirectBootstrapAndServerHost(string preset, bool tun, bool preService)
    {
        var cfg = Singbox(preset, tun, preService);
        var servers = cfg.dns!.servers;

        servers.ForEach(s => ShouldNotBeChinese(s.server));
        servers.Single(s => s.tag == Global.SingboxDirectDNSTag).server.Should().Be(Yandex);
        servers.Single(s => s.tag == Global.SingboxLocalDNSTag).server.Should().Be(Yandex);
        // Адреса исходящих (в том числе хост VPN-сервера) sing-box разрешает прямым DNS.
        cfg.route.default_domain_resolver!.server.Should().Be(Global.SingboxDirectDNSTag);
        cfg.dns.rules.Should().Contain(r => r.server == Global.SingboxDirectDNSTag
                                            && r.domain != null && r.domain.Contains("vpn.example.com"));
        // Никакой проверки ответов по geoip: ни geoip-правил DNS, ни ip_cidr.
        cfg.dns.rules.Should().NotContain(r => r.rule_set != null && r.rule_set.Any(t => t.StartsWith("geoip-")));
        cfg.dns.rules.Should().NotContain(r => r.ip_cidr != null);
    }

    [Fact]
    public void Singbox_YandexDoh_ShouldTakeItsNameFromHosts()
    {
        // DoH Яндекса — из списка пресетов. Имя common.dot.dns.yandex.net отвечает hosts, без
        // запроса к bootstrap: у dns.yandex.net из прежнего hosts записей A/AAAA нет вовсе.
        Global.PredefinedHosts["common.dot.dns.yandex.net"].Should().Contain(new[] { Yandex, "77.88.8.1" });

        var cfg = Singbox("black", true, false, d => d.DirectDNS = "https://common.dot.dns.yandex.net/dns-query");

        var direct = cfg.dns!.servers.Single(s => s.tag == Global.SingboxDirectDNSTag);
        direct.type.Should().Be("https");
        direct.server.Should().Be("common.dot.dns.yandex.net");
        direct.domain_resolver.Should().Be(Global.SingboxHostsDNSTag);
    }

    [Theory]
    [InlineData("dns_v2ray_normal")]
    [InlineData("dns_singbox_normal")]
    [InlineData("tun_singbox_dns")]
    [InlineData("clash_mixin_yaml")]
    public void EmbeddedDnsTemplates_ShouldCarryYandexAndNoChineseResolverOrGeoipCn(string name)
    {
        var text = EmbedUtils.GetEmbedText(Global.NamespaceSample + name);

        text.Should().Contain(Yandex);
        ShouldNotBeChinese(text);
        text.Should().NotContainAny("geoip:cn", "geoip-cn", "geosite:cn", "geosite-cn", "expectIPs");
    }
}
