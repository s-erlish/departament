namespace ServiceLib.Services.AppUpdate;

/// <summary>
/// Откуда departament берёт свои обновления, и откуда не берёт ничего.
///
/// <para>Раньше обновление приложения шло из выпусков апстрима (Global.CoreUrls): «Обновить» поставило
/// бы поверх departament чужую программу. Теперь лента одна: выпуски
/// <see cref="Repo"/>, и каждый шаг, где адрес может уйти в сторону, проверяется здесь:</para>
/// <list type="bullet">
///   <item>лента — только <c>api.github.com/repos/s-erlish/departament/releases</c> и её <c>/latest</c>,
///   адрес собирается из констант, а не приходит извне;</item>
///   <item>загрузка — только <c>github.com/s-erlish/departament/releases/download/&lt;тег&gt;/&lt;файл&gt;</c>
///   для двух имён по контракту выпуска: пакета и его контрольной суммы; любое другое имя отвергается,
///   даже если оно лежит в том же выпуске;</item>
///   <item>github.com отвечает на загрузку перенаправлением в своё хранилище файлов; идти по нему можно
///   только на хосты выпусков GitHub (<see cref="DownloadHosts"/>) и только по https. raw.githubusercontent.com
///   и прочие *.githubusercontent.com в список не входят: там лежит содержимое любых репозиториев.</item>
/// </list>
/// </summary>
public sealed class AppUpdateChannel
{
    /// <summary>
    /// Репозиторий выпусков departament для ПК, <c>владелец/имя</c>: страница программы, где лежат её
    /// выпуски. Там же и исходный код.
    /// </summary>
    public const string Repo = "s-erlish/departament";

    /// <summary>Пакет выпуска по контракту CI: всё приложение под одним верхним каталогом.</summary>
    public const string AssetName = "departament-windows-x64.zip";

    /// <summary>Контрольная сумма пакета в формате sha256sum: <c>&lt;64 hex&gt;  departament-windows-x64.zip</c>.</summary>
    public const string ChecksumAssetName = AssetName + ".sha256";

    /// <summary>Верхний каталог внутри пакета, под ним лежат все записи.</summary>
    public const string PackageTopFolder = "departament-windows-x64";

    /// <summary>Имя exe приложения без расширения (AssemblyName в departament.Desktop.csproj).</summary>
    public const string AppExeBaseName = "departament";

    /// <summary>Имя установщика без расширения: он кладётся в пакет рядом с exe приложения.</summary>
    public const string InstallerBaseName = "AmazTool";

    /// <summary>
    /// Куда github.com перенаправляет загрузку файла выпуска. Сейчас (сентябрь 2026) это
    /// release-assets.githubusercontent.com, до 2025 года был objects.githubusercontent.com — оставлен,
    /// чтобы откат GitHub на старую схему не сломал обновление. Сравнение по имени хоста целиком.
    /// </summary>
    private static readonly string[] GitHubDownloadHosts =
        ["github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com"];

    /// <summary>Настоящий канал: выпуски на GitHub.</summary>
    public static AppUpdateChannel GitHub { get; } =
        new("https://api.github.com", "https://github.com", GitHubDownloadHosts, isDev: false);

    private readonly string _apiBase;
    private readonly string _webBase;
    private readonly string _scheme;

    private AppUpdateChannel(string apiBase, string webBase, string[] downloadHosts, bool isDev)
    {
        _apiBase = apiBase.TrimEnd('/');
        _webBase = webBase.TrimEnd('/');
        _scheme = new Uri(_webBase).Scheme;
        DownloadHosts = downloadHosts;
        IsDev = isDev;
    }

    /// <summary>Хосты, на которые разрешено перенаправление при загрузке.</summary>
    public IReadOnlyList<string> DownloadHosts { get; }

    /// <summary>Подставная лента разработчика (только отладочная сборка, см. <see cref="Current"/>).</summary>
    public bool IsDev { get; }

    /// <summary>Все выпуски, новые первыми (сюда идём, когда включены предварительные выпуски).</summary>
    public string ReleasesFeedUrl => $"{_apiBase}/repos/{Repo}/releases";

    /// <summary>Последний выпуск без пометки «предварительный». Для репозитория без выпусков GitHub отвечает 404.</summary>
    public string LatestFeedUrl => ReleasesFeedUrl + "/latest";

    /// <summary>Страница выпусков — запасной путь «скачать вручную».</summary>
    public string ReleasesPageUrl => $"{_webBase}/{Repo}/releases";

    /// <summary>
    /// Поддерживает ли эта система самообновление. Пакет по контракту один — для Windows x64, поэтому
    /// на остальных системах обновление не предлагается вовсе: «доступна версия X», за которой нечего
    /// скачать, — тупик. Подставная лента разработчика это ограничение снимает, чтобы весь путь можно было
    /// прогнать на Linux.
    /// </summary>
    public bool IsSupportedPlatform =>
        IsDev || (OperatingSystem.IsWindows() && RuntimeInformation.ProcessArchitecture == Architecture.X64);

