using AwesomeAssertions;
using ServiceLib.Services.AppUpdate;
using Xunit;

namespace ServiceLib.Tests.AppUpdate;

/// <summary>
/// Выбор предложения из ленты выпусков GitHub. Образцы ниже повторяют ответ API
/// (<c>GET /repos/{owner}/{repo}/releases[/latest]</c>): лишние поля на месте, идентификаторы за
/// пределами int — как у GitHub сейчас.
/// </summary>
public class AppUpdateFeedTests
{
    private const long PackageSize = 163_300_000;

    private static string Asset(string name, long size = 1000, string state = "uploaded", string tag = "v1.1.0") => $$"""
        {
          "url": "https://api.github.com/repos/s-erlish/departament/releases/assets/9999999999",
          "id": 9999999999,
          "node_id": "RA_kwDOAAAAAAA",
          "name": "{{name}}",
          "label": null,
          "content_type": "application/zip",
          "state": "{{state}}",
          "size": {{size}},
          "download_count": 3,
          "created_at": "2026-09-20T10:00:00Z",
          "updated_at": "2026-09-20T10:01:00Z",
          "browser_download_url": "https://github.com/s-erlish/departament/releases/download/{{tag}}/{{name}}"
        }
        """;

    private static string OurAssets(string tag) =>
        Asset(AppUpdateChannel.AssetName, PackageSize, tag: tag) + "," + Asset(AppUpdateChannel.ChecksumAssetName, 93, tag: tag);

    private static string Release(string tag, bool prerelease = false, bool draft = false, string? assets = null, string body = "Исправления подключения") => $$"""
        {
          "url": "https://api.github.com/repos/s-erlish/departament/releases/8888888888",
          "html_url": "https://github.com/s-erlish/departament/releases/tag/{{tag}}",
          "id": 8888888888,
          "tag_name": "{{tag}}",
          "target_commitish": "main",
          "name": "departament {{tag}}",
          "draft": {{(draft ? "true" : "false")}},
          "prerelease": {{(prerelease ? "true" : "false")}},
          "created_at": "2026-09-20T09:59:00Z",
          "published_at": "2026-09-20T10:02:00Z",
          "assets": [{{assets ?? OurAssets(tag)}}],
          "tarball_url": "https://api.github.com/repos/s-erlish/departament/tarball/{{tag}}",
          "zipball_url": "https://api.github.com/repos/s-erlish/departament/zipball/{{tag}}",
          "body": "{{body}}"
        }
        """;

    private static string List(params string[] releases) => "[" + string.Join(",", releases) + "]";

    private static AppVersion V(string text)
    {
        AppVersion.TryParse(text, out var v).Should().BeTrue();
        return v!;
    }

    // ---------------------------------------------------------------- /releases/latest

    [Fact]
    public void LatestReleaseNewerThanRunningIsOffered()
    {
        var result = AppUpdateFeed.Select(Release("v1.1.0"), isList: false, V("1.0.0"), includePreRelease: false);

        result.Verdict.Should().Be(AppUpdateVerdict.Offer);
        result.Offer!.Version.Should().Be(V("1.1.0"));
        result.Offer.Tag.Should().Be("v1.1.0");
        result.Offer.IsPreRelease.Should().BeFalse();
        result.Offer.PackageSize.Should().Be(PackageSize);
        result.Offer.Notes.Should().Be("Исправления подключения");
    }

    [Theory]
    [InlineData("1.1.0")]
    [InlineData("1.1.1")]
    [InlineData("2.0.0-rc.1")]
    public void NeverNewerNeverOffered(string running)
    {
        var result = AppUpdateFeed.Select(Release("v1.1.0"), isList: false, V(running), includePreRelease: false);

        result.Verdict.Should().Be(AppUpdateVerdict.UpToDate);
        result.Offer.Should().BeNull();
    }

    [Fact]
    public void AReleaseCandidateIsOfferedItsRelease()
    {
        var result = AppUpdateFeed.Select(Release("v1.1.0"), isList: false, V("1.1.0-rc.3"), includePreRelease: false);

        result.Verdict.Should().Be(AppUpdateVerdict.Offer);
        result.Offer!.Version.Should().Be(V("1.1.0"));
    }

    [Fact]
    public void AnRcTagWithoutThePreReleaseFlagIsStillAPreRelease()
    {
        // Выпуск v1.2.0-rc.1, по ошибке опубликованный как «latest»: тем, кто не просил ранних версий,
        // он не предлагается.
        var result = AppUpdateFeed.Select(Release("v1.2.0-rc.1", prerelease: false), isList: false, V("1.1.0"), includePreRelease: false);

        result.Verdict.Should().Be(AppUpdateVerdict.NoRelease);
    }

