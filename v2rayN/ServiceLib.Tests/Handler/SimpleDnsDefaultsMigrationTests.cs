using AwesomeAssertions;
using ServiceLib.Common;
using ServiceLib.Handler;
using ServiceLib.Models;
using Xunit;

namespace ServiceLib.Tests.Handler;

/// <summary>
/// Прямой DNS и bootstrap-DNS у всех, кто ставил приложение до перехода на Яндекс, лежат в конфиге
/// китайскими умолчаниями апстрима: departament для ПК не показывает эти поля ни на одном экране.
/// ConfigHandler.MigrateSimpleDnsDefaults переводит их один раз и только там, где значение точно
/// равно умолчанию апстрима.
///
/// В одной коллекции с тестами подписок: их AddBatchServers сохраняет guiNConfig.json в тот же каталог,
/// который читает здесь LoadConfig, и параллельно запись подменила бы файл посреди теста.
/// </summary>
[Collection("SqliteDb")]
public class SimpleDnsDefaultsMigrationTests
{
    private const string Yandex = "77.88.8.8";

    /// <summary>Блок таким, каким его записал апстрим 7.21+ (с него начат departament).</summary>
    private static SimpleDNSItem Upstream() => new()
    {
        UseSystemHosts = false,
        AddCommonHosts = true,
        FakeIP = false,
        GlobalFakeIp = true,
        BlockBindingQuery = true,
        DirectDNS = "119.29.29.29",
        RemoteDNS = "https://cloudflare-dns.com/dns-query",
        BootstrapDNS = "119.29.29.29",
        ServeStale = false,
        ParallelQuery = false,
    };

    [Fact]
    public void InitBuiltinSimpleDNS_ShouldUseYandexAndEmptyExpectedIPs()
    {
        var item = ConfigHandler.InitBuiltinSimpleDNS();

        item.DirectDNS.Should().Be(Yandex);
        item.BootstrapDNS.Should().Be(Yandex);
        item.DirectExpectedIPs.Should().BeNullOrEmpty();
        // Свежая установка уже на новых умолчаниях: миграции делать нечего.
        ConfigHandler.MigrateSimpleDnsDefaults(item).Should().BeFalse();
        item.DirectDNS.Should().Be(Yandex);
    }

    [Fact]
    public void Migrate_UpstreamDefaults_ShouldMoveDirectAndBootstrapToYandex()
    {
        var item = Upstream();

        ConfigHandler.MigrateSimpleDnsDefaults(item).Should().BeTrue();

        item.DirectDNS.Should().Be(Yandex);
        item.BootstrapDNS.Should().Be(Yandex);
        item.DirectExpectedIPs.Should().BeNullOrEmpty();
        item.DefaultsVersion.Should().Be(1);
        // Удалённый DNS не китайский и не трогается.
        item.RemoteDNS.Should().Be("https://cloudflare-dns.com/dns-query");
    }

    [Fact]
    public void Migrate_OlderUpstreamDefaults_ShouldAlsoMove()
    {
        // 7.14–7.20: DoH AliDNS прямым DNS, 223.5.5.5 bootstrap-ом.
        var item = Upstream();
        item.DirectDNS = "https://dns.alidns.com/dns-query";
        item.BootstrapDNS = "223.5.5.5";

        ConfigHandler.MigrateSimpleDnsDefaults(item).Should().BeTrue();

        item.DirectDNS.Should().Be(Yandex);
        item.BootstrapDNS.Should().Be(Yandex);
    }

    [Theory]
    [InlineData("1.1.1.1", "8.8.8.8")]
    [InlineData("223.5.5.5", "1.1.1.1")]
    [InlineData("119.29.29.29,223.5.5.5,https://doh.pub/dns-query", "119.29.29.29 ")]
    [InlineData(" 119.29.29.29", "localhost")]
    [InlineData("https://doh.pub/dns-query", "")]
    public void Migrate_CustomisedValues_ShouldBeLeftAlone(string direct, string bootstrap)
    {
        // Всё, что не равно умолчанию апстрима ТОЧНО, — выбор человека: пресет из списка, набранное
        // руками, даже 223.5.5.5 прямым DNS (у апстрима прямой DNS им по умолчанию не был).
        var item = Upstream();
        item.DirectDNS = direct;
        item.BootstrapDNS = bootstrap;

        ConfigHandler.MigrateSimpleDnsDefaults(item).Should().BeTrue();

        item.DirectDNS.Should().Be(direct);
        item.BootstrapDNS.Should().Be(bootstrap);
        item.DefaultsVersion.Should().Be(1);
    }

    [Fact]
    public void Migrate_EachFieldOnItsOwn()
    {
        var item = Upstream();
        item.DirectDNS = "9.9.9.9";

        ConfigHandler.MigrateSimpleDnsDefaults(item);

        item.DirectDNS.Should().Be("9.9.9.9");
        item.BootstrapDNS.Should().Be(Yandex);
    }

