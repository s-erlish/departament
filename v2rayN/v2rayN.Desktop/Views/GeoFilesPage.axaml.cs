using ServiceLib.Services;
using v2rayN.Desktop.Common;

namespace v2rayN.Desktop.Views;

/// <summary>
/// «Файлы ресурсов» — in-app суб-страница (раньше отдельное окно). Real: downloads geoip.dat +
/// geosite.dat (and sing-box .srs rulesets) through the engine's <see cref="UpdateService"/>, streaming
/// per-file progress into the status line and refreshing the on-disk file info. Pure asset update —
/// never touches the core. Стрелка «назад» поднимает <see cref="BackRequested"/>.
/// </summary>
public partial class GeoFilesPage : UserControl, ISubPage
{
    private readonly Config _config;
    private bool _busy;

    public event EventHandler? BackRequested;

    public GeoFilesPage()
    {
        InitializeComponent();

        _config = AppManager.Instance.Config;

        btnBack.Click += (_, _) => BackRequested?.Invoke(this, EventArgs.Empty);

        // Движок качает обе базы одним вызовом UpdateGeoFileAll — раздельного обновления в ветке
        // нет. Поэтому тап по любой из строк запускает одно и то же, а «Обновление…» загорается
        // сразу в обеих: обещать построчное обновление там, где его нет, — врать интерфейсом.
        RowGeosite.Tapped += async (_, _) => await UpdateAsync();
        RowGeoip.Tapped += async (_, _) => await UpdateAsync();

        txtSource.Text = DescribeSource();
        RefreshFileInfo();
    }

    private void RefreshFileInfo()
    {
        txtGeoip.Text = DescribeFile("geoip.dat");
        txtGeosite.Text = DescribeFile("geosite.dat");
    }

    /// <summary>
    /// Источник — это РЕПОЗИТОРИЙ, а не полный адрес: адрес в ветке фиксирован
    /// (<see cref="Global.GeoUrl"/> либо пресет региона в <c>ConstItem.GeoSourceUrl</c>) и является
    /// шаблоном с «{0}» — показывать его целиком значит показывать то, что нельзя ни выбрать, ни
    /// прочитать одним взглядом. «Loyalsoldier/v2ray-rules-dat» — та же строка, что в эталоне: по ней
    /// сразу видно, чьи базы качаются. Не-GitHub адрес сводим к хосту, нечитаемый — оставляем как есть.
    /// </summary>
    private string DescribeSource()
    {
        var url = _config.ConstItem.GeoSourceUrl;
        if (url.IsNullOrEmpty())
        {
            url = Global.GeoUrl;
        }

        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri))
        {
            return url;
        }

        var segments = uri.AbsolutePath.Split('/', StringSplitOptions.RemoveEmptyEntries);
        return uri.Host.EndsWith("github.com", StringComparison.OrdinalIgnoreCase) && segments.Length >= 2
            ? $"{segments[0]}/{segments[1]}"
            : uri.Host;
    }

    /// <summary>
    /// Подпись файла — «2 МБ · 03.08.2026» (эталонный кадр): размер и дата, без времени и без слова
    /// «обновлён». Дата у базы маршрутизации отвечает на один вопрос — «свежая или нет», — и час с
    /// минутами на него не отвечают, а строку удлиняют. Единицу берём из общей лестницы
    /// <c>Common_ByteUnits</c> (та же, что у трафика), поэтому в английской локали строка сама
    /// становится «2 MB · 03.08.2026». Разделитель «·» — та же пунктуация, что во всём интерфейсе.
    /// </summary>
    private static string DescribeFile(string name)
    {
        try
        {
            var path = Utils.GetBinPath(name);
            if (!File.Exists(path))
            {
                return L.T("Geo_NotDownloaded");
            }
            var fi = new FileInfo(path);
            var mb = fi.Length / 1024d / 1024d;
            var units = L.T("Common_ByteUnits").Split(',');
            var unit = units.Length > 2 ? units[2] : "MB";
            //  «0.#»: у целого числа мегабайт десятой доли не показываем — 2 МБ, а не 2,0 МБ.
            var size = mb.ToString("0.#", CultureInfo.CurrentUICulture);
            var stamp = fi.LastWriteTime.ToString("dd.MM.yyyy", CultureInfo.CurrentUICulture);
            return $"{size} {unit} · {stamp}";
        }
        catch
        {
            // Файл на месте, а прочитать его не вышло (права, битый том, гонка с загрузкой). «—»
            // здесь молчало: пользователь видел прочерк и не знал ни что случилось, ни что делать.
            // Строка называет отказ; кнопка «Обновить» рядом — следующий шаг, она перекачает файл.
            return L.T("Geo_ReadFailed");
        }
    }

    private async Task UpdateAsync()
    {
        if (_busy)
        {
            return;
        }
        _busy = true;
        SetActionText(L.T("Geo_Updating"), busy: true);
        SetStatus(L.T("Geo_Downloading"));

        //  ПОСЛЕДНИЙ ОТКАЗ, О КОТОРОМ СКАЗАЛА СЛУЖБА. Загрузка geo-файлов исключений не бросает:
        //  про неудачу она сообщает вызовом обратно с success = false, а в конце всё равно шлёт
        //  собственное «geo загружены успешно». Без этой переменной экран писал «Готово. Базы
        //  обновлены.» поверх сообщения об отказе — при выключенной сети пользователь получал
        //  обещание обновления, которого не было, и старые даты файлов под ним.
        //  Все вызовы обратно завершаются до возврата из UpdateGeoFileAll, поэтому обычной
        //  захваченной переменной здесь достаточно.
        var failure = string.Empty;

        try
        {
            var svc = new UpdateService(_config, (success, msg) =>
            {
                if (!success && msg.IsNotEmpty())
                {
                    failure = msg;
                }
                Dispatcher.UIThread.Post(() =>
                {
                    if (msg.IsNotEmpty())
                    {
                        SetStatus(msg);
                    }
                    if (success)
                    {
                        RefreshFileInfo();
                    }
                });
                return Task.CompletedTask;
            });
            await svc.UpdateGeoFileAll();
            SetStatus(failure.IsNotEmpty() ? L.T("Geo_Failed") + failure : L.T("Geo_Done"));
        }
        catch (Exception ex)
        {
            SetStatus(L.T("Geo_Failed") + ex.Message);
        }
        finally
        {
            RefreshFileInfo();
            SetActionText(L.T("Geo_Update"), busy: false);
            _busy = false;
        }
    }

    /// <summary>
    /// Сноска под карточкой существует, только когда ей есть что сказать: пустая строка занимает
    /// высоту строки текста и раздвигает экран невидимым отступом. В эталонном кадре под карточкой
    /// нет ничего — там и не должно быть ничего.
    /// </summary>
    private void SetStatus(string? text)
    {
        txtStatus.Text = text;
        txtStatus.IsVisible = text.IsNotEmpty();
    }

    /// <summary>
    /// Пока идёт загрузка, действие теряет акцент и перестаёт откликаться на тап: акцентный текст
    /// читается как «нажми», а нажимать уже нечего.
    /// </summary>
    private void SetActionText(string text, bool busy)
    {
        foreach (var (action, row) in new[] { (txtGeositeAction, RowGeosite), (txtGeoipAction, RowGeoip) })
        {
            action.Text = text;
            action.Classes.Set("accent", !busy);
            row.Classes.Set("tap", !busy);
        }
    }
}
