using AwesomeAssertions;
using Xunit;

namespace ServiceLib.Tests.Common;

/// <summary>
/// Имя компьютера уходит в заголовок x-device-model, а .NET не отправляет запрос с не-ASCII в
/// заголовке. Программа на компьютере с русским именем не могла ни войти, ни загрузить подписку.
/// </summary>
public class HeaderAsciiTests
{
    [Theory]
    [InlineData("ИВАН-ПК", "IVAN-PK")]
    [InlineData("ЖЕНЯ-ПК", "ZHENYA-PK")]
    [InlineData("Женя", "Zhenya")]
    [InlineData("ПОДЪЕЗД", "PODEZD")]
    [InlineData("DESKTOP-4F2K1", "DESKTOP-4F2K1")]
    [InlineData("Café-PC", "Cafe-PC")]
    [InlineData("ОФИС 2", "OFIS 2")]
    public void CyrillicAndAccentsBecomeAscii(string name, string expected)
    {
        Utils.ToHeaderAscii(name, "PC").Should().Be(expected);
    }

    [Theory]
    [InlineData("")]
    [InlineData(null)]
    [InlineData("电脑")]
    [InlineData("  ")]
    public void NothingPrintableFallsBack(string? name)
    {
        Utils.ToHeaderAscii(name, "PC").Should().Be("PC");
    }

    [Fact]
    public void ResultIsAlwaysSendableAsHeader()
    {
        var value = Utils.ToHeaderAscii("Й\r\nЁ\tкомп😀", "PC");

        value.Should().MatchRegex("^[ -~]+$");
        using var request = new HttpRequestMessage(HttpMethod.Get, "https://example.invalid/");
        request.Headers.TryAddWithoutValidation("x-device-model", value).Should().BeTrue();
    }
}
