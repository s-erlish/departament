using AwesomeAssertions;
using ServiceLib.Services.AppUpdate;
using Xunit;

namespace ServiceLib.Tests.AppUpdate;

/// <summary>
/// Возврат подключения после перезапуска ради обновления: только следом за установкой, только свежая
/// метка, только в том же режиме, и никогда поверх решения пользователя.
/// </summary>
public class AppUpdateReconnectTests
{
    private static readonly DateTime Now = new(2026, 9, 23, 12, 0, 0, DateTimeKind.Utc);

    private static AppVersion V(string text)
    {
        AppVersion.TryParse(text, out var version).Should().BeTrue();
        return version!;
    }

    private static AppUpdatePendingInstall Pending(TimeSpan? age = null, string? server = "5477952172323699705", bool tun = false,
        string tag = "v1.0.1", string from = "1.0.0", bool connected = true) => new()
    {
        Tag = tag,
        From = from,
        HandedOffUtc = Now - (age ?? TimeSpan.FromSeconds(4)),
        Reconnect = connected ? new AppUpdateReconnectMarker { ServerId = server, Tun = tun } : null,
    };

    private static AppUpdateReconnectVerdict Screen(AppUpdatePendingInstall? pending, string running = "1.0.1",
        bool userActed = false, bool tunNow = false) =>
        AppUpdateReconnect.Screen(pending, Now, V(running), userActed, tunNow);

    [Fact]
    public void ReconnectsTheFirstStartOfTheNewVersion()
    {
        Screen(Pending()).Should().Be(AppUpdateReconnectVerdict.Connect);
    }

    [Fact]
    public void ReconnectsAlsoWhenTheInstallerRolledBackToThePreviousVersion()
    {
        // Установщик откатил замену и поднял прежнюю версию: приложение всё равно закрыл перезапуск ради обновления.
        Screen(Pending(), running: "1.0.0").Should().Be(AppUpdateReconnectVerdict.Connect);
    }

    [Fact]
    public void DoesNothingWhenThereWasNoConnection()
    {
        Screen(null).Should().Be(AppUpdateReconnectVerdict.NotConnected);
        Screen(Pending(connected: false)).Should().Be(AppUpdateReconnectVerdict.NotConnected);
    }

    [Theory]
    [InlineData(301)]
    [InlineData(3600)]
    [InlineData(86400 * 3)]
    public void AnOldMarkerIsNotThisRestart(int seconds)
    {
        Screen(Pending(TimeSpan.FromSeconds(seconds))).Should().Be(AppUpdateReconnectVerdict.Stale);
    }

    [Theory]
    [InlineData(0)]
    [InlineData(35)]
    [InlineData(300)]
    public void AFreshMarkerCounts(int seconds)
    {
        Screen(Pending(TimeSpan.FromSeconds(seconds))).Should().Be(AppUpdateReconnectVerdict.Connect);
    }

    [Fact]
    public void ToleratesASmallClockStepButNotAMarkerFromTheFuture()
    {
        Screen(Pending(TimeSpan.FromSeconds(-30))).Should().Be(AppUpdateReconnectVerdict.Connect);
        Screen(Pending(TimeSpan.FromMinutes(-2))).Should().Be(AppUpdateReconnectVerdict.Stale);
    }

    [Fact]
    public void AMarkerWithoutATimeIsStale()
    {
        var pending = Pending();
        pending.HandedOffUtc = null;
        Screen(pending).Should().Be(AppUpdateReconnectVerdict.Stale);
    }

    [Fact]
    public void ALocalTimestampIsReadAsLocal()
    {
        var pending = Pending();
        pending.HandedOffUtc = (Now - TimeSpan.FromSeconds(10)).ToLocalTime();
        Screen(pending).Should().Be(AppUpdateReconnectVerdict.Connect);
    }

    [Theory]
    [InlineData("1.0.2")]
    [InlineData("1.0.1-rc.1")]
    [InlineData("0.9.0")]
    public void AnyOtherVersionIsSomeoneElsesStart(string running)
    {
        Screen(Pending(), running: running).Should().Be(AppUpdateReconnectVerdict.OtherVersion);
    }

