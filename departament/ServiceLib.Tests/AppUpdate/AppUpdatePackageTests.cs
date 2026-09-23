using System.IO.Compression;
using AwesomeAssertions;
using ServiceLib.Services.AppUpdate;
using Xunit;

namespace ServiceLib.Tests.AppUpdate;

/// <summary>
/// Состав пакета по контракту CI: один верхний каталог departament-windows-x64/, в нём приложение и
/// установщик, ни одной записи за его пределами.
/// </summary>
public class AppUpdatePackageTests : IDisposable
{
    private const string Top = AppUpdateChannel.PackageTopFolder + "/";
    private readonly string _dir = Directory.CreateTempSubdirectory("dp-package-").FullName;

    public void Dispose() => Directory.Delete(_dir, recursive: true);

    private string Zip(params string[] entries)
    {
        var path = Path.Combine(_dir, Guid.NewGuid().ToString("N") + ".zip");
        using var archive = ZipFile.Open(path, ZipArchiveMode.Create);
        foreach (var name in entries)
        {
            var entry = archive.CreateEntry(name);
            if (!name.EndsWith('/'))
            {
                using var stream = entry.Open();
                stream.Write("content"u8);
            }
        }
        return path;
    }

    private static bool Valid(string zip) => AppUpdatePackage.Validate(zip, "departament.exe", "AmazTool.exe", out _);

    [Fact]
    public void AcceptsTheContractLayout()
    {
        Valid(Zip(Top, Top + "departament.exe", Top + "AmazTool.exe", Top + "libSkiaSharp.dll",
            Top + "bin/", Top + "bin/xray/xray.exe", Top + "bin/sing_box/sing-box.exe", Top + "bin/srss/geosite-cn.srs"))
            .Should().BeTrue();
    }

    [Fact]
    public void AcceptsBackslashSeparatorsOfOldWindowsArchivers()
    {
        Valid(Zip(@"departament-windows-x64\departament.exe", @"departament-windows-x64\AmazTool.exe")).Should().BeTrue();
    }

    [Fact]
    public void RefusesAPackageWithoutTheApp()
    {
        AppUpdatePackage.Validate(Zip(Top + "AmazTool.exe", Top + "other.exe"), "departament.exe", "AmazTool.exe", out var problem)
            .Should().BeFalse();
        problem.Should().Contain("departament.exe");
    }

    [Fact]
    public void RefusesAPackageWithoutTheInstaller()
    {
        // Без установщика следующее обновление ставить было бы нечем.
        Valid(Zip(Top + "departament.exe", Top + "bin/xray/xray.exe")).Should().BeFalse();
    }

    [Fact]
    public void RefusesTheUpstreamLayout()
    {
        Valid(Zip("other-windows-64/other.exe", "other-windows-64/AmazTool.exe")).Should().BeFalse();
    }

    [Theory]
    [InlineData("evil.txt")]
    [InlineData("other/departament.exe")]
    [InlineData(Top + "../evil.txt")]
    [InlineData(Top + "bin/../../evil.txt")]
    [InlineData(Top + "C:/Windows/evil.dll")]
    [InlineData(Top + "departament.exe:stream")]
    [InlineData(Top + "bin//x.exe")]
    public void RefusesEntriesOutsideTheTopFolder(string extra)
    {
        Valid(Zip(Top + "departament.exe", Top + "AmazTool.exe", extra)).Should().BeFalse();
    }

    [Fact]
    public void RefusesAFileThatIsNotAZip()
    {
        var path = Path.Combine(_dir, "not.zip");
        File.WriteAllText(path, "<html>Not Found</html>");
        AppUpdatePackage.Validate(path, "departament.exe", "AmazTool.exe", out var problem).Should().BeFalse();
        problem.Should().NotBeNullOrEmpty();
    }
}
