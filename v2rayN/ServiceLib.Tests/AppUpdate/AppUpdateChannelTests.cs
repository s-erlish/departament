using AwesomeAssertions;
using ServiceLib.Services.AppUpdate;
using Xunit;

namespace ServiceLib.Tests.AppUpdate;

/// <summary>
/// Откуда можно брать обновление. Всё, что не лента и не файлы выпуска departament на GitHub, должно
/// отвергаться: иначе «Обновить» снова поставит чужое приложение (как было с 2dust/v2rayN).
/// </summary>
public class AppUpdateChannelTests
{
    private static readonly AppUpdateChannel GitHub = AppUpdateChannel.GitHub;

    [Fact]
    public void TheFeedIsOurRepoOnly()
    {
        GitHub.LatestFeedUrl.Should().Be("https://api.github.com/repos/s-erlish/departament/releases/latest");
        GitHub.ReleasesFeedUrl.Should().Be("https://api.github.com/repos/s-erlish/departament/releases");
        GitHub.FeedUrl(includePreRelease: false).AbsoluteUri.Should().Be(GitHub.LatestFeedUrl);
        GitHub.FeedUrl(includePreRelease: true).AbsoluteUri.Should().Be(GitHub.ReleasesFeedUrl);
    }

    [Theory]
    [InlineData("https://api.github.com/repos/2dust/v2rayN/releases/latest")]
    [InlineData("https://api.github.com/repos/s-erlish/dp/releases/latest")]
    [InlineData("http://api.github.com/repos/s-erlish/departament/releases/latest")]
    [InlineData("https://api.github.com/repos/s-erlish/departament/releases?per_page=100")]
    [InlineData("https://api.github.com/repos/s-erlish/departament/releases/tags/v1.0.0")]
    [InlineData("https://api.github.com.evil.example/repos/s-erlish/departament/releases")]
    public void AnyOtherFeedIsRefused(string url)
    {
        GitHub.IsFeedUrl(new Uri(url)).Should().BeFalse();
    }

    [Fact]
    public void DownloadsComeFromOurReleaseByTag()
    {
        GitHub.DownloadUrl("v1.2.0", AppUpdateChannel.AssetName).AbsoluteUri
            .Should().Be("https://github.com/s-erlish/departament/releases/download/v1.2.0/departament-windows-x64.zip");
        GitHub.DownloadUrl("v1.2.0-rc.3", AppUpdateChannel.ChecksumAssetName).AbsoluteUri
            .Should().Be("https://github.com/s-erlish/departament/releases/download/v1.2.0-rc.3/departament-windows-x64.zip.sha256");
        GitHub.IsDownloadUrl(GitHub.DownloadUrl("v1.2.0", AppUpdateChannel.AssetName), "v1.2.0", AppUpdateChannel.AssetName)
            .Should().BeTrue();
    }

    [Theory]
    [InlineData("departament-windows-x64.zip", true)]
    [InlineData("departament-windows-x64.zip.sha256", true)]
    [InlineData("Departament-windows-x64.zip", false)]
    [InlineData("departament-windows-arm64.zip", false)]
    [InlineData("departament-windows-x64.exe", false)]
    [InlineData("v2rayN-windows-64.zip", false)]
    [InlineData("v2rayN-windows-64-desktop.zip", false)]
    [InlineData("Xray-windows-64.zip", false)]
    [InlineData("../departament-windows-x64.zip", false)]
    [InlineData("", false)]
    [InlineData(null, false)]
    public void OnlyTheTwoContractAssetsAreAllowed(string? name, bool allowed)
    {
        AppUpdateChannel.IsAllowedAssetName(name).Should().Be(allowed);
    }

    [Theory]
    [InlineData("v1.2.0", "v2rayN-windows-64.zip")]
    [InlineData("v1.2.0", "sing-box-1.14.1-windows-amd64.zip")]
    [InlineData("latest", "departament-windows-x64.zip")]
    [InlineData("v1.2.0/../../../2dust/v2rayN/releases/download/7.0.0", "departament-windows-x64.zip")]
    [InlineData("v1.2.0?x=", "departament-windows-x64.zip")]
    [InlineData("v1.2.0+build", "departament-windows-x64.zip")]
    public void ForeignAssetsAndTagsNeverBecomeADownloadAddress(string tag, string asset)
    {
        var build = () => GitHub.DownloadUrl(tag, asset);
        build.Should().Throw<ArgumentException>();
        GitHub.IsDownloadUrl(new Uri("https://github.com/s-erlish/departament/releases/download/v1.2.0/departament-windows-x64.zip"), tag, asset)
            .Should().BeFalse();
    }

    [Theory]
    // Куда GitHub перенаправляет загрузку файла выпуска сейчас и куда перенаправлял раньше.
    [InlineData("https://release-assets.githubusercontent.com/github-production-release-asset/1/2?sp=r&sig=x")]
    [InlineData("https://objects.githubusercontent.com/github-production-release-asset-2e65be/1/2?X-Amz-Signature=x")]
    [InlineData("https://github.com/s-erlish/departament/releases/download/v1.2.0/departament-windows-x64.zip")]
    [InlineData("https://GitHub.com/s-erlish/departament/releases/download/v1.2.0/departament-windows-x64.zip")]
    public void RedirectsToGitHubReleaseStorageAreFollowed(string url)
    {
        GitHub.IsAllowedRedirect(new Uri(url)).Should().BeTrue();
    }

    [Theory]
    [InlineData("http://release-assets.githubusercontent.com/github-production-release-asset/1/2")]
    [InlineData("https://raw.githubusercontent.com/s-erlish/departament/main/departament.exe")]
    [InlineData("https://user-images.githubusercontent.com/1/2.zip")]
    [InlineData("https://release-assets.githubusercontent.com.evil.example/github-production-release-asset/1/2")]
    [InlineData("https://evil.example/departament-windows-x64.zip")]
    [InlineData("https://github.com/2dust/v2rayN/releases/download/7.23.4/v2rayN-windows-64.zip")]
    // Прежний репозиторий программы: выпуски берутся только из s-erlish/departament.
    [InlineData("https://github.com/s-erlish/v2rayN/releases/download/v1.2.0/departament-windows-x64.zip")]
    [InlineData("https://github.com/s-erlish/departament/raw/main/departament.exe")]
    [InlineData("https://user:secret@release-assets.githubusercontent.com/github-production-release-asset/1/2")]
    [InlineData("https://release-assets.githubusercontent.com:8443/github-production-release-asset/1/2")]
    [InlineData("ftp://release-assets.githubusercontent.com/x")]
    public void RedirectsAnywhereElseAreRefused(string url)
    {
        GitHub.IsAllowedRedirect(new Uri(url)).Should().BeFalse();
    }

    [Fact]
    public void AMissingOrRelativeRedirectIsRefused()
    {
        GitHub.IsAllowedRedirect(null).Should().BeFalse();
        GitHub.IsAllowedRedirect(new Uri("/s-erlish/departament/releases/download/v1.2.0/departament-windows-x64.zip", UriKind.Relative))
            .Should().BeFalse();
    }

    [Fact]
    public void SelfUpdateIsOfferedOnlyWhereThePackageRuns()
    {
        GitHub.IsSupportedPlatform.Should().Be(OperatingSystem.IsWindows()
                                               && RuntimeInformation.ProcessArchitecture == Architecture.X64);
    }
}