    [Theory]
    [InlineData("geoip:cn")]
    [InlineData("geoip:ru")]
    [InlineData("192.168.0.0/16")]
    public void Migrate_WithCustomExpectedIPs_ShouldKeepTheirResolver(string expectedIps)
    {
        // Ожидаемые IP проверяют ответы прямого DNS; пару подбирал человек. Подмена резолвера под
        // geoip:cn дала бы Яндекс, чьи ответы вне Китая Xray молча отбрасывает.
        var item = Upstream();
        item.DirectExpectedIPs = expectedIps;

        ConfigHandler.MigrateSimpleDnsDefaults(item);

        item.DirectDNS.Should().Be("119.29.29.29");
        item.DirectExpectedIPs.Should().Be(expectedIps);
        // Bootstrap разрешает только имена DoH-серверов и от ожидаемых IP не зависит.
        item.BootstrapDNS.Should().Be(Yandex);
    }

    [Fact]
    public void Migrate_RunsOnce_ADeliberateReturnToTheOldValueSurvives()
    {
        var item = Upstream();
        ConfigHandler.MigrateSimpleDnsDefaults(item).Should().BeTrue();

        // Человек потом сам вернул DNSPod: следующий запуск это не отменяет.
        item.DirectDNS = "119.29.29.29";
        item.BootstrapDNS = "119.29.29.29";

        ConfigHandler.MigrateSimpleDnsDefaults(item).Should().BeFalse();
        ConfigHandler.MigrateSimpleDnsDefaults(item).Should().BeFalse();

        item.DirectDNS.Should().Be("119.29.29.29");
        item.BootstrapDNS.Should().Be("119.29.29.29");
    }

    [Fact]
    public void Migrate_ConfigWrittenByAnOlderBuild_RoundTripsWithTheMarker()
    {
        // Так блок лежит в guiNConfig.json у установки до этой версии: поля DefaultsVersion нет.
        const string json = """
            {
              "UseSystemHosts": false,
              "AddCommonHosts": true,
              "FakeIP": false,
              "GlobalFakeIp": true,
              "BlockBindingQuery": true,
              "DirectDNS": "119.29.29.29",
              "RemoteDNS": "https://dns.google/dns-query",
              "BootstrapDNS": "119.29.29.29",
              "Strategy4Freedom": null,
              "Strategy4Proxy": null,
              "ServeStale": false,
              "ParallelQuery": false,
              "Hosts": null,
              "DirectExpectedIPs": null
            }
            """;
        var item = JsonUtils.Deserialize<SimpleDNSItem>(json)!;
        item.DefaultsVersion.Should().BeNull();

        ConfigHandler.MigrateSimpleDnsDefaults(item).Should().BeTrue();
        var saved = JsonUtils.Deserialize<SimpleDNSItem>(JsonUtils.Serialize(item))!;

        saved.DirectDNS.Should().Be(Yandex);
        saved.BootstrapDNS.Should().Be(Yandex);
        saved.RemoteDNS.Should().Be("https://dns.google/dns-query");
        saved.DefaultsVersion.Should().Be(1);
        ConfigHandler.MigrateSimpleDnsDefaults(saved).Should().BeFalse();
    }

    [Theory]
    // Блок живого конфига departament для ПК, как его записала сборка до этой версии.
    [InlineData("""{ "DirectDNS": "119.29.29.29", "RemoteDNS": "https://cloudflare-dns.com/dns-query", "BootstrapDNS": "119.29.29.29", "DirectExpectedIPs": null }""")]
    // Конфиг апстрима 7.14–7.16: bootstrap-поля ещё нет, его ставит ??= в LoadConfig.
    [InlineData("""{ "DirectDNS": "https://dns.alidns.com/dns-query", "RemoteDNS": "https://cloudflare-dns.com/dns-query" }""")]
    public void LoadConfig_ExistingInstall_ComesUpOnYandex(string simpleDns)
    {
        // Сквозь настоящий LoadConfig: файл настроек в каталоге приложения (для тестов — bin),
        // прежнее содержимое возвращается на место.
        var path = Utils.GetConfigPath(Global.ConfigFileName);
        var previous = File.Exists(path) ? File.ReadAllBytes(path) : null;
        try
        {
            File.WriteAllText(path, $$"""{ "IndexId": "", "SimpleDNSItem": {{simpleDns}} }""");

            var config = ConfigHandler.LoadConfig()!;

            config.SimpleDNSItem.DirectDNS.Should().Be(Yandex);
            config.SimpleDNSItem.BootstrapDNS.Should().Be(Yandex);
            config.SimpleDNSItem.RemoteDNS.Should().Be("https://cloudflare-dns.com/dns-query");
            config.SimpleDNSItem.DefaultsVersion.Should().Be(1);
        }
        finally
        {
            if (previous is null)
            {
                File.Delete(path);
            }
            else
            {
                File.WriteAllBytes(path, previous);
            }
        }
    }
}
