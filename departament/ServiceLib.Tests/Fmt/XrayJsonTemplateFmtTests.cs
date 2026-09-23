using AwesomeAssertions;
using ServiceLib.Common;
using ServiceLib.Enums;
using ServiceLib.Handler.Builder;
using ServiceLib.Handler.Fmt;
using ServiceLib.Models;
using ServiceLib.Models.CoreConfigs;
using ServiceLib.Services.CoreConfig;
using ServiceLib.Tests.CoreConfig;
using Xunit;

namespace ServiceLib.Tests.Fmt;

/// <summary>
/// Серверы узла CUSTOM живут внутри его файла, а не в Address. Если их хосты не попали в защиту,
/// sing-box, держащий туннель, разрешает адрес VPN-сервера через этот же сервер — и трафик встаёт,
/// как только истечёт кеш DNS системы.
/// </summary>
public class XrayJsonTemplateFmtTests
{
    [Fact]
    public void GetServerHostsFromJson_XrayVnextAndServers_ShouldReturnEveryDomain()
    {
        const string raw =
            """
            {
              "outbounds": [
                { "protocol": "vless", "tag": "proxy",
                  "settings": { "vnext": [
                    { "address": "nl.example.com", "port": 443, "users": [ { "id": "u" } ] },
                    { "address": "de.example.com", "port": 443, "users": [ { "id": "u" } ] } ] } },
                { "protocol": "trojan", "tag": "backup",
                  "settings": { "servers": [ { "address": "tr.example.net", "port": 443, "password": "p" } ] } },
                { "protocol": "freedom", "tag": "direct" },
                { "protocol": "blackhole", "tag": "block" }
              ]
            }
            """;

        XrayJsonTemplateFmt.GetServerHostsFromJson(raw).Should()
            .Equal("nl.example.com", "de.example.com", "tr.example.net");
    }

    [Fact]
    public void GetServerHostsFromJson_FlatSettingsAddress_ShouldReturnDomain()
    {
        // Новая форма VLESS/VMess в Xray: сервер прямо в settings, без vnext.
        const string raw =
            """
            { "outbounds": [ { "protocol": "vless",
                "settings": { "address": "flat.example.com", "port": 443, "id": "u", "encryption": "none" } } ] }
            """;

        XrayJsonTemplateFmt.GetServerHostsFromJson(raw).Should().Equal("flat.example.com");
    }

    [Fact]
    public void GetServerHostsFromJson_XhttpDownloadSettings_ShouldReturnUploadAndDownloadHosts()
    {
        const string raw =
            """
            { "outbounds": [
                { "protocol": "vless",
                  "settings": { "vnext": [ { "address": "up.example.com", "port": 443 } ] },
                  "streamSettings": { "network": "xhttp",
                    "xhttpSettings": { "path": "/x",
                      "downloadSettings": { "address": "down.example.com", "port": 443, "network": "xhttp" } } } },
                { "protocol": "vless",
                  "settings": { "vnext": [ { "address": "up2.example.com", "port": 443 } ] },
                  "streamSettings": { "network": "xhttp",
                    "xhttpSettings": { "extra": { "downloadSettings": { "address": "down2.example.com" } } } } }
            ] }
            """;

        XrayJsonTemplateFmt.GetServerHostsFromJson(raw).Should()
            .Equal("up.example.com", "down.example.com", "up2.example.com", "down2.example.com");
    }

    [Fact]
    public void GetServerHostsFromJson_SingboxOutboundServer_ShouldReturnDomain()
    {
        const string raw =
            """
            { "outbounds": [
                { "type": "vless", "tag": "proxy", "server": "sb.example.com", "server_port": 443 },
                { "type": "direct", "tag": "direct" } ] }
            """;

        XrayJsonTemplateFmt.GetServerHostsFromJson(raw).Should().Equal("sb.example.com");
    }

    [Fact]
    public void GetServerHostsFromJson_IpOnlyHosts_ShouldBeIgnored()
    {
        // IP в DNS не ходит — защищать нечего. Массив у WireGuard — адреса его интерфейса.
        const string raw =
            """
            { "outbounds": [
                { "protocol": "vless", "settings": { "vnext": [ { "address": "203.0.113.7", "port": 443 } ] } },
                { "protocol": "trojan", "settings": { "servers": [ { "address": "2001:db8::1", "port": 443 } ] } },
                { "protocol": "vless", "settings": { "address": "[2001:db8::2]", "port": 443 } },
                { "type": "trojan", "server": "198.51.100.1", "server_port": 443 },
                { "protocol": "wireguard", "settings": { "address": [ "10.0.0.2/32", "fd00::2/128" ] } }
            ] }
            """;

        XrayJsonTemplateFmt.GetServerHostsFromJson(raw).Should().BeEmpty();
    }

