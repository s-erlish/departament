namespace ServiceLib.Services.AppUpdate;

/// <summary>Выпуск, который можно предложить пользователю: он новее запущенной версии и несёт пакет.</summary>
/// <param name="Version">Версия из тега.</param>
/// <param name="Tag">Тег как есть (<c>v1.2.0</c>): из него собираются адреса загрузки.</param>
/// <param name="IsPreRelease">Предварительный выпуск (пометка GitHub или хвост <c>-rc.N</c>).</param>
/// <param name="PackageSize">Размер пакета по ленте, байт; 0, если лента его не назвала.</param>
/// <param name="Notes">Описание выпуска, как его написали на GitHub.</param>
public sealed record AppUpdateOffer(AppVersion Version, string Tag, bool IsPreRelease, long PackageSize, string? Notes);

/// <summary>Что ответила лента.</summary>
public enum AppUpdateVerdict
{
    /// <summary>Есть более новая версия с пакетом — её и предлагаем (<see cref="AppUpdateFeedResult.Offer"/>).</summary>
    Offer,

    /// <summary>Самая новая подходящая версия не новее запущенной.</summary>
    UpToDate,

    /// <summary>
    /// Выпусков нет вовсе (или нет ни одного подходящего канала). Это ответ, а не сбой: так отвечает
    /// репозиторий, где ещё ничего не опубликовано, — ровно то состояние, в котором проект сейчас.
    /// </summary>
    NoRelease,

    /// <summary>Есть более новая версия, но пакета для этой системы в ней нет: предлагать нечего.</summary>
    NoPackage,

    /// <summary>Ответ не разбирается как лента выпусков GitHub.</summary>
    Unreadable,
}

public sealed record AppUpdateFeedResult(AppUpdateVerdict Verdict, AppUpdateOffer? Offer = null, AppVersion? Newest = null);

/// <summary>
/// Разбор ленты выпусков GitHub и выбор предложения. Чистая функция без сети и без диска: по ней
/// написаны тесты, и по ней же работает приложение.
///
/// <para>Правила выбора — те же, что у departament для Android (UpdateCheckerManager), и все они
/// про то, чтобы не предложить лишнего:</para>
/// <list type="bullet">
///   <item>черновики не рассматриваются; тег обязан быть версией (<see cref="AppVersion.TryParseTag"/>);</item>
///   <item>предварительный выпуск (пометка GitHub ИЛИ хвост <c>-rc.N</c> в теге) рассматривается, только
///   если пользователь включил «Искать предварительный выпуск»: rc, по ошибке опубликованный без пометки,
///   не должен доехать до тех, кто её не просил;</item>
///   <item>предлагается только версия СТРОГО новее запущенной: равная или более старая — «обновлений нет»;</item>
///   <item>у выпуска должны быть оба файла контракта (<see cref="AppUpdateChannel.AssetName"/> и его
///   <c>.sha256</c>) с состоянием «uploaded»; прочие файлы выпуска не рассматриваются вовсе;</item>
///   <item>из подходящих берётся самая новая по SemVer, а не первая в списке: GitHub сортирует ленту по
///   дате создания, и выпуск-заплатка к старой ветке может оказаться выше более новой версии.</item>
/// </list>
/// </summary>
public static class AppUpdateFeed
{
    private static readonly JsonSerializerOptions Options = new() { PropertyNameCaseInsensitive = true };

    /// <param name="json">Тело ответа ленты.</param>
    /// <param name="isList">Ответ <c>/releases</c> (массив), а не <c>/releases/latest</c> (один объект).</param>
    /// <param name="running">Запущенная версия.</param>
    /// <param name="includePreRelease">Включены ли предварительные выпуски.</param>
    public static AppUpdateFeedResult Select(string? json, bool isList, AppVersion running, bool includePreRelease)
    {
        List<FeedRelease>? releases;
        try
        {
            releases = isList
                ? JsonSerializer.Deserialize<List<FeedRelease>>(json ?? string.Empty, Options)
                : JsonSerializer.Deserialize<FeedRelease>(json ?? string.Empty, Options) is { } one ? [one] : null;
        }
        catch (JsonException)
        {
            return new AppUpdateFeedResult(AppUpdateVerdict.Unreadable);
        }
        if (releases is null)
        {
            return new AppUpdateFeedResult(AppUpdateVerdict.Unreadable);
        }

        var candidates = releases
            .Where(r => r is { Draft: false })
            .Select(r => (Release: r, Version: AppVersion.TryParseTag(r.TagName, out var v) ? v : null))
            .Where(c => c.Version is not null)
            .Select(c => (c.Release, Version: c.Version!, Pre: c.Release.Prerelease || c.Version!.IsPreRelease))
            .Where(c => includePreRelease || !c.Pre)
            .ToList();
        if (candidates.Count == 0)
        {
            return new AppUpdateFeedResult(AppUpdateVerdict.NoRelease);
        }

        var newest = candidates.Max(c => c.Version)!;
        var installable = candidates
            .Where(c => c.Version > running && HasUploaded(c.Release, AppUpdateChannel.AssetName)
                        && HasUploaded(c.Release, AppUpdateChannel.ChecksumAssetName))
            .OrderByDescending(c => c.Version)
            .FirstOrDefault();
        if (installable.Release is not null)
        {
            var package = installable.Release.Assets!.First(a => a.Name == AppUpdateChannel.AssetName);
            var offer = new AppUpdateOffer(installable.Version, installable.Release.TagName!, installable.Pre,
                Math.Max(0, package.Size), installable.Release.Body);
            return new AppUpdateFeedResult(AppUpdateVerdict.Offer, offer, newest);
        }

        return newest > running
            ? new AppUpdateFeedResult(AppUpdateVerdict.NoPackage, Newest: newest)
            : new AppUpdateFeedResult(AppUpdateVerdict.UpToDate, Newest: newest);
    }

    /// <summary>Файл с ТОЧНО этим именем, дозагруженный до конца (у недокачанного state = «starter»).</summary>
    private static bool HasUploaded(FeedRelease release, string name) =>
        release.Assets?.Any(a => a.Name == name && string.Equals(a.State, "uploaded", StringComparison.Ordinal)) == true;

    // Только поля, которые нужны выбору. Отдельно от GitHubRelease движка: там идентификаторы — int, а у
    // GitHub они давно растут к пределу int, и переполнение уронило бы разбор всей ленты.
    private sealed class FeedRelease
    {
        [JsonPropertyName("tag_name")] public string? TagName { get; set; }
        [JsonPropertyName("draft")] public bool Draft { get; set; }
        [JsonPropertyName("prerelease")] public bool Prerelease { get; set; }
        [JsonPropertyName("body")] public string? Body { get; set; }
        [JsonPropertyName("assets")] public List<FeedAsset>? Assets { get; set; }
    }

    private sealed class FeedAsset
    {
        [JsonPropertyName("name")] public string? Name { get; set; }
        [JsonPropertyName("state")] public string? State { get; set; }
        [JsonPropertyName("size")] public long Size { get; set; }
    }
}
