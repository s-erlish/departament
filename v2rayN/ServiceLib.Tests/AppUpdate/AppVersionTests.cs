using AwesomeAssertions;
using ServiceLib.Services.AppUpdate;
using Xunit;

namespace ServiceLib.Tests.AppUpdate;

/// <summary>
/// Версия выпуска по SemVer: ею решается, предлагать ли обновление. Ошибка здесь — это либо чужая
/// версия поверх своей, либо вечное «обновлений нет».
/// </summary>
public class AppVersionTests
{
    private static AppVersion V(string text)
    {
        AppVersion.TryParse(text, out var v).Should().BeTrue(text);
        return v!;
    }

    [Theory]
    [InlineData("1.0.0", "1.0.0")]
    [InlineData("v1.0.0", "1.0.0")]
    [InlineData("1.0.0-rc.2", "1.0.0-rc.2")]
    [InlineData("v2.10.3-rc.11", "2.10.3-rc.11")]
    // Так выглядит версия самого приложения: SDK дописывает хеш коммита после «+».
    [InlineData("1.0.0-rc.2+d163c4f79ec4d66636f756fb22bbfeb7878a76f4", "1.0.0-rc.2")]
    public void ParsesTagsAndTheAppsOwnVersion(string text, string normalized)
    {
        V(text).ToString().Should().Be(normalized);
    }

    [Theory]
    [InlineData("")]
    [InlineData(null)]
    [InlineData("1.2")]
    [InlineData("1.2.0.1")]
    [InlineData("01.2.0")]
    [InlineData("1.2.0-")]
    [InlineData("1.2.0-rc.01")]
    [InlineData("latest")]
    [InlineData("v1.2.0/../../evil")]
    [InlineData("1.2.0 beta")]
    [InlineData("99999999999.0.0")]
    public void RejectsWhatIsNotAVersion(string? text)
    {
        AppVersion.TryParse(text, out _).Should().BeFalse();
    }

    [Theory]
    [InlineData("v1.2.0+build")]
    [InlineData(" v1.2.0")]
    [InlineData("v1.2.0\n")]
    public void TagsCarryNoBuildMetadataAndNoWhitespace(string tag)
    {
        AppVersion.TryParseTag(tag, out _).Should().BeFalse();
    }

    [Fact]
    public void ReleaseCandidatesSortBelowTheReleaseAndByNumber()
    {
        string[] ordered = ["1.0.0-rc.2", "1.0.0-rc.10", "1.0.0", "1.0.1", "1.1.0-rc.1", "1.1.0", "2.0.0"];
        for (var i = 0; i + 1 < ordered.Length; i++)
        {
            (V(ordered[i]) < V(ordered[i + 1])).Should().BeTrue($"{ordered[i]} < {ordered[i + 1]}");
            (V(ordered[i + 1]) > V(ordered[i])).Should().BeTrue($"{ordered[i + 1]} > {ordered[i]}");
        }
    }

    [Fact]
    public void FollowsTheSemVerPrecedenceExample()
    {
        // semver.org, правило 11: пример цепочки как есть.
        string[] ordered =
        [
            "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11",
            "1.0.0-rc.1", "1.0.0",
        ];
        ordered.Select(V).Should().BeInAscendingOrder();
        ordered.Reverse().Select(V).Should().BeInDescendingOrder();
    }

    [Fact]
    public void BuildMetadataDoesNotMakeAVersionNewer()
    {
        V("1.0.0+aaa").Should().Be(V("1.0.0+bbb"));
        (V("1.0.0+zzz") > V("1.0.0")).Should().BeFalse();
    }

    [Fact]
    public void HugePreReleaseNumbersCompareAsNumbers()
    {
        (V("1.0.0-rc.99999999999") > V("1.0.0-rc.9999999999")).Should().BeTrue();
    }

    [Fact]
    public void TheRunningAppVersionIsSemVer()
    {
        // Utils.GetVersionInfo читает информационную версию сборки: с хвостом -rc.N, без хеша коммита.
        var running = Utils.GetVersionInfo();
        running.Should().NotContain("+");
        AppVersion.TryParse(running, out _).Should().BeTrue(running);
    }
}