    [Fact]
    public void AReleaseWithoutOurPackageIsNotAnOffer()
    {
        var foreignAssets = Asset("v2rayN-windows-64.zip") + "," + Asset("departament-linux-x64.zip") + "," + Asset("Source code.zip");
        var result = AppUpdateFeed.Select(Release("v1.1.0", assets: foreignAssets), isList: false, V("1.0.0"), includePreRelease: false);

        result.Verdict.Should().Be(AppUpdateVerdict.NoPackage);
        result.Offer.Should().BeNull();
        result.Newest.Should().Be(V("1.1.0"));
    }

    [Fact]
    public void APackageWithoutItsChecksumIsNotAnOffer()
    {
        var result = AppUpdateFeed.Select(Release("v1.1.0", assets: Asset(AppUpdateChannel.AssetName, PackageSize)),
            isList: false, V("1.0.0"), includePreRelease: false);

        result.Verdict.Should().Be(AppUpdateVerdict.NoPackage);
    }

    [Fact]
    public void APackageStillUploadingIsNotAnOffer()
    {
        var uploading = Asset(AppUpdateChannel.AssetName, PackageSize, state: "starter") + "," + Asset(AppUpdateChannel.ChecksumAssetName, 93);
        var result = AppUpdateFeed.Select(Release("v1.1.0", assets: uploading), isList: false, V("1.0.0"), includePreRelease: false);

        result.Verdict.Should().Be(AppUpdateVerdict.NoPackage);
    }

    // ---------------------------------------------------------------- /releases (предварительные выпуски)

    [Fact]
    public void WithPreReleasesTheNewestRcIsOffered()
    {
        var feed = List(Release("v1.2.0-rc.1", prerelease: true), Release("v1.1.0"));

        var result = AppUpdateFeed.Select(feed, isList: true, V("1.1.0"), includePreRelease: true);

        result.Verdict.Should().Be(AppUpdateVerdict.Offer);
        result.Offer!.Version.Should().Be(V("1.2.0-rc.1"));
        result.Offer.IsPreRelease.Should().BeTrue();
    }

    [Fact]
    public void WithoutPreReleasesTheRcIsIgnored()
    {
        var feed = List(Release("v1.2.0-rc.1", prerelease: true), Release("v1.1.0"));

        AppUpdateFeed.Select(feed, isList: true, V("1.1.0"), includePreRelease: false).Verdict.Should().Be(AppUpdateVerdict.UpToDate);
        AppUpdateFeed.Select(feed, isList: true, V("1.0.0"), includePreRelease: false).Offer!.Version.Should().Be(V("1.1.0"));
    }

    [Fact]
    public void TheHighestVersionWinsNotTheFirstInTheList()
    {
        // GitHub сортирует по дате создания: заплатка к старой ветке может стоять выше новой версии.
        var feed = List(Release("v1.1.1"), Release("v1.2.0"), Release("v1.2.0-rc.2", prerelease: true));

        AppUpdateFeed.Select(feed, isList: true, V("1.0.0"), includePreRelease: true).Offer!.Version.Should().Be(V("1.2.0"));
    }

    [Fact]
    public void ANewerReleaseWithoutOurPackageFallsBackToTheNewestInstallableOne()
    {
        var feed = List(Release("v1.2.0", assets: Asset("departament-linux-x64.zip")), Release("v1.1.0"));

        var offered = AppUpdateFeed.Select(feed, isList: true, V("1.0.0"), includePreRelease: true);
        offered.Verdict.Should().Be(AppUpdateVerdict.Offer);
        offered.Offer!.Version.Should().Be(V("1.1.0"));

        var nothing = AppUpdateFeed.Select(feed, isList: true, V("1.1.0"), includePreRelease: true);
        nothing.Verdict.Should().Be(AppUpdateVerdict.NoPackage);
        nothing.Newest.Should().Be(V("1.2.0"));
    }

    [Fact]
    public void DraftsAndNonVersionTagsAreIgnored()
    {
        var feed = List(Release("v9.0.0", draft: true), Release("nightly"), Release("latest"), Release("v1.1.0"));

        AppUpdateFeed.Select(feed, isList: true, V("1.0.0"), includePreRelease: true).Offer!.Version.Should().Be(V("1.1.0"));
        AppUpdateFeed.Select(List(Release("v9.0.0", draft: true), Release("nightly")), isList: true, V("1.0.0"), includePreRelease: true)
            .Verdict.Should().Be(AppUpdateVerdict.NoRelease);
    }

    [Fact]
    public void AnEmptyFeedMeansNoReleaseYet()
    {
        AppUpdateFeed.Select("[]", isList: true, V("1.0.0"), includePreRelease: true).Verdict.Should().Be(AppUpdateVerdict.NoRelease);
    }

    [Theory]
    [InlineData("<html>rate limited</html>", false)]
    [InlineData("{\"message\": \"Not Found\"", false)]
    [InlineData("null", false)]
    [InlineData("{\"tag_name\": \"v1.1.0\"}", true)]
    public void AnswersThatAreNotAFeedAreUnreadable(string body, bool isList)
    {
        AppUpdateFeed.Select(body, isList, V("1.0.0"), includePreRelease: isList).Verdict.Should().Be(AppUpdateVerdict.Unreadable);
    }
}
