using System.Text;
using AwesomeAssertions;
using ServiceLib.Handler;
using ServiceLib.Helper;
using ServiceLib.Manager;
using ServiceLib.Models;
using ServiceLib.Tests.CoreConfig;
using Xunit;

namespace ServiceLib.Tests.Handler;

/// <summary>
/// Обновление подписки узнаёт серверы прошлого поколения и оставляет им прежние IndexId
/// (ConfigHandler.KeepIndexIdsAcrossRefresh). Настоящая база SQLite в каталоге тестов: логика
/// живёт в SQL-операциях, и проверять её в отрыве от них бессмысленно.
/// </summary>
[Collection("SqliteDb")]
public class SubscriptionRefreshIdentityTests
{
    private readonly Config _config;

    public SubscriptionRefreshIdentityTests()
    {
        _config = CoreConfigTestFactory.CreateConfig();
        CoreConfigTestFactory.BindAppManagerConfig(_config);
        SQLiteHelper.Instance.CreateTable<SubItem>();
        SQLiteHelper.Instance.CreateTable<ProfileItem>();
        SQLiteHelper.Instance.CreateTable<ProfileExItem>();
        SQLiteHelper.Instance.CreateTable<ServerStatItem>();
    }

    private static string Vless(string name, int port) =>
        $"vless://986f25c1-b232-4d14-804b-cc6cd2575e8c@203.0.113.7:{port}?encryption=none&security=none&type=tcp#{name}";

    private static string Base64(params string[] links) =>
        Convert.ToBase64String(Encoding.UTF8.GetBytes(string.Join("\n", links)));

    private static string XrayElement(string? remarks, int port)
    {
        var remarksPart = remarks is null ? string.Empty : $"\"remarks\": \"{remarks}\", ";
        return "{" + remarksPart
            + "\"outbounds\": [{\"protocol\": \"vless\", \"tag\": \"proxy\", \"settings\": {\"vnext\": [{\"address\": \"vpn.example.net\", \"port\": "
            + port
            + ", \"users\": [{\"id\": \"986f25c1-b232-4d14-804b-cc6cd2575e8c\", \"encryption\": \"none\"}]}]}}, {\"protocol\": \"freedom\", \"tag\": \"direct\"}]}";
    }

    private static string XrayArray(params string[] elements) => "[" + string.Join(",", elements) + "]";

    private async Task<string> NewSubscription(string remarks)
    {
        var id = Utils.GetGuid(false);
        await SQLiteHelper.Instance.InsertAsync(new SubItem { Id = id, Remarks = remarks, Url = $"https://example.net/{id}" });
        return id;
    }

    private static async Task<List<ProfileItem>> Rows(string subid) =>
        await AppManager.Instance.ProfileItems(subid) ?? [];

    [Fact]
    public async Task UnchangedLinksKeepTheirIdsAndOrder()
    {
        var subid = await NewSubscription("links");
        var body = Base64(Vless("A", 1001), Vless("B", 1002), Vless("C", 1003));

        (await ConfigHandler.AddBatchServers(_config, body, subid, true)).Should().Be(3);
        var first = await Rows(subid);

        (await ConfigHandler.AddBatchServers(_config, body, subid, true)).Should().Be(3);
        var second = await Rows(subid);

        second.Select(t => t.IndexId).Should().Equal(first.Select(t => t.IndexId));
        second.Select(t => t.Remarks).Should().Equal("A", "B", "C");
    }

    [Fact]
    public async Task ChangedLinkGetsNewIdNeighboursKeepTheirs()
    {
        var subid = await NewSubscription("links-changed");
        (await ConfigHandler.AddBatchServers(_config, Base64(Vless("A", 1001), Vless("B", 1002), Vless("C", 1003)), subid, true)).Should().Be(3);
        var before = (await Rows(subid)).ToDictionary(t => t.Remarks, t => t.IndexId);

        (await ConfigHandler.AddBatchServers(_config, Base64(Vless("A", 1001), Vless("B", 2002), Vless("C", 1003)), subid, true)).Should().Be(3);
        var after = await Rows(subid);

        after.Select(t => t.Remarks).Should().Equal("A", "B", "C");
        after.Single(t => t.Remarks == "A").IndexId.Should().Be(before["A"]);
        after.Single(t => t.Remarks == "C").IndexId.Should().Be(before["C"]);
        after.Single(t => t.Remarks == "B").IndexId.Should().NotBe(before["B"]);
    }

