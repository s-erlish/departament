using AwesomeAssertions;
using ServiceLib.Services.AppUpdate;
using Xunit;

namespace ServiceLib.Tests.AppUpdate;

/// <summary>
/// Сверка пакета с его .sha256: файл, который не сошёлся, до установщика не доходит.
/// </summary>
public class AppUpdateChecksumTests
{
    // SHA-256 строки «abc» (FIPS 180-2, пример B.1).
    private const string AbcSha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private const string Name = AppUpdateChannel.AssetName;

    [Theory]
    [InlineData(AbcSha256 + "  departament-windows-x64.zip\n")]
    [InlineData(AbcSha256 + "  departament-windows-x64.zip")]
    [InlineData(AbcSha256 + " *departament-windows-x64.zip\n")]
    [InlineData(AbcSha256 + "  departament-windows-x64.zip\r\n")]
    [InlineData("BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD  departament-windows-x64.zip\n")]
    [InlineData(AbcSha256 + "\n")]
    public void ReadsTheSha256sumLine(string content)
    {
        AppUpdateChecksum.TryParse(content, Name, out var sha).Should().BeTrue();
        sha.Should().Be(AbcSha256);
    }

    [Theory]
    [InlineData(AbcSha256 + "  other-windows-64.zip\n")]
    [InlineData(AbcSha256 + "  departament-windows-x64.zip.sha256\n")]
    [InlineData("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015a  departament-windows-x64.zip\n")]
    [InlineData("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015add  departament-windows-x64.zip\n")]
    [InlineData("ga7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad  departament-windows-x64.zip\n")]
    [InlineData(AbcSha256 + "  departament-windows-x64.zip\n" + AbcSha256 + "  departament-windows-x64.zip\n")]
    [InlineData("")]
    [InlineData(null)]
    [InlineData("<html>Not Found</html>")]
    public void RefusesAnythingElse(string? content)
    {
        AppUpdateChecksum.TryParse(content, Name, out _).Should().BeFalse();
    }

    [Fact]
    public async Task HashesTheFileAndDetectsAnyChange()
    {
        var path = Path.GetTempFileName();
        try
        {
            await File.WriteAllTextAsync(path, "abc", TestContext.Current.CancellationToken);
            (await AppUpdateChecksum.ComputeAsync(path, TestContext.Current.CancellationToken)).Should().Be(AbcSha256);
            (await AppUpdateChecksum.MatchesAsync(path, AbcSha256, TestContext.Current.CancellationToken)).Should().BeTrue();
            (await AppUpdateChecksum.MatchesAsync(path, AbcSha256.ToUpperInvariant(), TestContext.Current.CancellationToken)).Should().BeTrue();

            await File.WriteAllTextAsync(path, "abd", TestContext.Current.CancellationToken);
            (await AppUpdateChecksum.MatchesAsync(path, AbcSha256, TestContext.Current.CancellationToken)).Should().BeFalse();
        }
        finally
        {
            File.Delete(path);
        }
    }
}