    /// <summary>Адрес ленты: <see cref="LatestFeedUrl"/> или, с предварительными выпусками, <see cref="ReleasesFeedUrl"/>.</summary>
    public Uri FeedUrl(bool includePreRelease) => new(includePreRelease ? ReleasesFeedUrl : LatestFeedUrl);

    /// <summary>Лента — ровно один из двух адресов, без хвостов и параметров.</summary>
    public bool IsFeedUrl(Uri? uri) =>
        uri is not null && (uri.AbsoluteUri == LatestFeedUrl || uri.AbsoluteUri == ReleasesFeedUrl);

    /// <summary>Имя, которое разрешено скачивать: пакет или его контрольная сумма. Больше ничего.</summary>
    public static bool IsAllowedAssetName(string? name) => name is AssetName or ChecksumAssetName;

    /// <summary>
    /// Адрес загрузки файла выпуска. Тег проверяется по грамматике версии (<see cref="AppVersion.TryParseTag"/>):
    /// в нём не может оказаться ни «/», ни «?», и адрес не уйдёт из каталога выпуска.
    /// </summary>
    public Uri DownloadUrl(string tag, string asset)
    {
        if (!AppVersion.TryParseTag(tag, out _))
        {
            throw new ArgumentException($"not a release tag: {tag}", nameof(tag));
        }
        if (!IsAllowedAssetName(asset))
        {
            throw new ArgumentException($"not a release asset of departament: {asset}", nameof(asset));
        }
        return new Uri($"{_webBase}/{Repo}/releases/download/{tag}/{asset}");
    }

    /// <summary>Первый запрос загрузки — ровно адрес <see cref="DownloadUrl"/> для этого тега и имени.</summary>
    public bool IsDownloadUrl(Uri? uri, string tag, string asset)
    {
        if (uri is null || !AppVersion.TryParseTag(tag, out _) || !IsAllowedAssetName(asset))
        {
            return false;
        }
        return uri.AbsoluteUri == DownloadUrl(tag, asset).AbsoluteUri;
    }

    /// <summary>
    /// Можно ли идти по перенаправлению. Только схема канала (https у настоящего), порт по умолчанию, без
    /// логина в адресе и только на хост выпусков. На github.com путь обязан остаться в каталоге загрузок
    /// этого репозитория: github.com умеет отдавать и содержимое чужих репозиториев.
    /// </summary>
    public bool IsAllowedRedirect(Uri? uri)
    {
        if (uri is null || !uri.IsAbsoluteUri || uri.Scheme != _scheme || (!uri.IsDefaultPort && !IsDev)
            || uri.UserInfo.Length > 0)
        {
            return false;
        }
        var host = uri.Host;
        if (!DownloadHosts.Any(h => string.Equals(h, host, StringComparison.OrdinalIgnoreCase)))
        {
            return false;
        }
        var web = new Uri(_webBase);
        if (string.Equals(host, web.Host, StringComparison.OrdinalIgnoreCase) && uri.Port == web.Port)
        {
            return uri.AbsolutePath.StartsWith($"/{Repo}/releases/download/", StringComparison.Ordinal);
        }
        return true;
    }

    /// <summary>
    /// Канал этой сборки. В выпускной сборке — всегда <see cref="GitHub"/>.
    ///
    /// <para><b>Подставная лента разработчика</b> (<c>DP_DEV_UPDATE_FEED=http://127.0.0.1:порт</c>) нужна,
    /// чтобы прогнать проверку, загрузку, сверку и установку целиком на машине разработчика, не публикуя
    /// выпуск. Она существует ТОЛЬКО в отладочной сборке: код ниже компилируется под <c>#if DEBUG</c>, а
    /// выпуск CI собирает с <c>-c Release</c>, где этого кода нет в бинарнике вовсе — ни переменной
    /// окружения, ни файлу рядом с exe его не включить. Переменная окружения в выпускной сборке была бы
    /// опасна: её может выставить любая программа пользователя и подменить ленту, а вместе с ней то, что
    /// установщик разложит поверх приложения. Даже в отладочной сборке принимается только адрес на
    /// локальной петле (127.0.0.1, localhost).</para>
    /// </summary>
    public static AppUpdateChannel Current { get; } = Resolve();

    private static AppUpdateChannel Resolve()
    {
#if DEBUG
        var dev = Environment.GetEnvironmentVariable("DP_DEV_UPDATE_FEED");
        if (Uri.TryCreate(dev, UriKind.Absolute, out var feed) && feed.IsLoopback
            && (feed.Scheme == Uri.UriSchemeHttp || feed.Scheme == Uri.UriSchemeHttps))
        {
            // Второе имя петли играет роль хранилища файлов GitHub: подставной сервер перенаправляет
            // загрузку туда, и путь с перенаправлением проверяется так же, как на настоящем канале.
            var root = feed.GetLeftPart(UriPartial.Authority);
            return new AppUpdateChannel(root, root, ["127.0.0.1", "localhost"], isDev: true);
        }
#endif
        return GitHub;
    }
}
