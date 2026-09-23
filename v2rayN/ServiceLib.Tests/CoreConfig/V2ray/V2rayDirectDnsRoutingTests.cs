using AwesomeAssertions;
using ServiceLib.Common;
using ServiceLib.Enums;
using ServiceLib.Models;
using ServiceLib.Services.CoreConfig;
using Xunit;

namespace ServiceLib.Tests.CoreConfig.V2ray;

/// <summary>
/// Запрос прямого DNS Xray пропускает через правила маршрута, как трафик. Встроенный «Blacklist»
/// апстрима шлёт в proxy IP «публичных DNS за рубежом», и Яндекс в этом списке. Прямой DNS, bootstrap
/// и резолвер хоста VPN-сервера обязаны уйти напрямую при любом наборе маршрутов: иначе адрес
/// VPN-сервера спрашивают через туннель, которому этот адрес и нужен.
/// </summary>
public class V2rayDirectDnsRoutingTests
{
    private const string Yandex = "77.88.8.8";

    private static V2rayConfig Generate(string preset, bool tun, string host = "vpn.example.com", DNSItem? rawDns = null)
    {
        var config = CoreConfigTestFactory.CreateConfig(ECoreType.Xray);
        config.SimpleDNSItem.DirectDNS = Yandex;
        config.SimpleDNSItem.BootstrapDNS = Yandex;
        config.SimpleDNSItem.RemoteDNS = "https://cloudflare-dns.com/dns-query";
        config.SimpleDNSItem.AddCommonHosts = true;
        config.TunModeItem.EnableTun = tun;
        CoreConfigTestFactory.BindAppManagerConfig(config);

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

    private static int PresetSendsYandexToProxy(V2rayConfig cfg) =>
        cfg.routing.rules.FindIndex(r => r.outboundTag == Global.ProxyTag && r.ip?.Contains(Yandex) == true);

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void Blacklist_DirectDnsShouldGoDirectBeforeThePresetSendsItsIpToProxy(bool tun)
    {
        var cfg = Generate("black", tun);
        var rules = cfg.routing.rules;
        var servers = CoreConfigTestFactory.XrayDnsServers(cfg);

        var toProxy = PresetSendsYandexToProxy(cfg);
        toProxy.Should().BeGreaterThan(-1, "у «Blacklist» апстрима Яндекс в списке «публичных DNS за рубежом»");
        var direct = rules.FindIndex(r => r.outboundTag == Global.DirectTag
                                          && r.inboundTag?.Any(t => t.StartsWith(Global.DirectDnsTag, StringComparison.Ordinal)) == true);
        direct.Should().BeGreaterThan(-1);
        direct.Should().BeLessThan(toProxy);

        // Одно правило покрывает все прямые серверы: хост VPN-сервера, прямые домены и bootstrap.
        var directTags = servers.Where(s => s.tag?.StartsWith(Global.DirectDnsTag, StringComparison.Ordinal) == true)
            .Select(s => s.tag!).ToList();
        rules[direct].inboundTag.Should().BeEquivalentTo(directTags);
        servers.Should().Contain(s => s.domains != null && s.domains.Contains("vpn.example.com") && directTags.Contains(s.tag!));
        servers.Should().Contain(s => s.domains != null && s.domains.Contains("full:cloudflare-dns.com")
                                      && s.address == Yandex && directTags.Contains(s.tag!));
    }

    [Fact]
    public void CustomDns_ServerHostShouldBeResolvedDirectlyAndFirst()
    {
        // Свой DNS со своим сервером для domain:ru: хост VPN-сервера в зоне .ru совпадает с обоими.
        // Первым по списку и напрямую обязан отвечать защитный сервер.
        const string customDns = """{ "servers": [ { "address": "77.88.8.8", "domains": ["domain:ru"] }, "1.1.1.1" ] }""";
        var cfg = Generate("black", true, "vpn.example.ru", new DNSItem
        {
            Enabled = true,
            CoreType = ECoreType.Xray,
            NormalDNS = customDns,
            TunDNS = customDns,
            DomainDNSAddress = Yandex,
        });
        var servers = CoreConfigTestFactory.XrayDnsServers(cfg);

        servers[0].domains.Should().Equal("vpn.example.ru");
        servers[0].address.Should().Be(Yandex);
        servers[0].tag.Should().Be($"{Global.DirectDnsTag}-protect");
        var rules = cfg.routing.rules;
        var direct = rules.FindIndex(r => r.outboundTag == Global.DirectTag && r.inboundTag?.Contains(servers[0].tag!) == true);
        direct.Should().BeGreaterThan(-1);
        direct.Should().BeLessThan(PresetSendsYandexToProxy(cfg));
    }
}