    [Fact]
    public async Task UnchangedProviderConfigsKeepIdsAndTheirFiles()
    {
        var subid = await NewSubscription("xray-json");
        var body = XrayArray(XrayElement("X", 443), XrayElement("Y", 8443), XrayElement("Z", 9443));

        (await ConfigHandler.AddBatchServers(_config, body, subid, true)).Should().Be(3);
        var first = await Rows(subid);

        (await ConfigHandler.AddBatchServers(_config, body, subid, true)).Should().Be(3);
        var second = await Rows(subid);

        second.Select(t => t.IndexId).Should().Equal(first.Select(t => t.IndexId));
        //  Файл неизменившегося сервера остался прежним и лежит на диске.
        second.Select(t => t.Address).Should().Equal(first.Select(t => t.Address));
        second.Should().OnlyContain(t => File.Exists(Utils.GetConfigPath(t.Address)));
    }

    [Fact]
    public async Task ChangedProviderConfigKeepsIdByUniqueName()
    {
        var subid = await NewSubscription("xray-json-changed");
        (await ConfigHandler.AddBatchServers(_config, XrayArray(XrayElement("X", 443), XrayElement("Y", 8443)), subid, true)).Should().Be(2);
        var before = (await Rows(subid)).ToDictionary(t => t.Remarks, t => t);

        (await ConfigHandler.AddBatchServers(_config, XrayArray(XrayElement("X", 443), XrayElement("Y", 18443)), subid, true)).Should().Be(2);
        var after = (await Rows(subid)).ToDictionary(t => t.Remarks, t => t);

        after["X"].IndexId.Should().Be(before["X"].IndexId);
        after["X"].Address.Should().Be(before["X"].Address);
        after["Y"].IndexId.Should().Be(before["Y"].IndexId);
        //  Содержимое другое — и файл новый, а старый удалён вместе с прошлым поколением.
        after["Y"].Address.Should().NotBe(before["Y"].Address);
        File.Exists(Utils.GetConfigPath(before["Y"].Address)).Should().BeFalse();
        File.Exists(Utils.GetConfigPath(after["Y"].Address)).Should().BeTrue();
    }

    [Fact]
    public async Task NamelessProviderConfigsDoNotShiftOntoNeighbours()
    {
        var subid = await NewSubscription("nameless");
        //  У элементов нет remarks — все получают имя подписки, по имени их не различить.
        (await ConfigHandler.AddBatchServers(_config, XrayArray(XrayElement(null, 1443), XrayElement(null, 2443), XrayElement(null, 3443)), subid, true)).Should().Be(3);
        var before = await Rows(subid);
        var idOfThird = before[2].IndexId;
        var idOfSecond = before[1].IndexId;

        //  Провайдер убрал первый узел: второй и третий обязаны сохранить СВОИ id, а не соседей.
        (await ConfigHandler.AddBatchServers(_config, XrayArray(XrayElement(null, 2443), XrayElement(null, 3443)), subid, true)).Should().Be(2);
        var after = await Rows(subid);

        after.Select(t => t.IndexId).Should().Equal(idOfSecond, idOfThird);
    }

    [Fact]
    public async Task ImportIntoDeletedSubscriptionDoesNothing()
    {
        var subid = await NewSubscription("deleted");
        await ConfigHandler.DeleteSubItem(_config, subid);

        (await ConfigHandler.AddBatchServers(_config, Base64(Vless("A", 1001)), subid, true)).Should().Be(-1);
        (await Rows(subid)).Should().BeEmpty();
    }
}