    [Fact]
    public void GetServerHostsFromJson_KeysCaseAndDuplicates_ShouldNormalizeLikeTheCore()
    {
        // Ядро читает ключи без учёта регистра; комментарии и висячая запятая файл не ломают.
        const string raw =
            """
            {
              // шаблон, поправленный руками
              "Outbounds": [
                { "Settings": { "VNext": [ { "Address": " NL.Example.COM " } ] } },
                { "settings": { "vnext": [ { "address": "nl.example.com" } ] } },
              ]
            }
            """;

        XrayJsonTemplateFmt.GetServerHostsFromJson(raw).Should().Equal("nl.example.com");
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("not json at all")]
    [InlineData("{ \"outbounds\": [ { \"settings\": { \"vnext\": [ { \"address\": ")]
    [InlineData("[ { \"outbounds\": [] } ]")]
    [InlineData("{ \"outbounds\": { \"server\": \"a.example.com\" } }")]
    [InlineData("{ \"outbounds\": [ 1, \"x\", null, { \"settings\": { \"vnext\": { \"address\": \"a.example.com\" }, \"address\": 42 }, \"server\": [] } ] }")]
    public void GetServerHostsFromJson_MalformedInput_ShouldReturnEmptyWithoutThrowing(string? raw)
    {
        var act = () => XrayJsonTemplateFmt.GetServerHostsFromJson(raw);

        act.Should().NotThrow().Which.Should().BeEmpty();
    }

    [Fact]
    public async Task ResolveNodeAsync_CustomNode_ShouldProtectServerHostsForTunPreService()
    {
        var config = CoreConfigTestFactory.CreateConfig();
        config.SimpleDNSItem = ConfigHandler.InitBuiltinSimpleDNS();
        config.TunModeItem.EnableTun = true;
        CoreConfigTestFactory.BindAppManagerConfig(config);

        var file = WriteTempConfig(
            """
            { "outbounds": [
                { "protocol": "vless", "tag": "proxy",
                  "settings": { "vnext": [ { "address": "vpn.example.com", "port": 443, "users": [ { "id": "u" } ] } ] } },
                { "protocol": "freedom", "tag": "direct" } ] }
            """);
        try
        {
            var node = CreateCustomNode(file);
            var mainContext = CoreConfigTestFactory.CreateContext(config, node, ECoreType.Xray);

            var (_, result) = await CoreConfigContextBuilder.ResolveNodeAsync(mainContext, node);

            result.Success.Should().BeTrue();
            // Address — имя файла, не домен: в защите должен оказаться хост ИЗ файла.
            mainContext.ProtectDomainList.Should().BeEquivalentTo(new[] { "vpn.example.com" });

            // Контекст sing-box, держащего туннель, наследует защиту главного узла — так его
            // собирает BuildPreSocksIfNeeded для CUSTOM в режиме «весь трафик».
            var preNode = CoreConfigTestFactory.CreateSocksNode(ECoreType.sing_box);
            preNode.Address = Global.Loopback;
            preNode.Port = AppManager.Instance.GetLocalPort(EInboundProtocol.socks);
            var preContext = CoreConfigTestFactory.CreateContext(config, preNode, ECoreType.sing_box) with
            {
                IsTunEnabled = true,
                ProtectDomainList = [.. mainContext.ProtectDomainList],
            };

            var ret = new CoreConfigSingboxService(preContext).GenerateClientConfigContent();
            ret.Success.Should().BeTrue($"ret msg: {ret.Msg}");
            var pre = JsonUtils.Deserialize<SingboxConfig>(ret.Data!.ToString())!;

            // Хост VPN-сервера разрешается прямым DNS, мимо туннеля и мимо самого сервера.
            pre.dns!.rules.Should().Contain(r =>
                r.server == Global.SingboxDirectDNSTag
                && r.domain != null
                && r.domain.Contains("vpn.example.com"));
        }
        finally
        {
            File.Delete(file);
        }
    }

    [Fact]
    public async Task ResolveNodeAsync_CustomNodeWithMissingFile_ShouldStillResolve()
    {
        var config = CoreConfigTestFactory.CreateConfig();
        CoreConfigTestFactory.BindAppManagerConfig(config);

        var node = CreateCustomNode($"missing-{Guid.NewGuid():N}.json");
        var context = CoreConfigTestFactory.CreateContext(config, node, ECoreType.Xray);

        var (_, result) = await CoreConfigContextBuilder.ResolveNodeAsync(context, node);

        // Нечего защитить — не повод отказывать в подключении: об отсутствии файла скажет сборка конфига.
        result.Success.Should().BeTrue();
        context.ProtectDomainList.Should().BeEmpty();
    }

    private static ProfileItem CreateCustomNode(string address) => new()
    {
        IndexId = $"custom-{Guid.NewGuid():N}",
        ConfigType = EConfigType.Custom,
        CoreType = ECoreType.Xray,
        Remarks = "custom",
        Address = address,
        Subid = string.Empty,
    };

    private static string WriteTempConfig(string content)
    {
        var path = Path.Combine(Path.GetTempPath(), $"custom-{Guid.NewGuid():N}.json");
        File.WriteAllText(path, content);
        return path;
    }
}