    [Fact]
    public void PreReleaseTagsMatchTheirOwnVersion()
    {
        Screen(Pending(tag: "v1.1.0-rc.2", from: "1.0.1"), running: "1.1.0-rc.2").Should().Be(AppUpdateReconnectVerdict.Connect);
        Screen(Pending(tag: "v1.1.0-rc.2", from: "1.0.1"), running: "1.1.0").Should().Be(AppUpdateReconnectVerdict.OtherVersion);
    }

    [Fact]
    public void TheUserDecidesFirst()
    {
        // Пользователь сам подключился, отключился или выбрал сервер — подключение за него не возвращается.
        Screen(Pending(), userActed: true).Should().Be(AppUpdateReconnectVerdict.UserActed);
    }

    [Theory]
    [InlineData(false, false, AppUpdateReconnectVerdict.Connect)]
    [InlineData(true, true, AppUpdateReconnectVerdict.Connect)]
    [InlineData(true, false, AppUpdateReconnectVerdict.ModeUnavailable)]
    [InlineData(false, true, AppUpdateReconnectVerdict.ModeUnavailable)]
    public void OnlyInTheSameMode(bool tunBefore, bool tunNow, AppUpdateReconnectVerdict expected)
    {
        Screen(Pending(tun: tunBefore), tunNow: tunNow).Should().Be(expected);
    }

    [Fact]
    public void PrefersThePreviousServer()
    {
        AppUpdateReconnect.ChooseServer("a", previousServerExists: true, defaultServerId: "b").Should().Be("a");
    }

    [Fact]
    public void FallsBackToTheDefaultServerWhenThePreviousIsGone()
    {
        AppUpdateReconnect.ChooseServer("a", previousServerExists: false, defaultServerId: "b").Should().Be("b");
        AppUpdateReconnect.ChooseServer(null, previousServerExists: false, defaultServerId: "b").Should().Be("b");
        AppUpdateReconnect.ChooseServer("", previousServerExists: true, defaultServerId: "b").Should().Be("b");
    }

    [Fact]
    public void DoesNotConnectWithoutAnyServer()
    {
        AppUpdateReconnect.ChooseServer("a", previousServerExists: false, defaultServerId: null).Should().BeNull();
        AppUpdateReconnect.ChooseServer("a", previousServerExists: false, defaultServerId: "").Should().BeNull();
    }

    [Fact]
    public void TheMarkerSurvivesTheRoundTrip()
    {
        var written = Pending(tun: true);
        var read = AppUpdatePendingInstall.TryParse(written.ToJson());

        read.Should().NotBeNull();
        read!.Tag.Should().Be("v1.0.1");
        read.From.Should().Be("1.0.0");
        read.HandedOffUtc.Should().Be(written.HandedOffUtc);
        read.Reconnect!.ServerId.Should().Be("5477952172323699705");
        read.Reconnect.Tun.Should().BeTrue();
        Screen(read, tunNow: true).Should().Be(AppUpdateReconnectVerdict.Connect);
    }

    [Fact]
    public void AMarkerFromTheFirstUpdaterNeverReconnects()
    {
        // Так пишет метку 1.0.0: только тег. Итог установки из неё читается, подключения в ней нет.
        var read = AppUpdatePendingInstall.TryParse("""{"Tag":"v1.0.1","PreRelease":false}""");

        read!.Tag.Should().Be("v1.0.1");
        read.Reconnect.Should().BeNull();
        Screen(read).Should().Be(AppUpdateReconnectVerdict.NotConnected);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData("not json")]
    [InlineData("""{"Tag":""")]
    [InlineData("""{"Tag":"v1.0.1","HandedOffUtc":"yesterday"}""")]
    public void ADamagedMarkerIsIgnored(string? json)
    {
        AppUpdatePendingInstall.TryParse(json).Should().BeNull();
    }
}
