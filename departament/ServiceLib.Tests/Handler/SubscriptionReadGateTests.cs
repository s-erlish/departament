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
/// Экран читает список серверов через ConfigHandler.ReadServersSettledAsync и не должен застать
/// подписку посреди замены её серверов (удалить → вставить → вернуть прежние id). Настоящая база
/// SQLite: гонка живёт между её записями.
/// </summary>
[Collection("SqliteDb")]
public class SubscriptionReadGateTests
{
    private readonly Config _config;

    public SubscriptionReadGateTests()
    {
        _config = CoreConfigTestFactory.CreateConfig();
        CoreConfigTestFactory.BindAppManagerConfig(_config);
        SQLiteHelper.Instance.CreateTable<SubItem>();
        SQLiteHelper.Instance.CreateTable<ProfileItem>();
        SQLiteHelper.Instance.CreateTable<ProfileExItem>();
        SQLiteHelper.Instance.CreateTable<ServerStatItem>();
    }

    private static string Body(int count) =>
        Convert.ToBase64String(Encoding.UTF8.GetBytes(string.Join("\n", Enumerable.Range(1, count).Select(i =>
            $"vless://986f25c1-b232-4d14-804b-cc6cd2575e8c@203.0.113.7:{1000 + i}?encryption=none&security=none&type=tcp#S{i}"))));

    [Fact]
    public async Task ReadsNeverSeeAGroupHalfReplaced()
    {
        var subid = Utils.GetGuid(false);
        await SQLiteHelper.Instance.InsertAsync(new SubItem { Id = subid, Remarks = "gate", Url = $"https://example.net/{subid}" });
        var body = Body(30);
        (await ConfigHandler.AddBatchServers(_config, body, subid, true)).Should().Be(30);
        var ids = (await AppManager.Instance.ProfileItems(subid) ?? []).Select(t => t.IndexId).ToHashSet();

        //  Читатель всё время перечитывает группу, пока подписка десять раз обновляется тем же телом.
        //  Каждое чтение обязано увидеть ровно прежние тридцать серверов: ни пустой группы, ни
        //  свежих id, которые через миг заменятся прежними.
        using var stop = new CancellationTokenSource();
        var seen = new List<(int Count, bool SameIds)>();
        var reader = Task.Run(async () =>
        {
            while (!stop.IsCancellationRequested)
            {
                var rows = await ConfigHandler.ReadServersSettledAsync(() => AppManager.Instance.ProfileItems(subid));
                lock (seen)
                {
                    seen.Add((rows?.Count ?? 0, rows?.All(t => ids.Contains(t.IndexId)) == true));
                }
            }
        }, TestContext.Current.CancellationToken);

        for (var i = 0; i < 10; i++)
        {
            (await ConfigHandler.AddBatchServers(_config, body, subid, true)).Should().Be(30);
        }
        stop.Cancel();
        await reader;

        seen.Should().NotBeEmpty();
        seen.Should().OnlyContain(r => r.Count == 30 && r.SameIds);
    }
}
