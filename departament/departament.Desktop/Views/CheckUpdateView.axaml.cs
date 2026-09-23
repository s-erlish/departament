using ServiceLib.Services.AppUpdate;
using departament.Desktop.Common;

namespace departament.Desktop.Views;

/// <summary>
/// «Проверить обновление» — подэкран настроек по единому лекалу: карточка «что сейчас и что дальше» →
/// «Искать предварительный выпуск» → сноска с версией и временем проверки.
///
/// <para>Под экраном — <see cref="CheckUpdateViewModel"/>, то есть общее состояние самообновления
/// (<see cref="AppUpdateManager"/>): загрузка, начатая из уведомления в строке окна, видна здесь с тем же
/// прогрессом. Экран каждый раз рисует состояние целиком (<see cref="Render"/>): одна точка, где
/// состояние становится словами, — и ни одна строка не может остаться от прошлого состояния.</para>
///
/// <para>Раньше здесь были список «компонентов» (приложение, Xray, mihomo, sing-box, Geo-базы) и две
/// вечные строки «Проверить» и «Обновить сейчас»; «Обновить» поставил бы поверх departament программу
/// апстрима и незакреплённые ядра. Ядра теперь едут в выпуске, Geo-базы обновляются из «Файлов ресурсов».</para>
/// </summary>
public partial class CheckUpdateView : ReactiveUserControl<CheckUpdateViewModel>, ISubPage
{
    private enum Tone
    {
        Neutral,
        Accent,
        Error,
    }

    /// <summary>Что показать для состояния: плитка, слова, основное действие и нужна ли страница загрузки.</summary>
    private sealed record Look(
        string Glyph,
        Tone Tone,
        bool Spinner,
        string Title,
        string? Line,
        string? Action,
        string ActionGlyph,
        bool ActionAccent,
        bool ActionEnabled,
        Action? Run,
        bool ReleasesPage);

    private Action? _run;
    private bool _runEnabled;
    private double _fraction = -1;
    private IDisposable? _coreStateSub;

    public event EventHandler? BackRequested;

    public CheckUpdateView()
    {
        InitializeComponent();

        // Подэкран создаётся оболочкой напрямую (new CheckUpdateView()), поэтому модель заводим сами.
        ViewModel ??= new CheckUpdateViewModel();

        btnBack.Click += (_, _) => BackRequested?.Invoke(this, EventArgs.Empty);

        WireRow(RowAction, () =>
        {
            if (_runEnabled)
            {
                _run?.Invoke();
            }
        });
        WireRow(RowSecondary, OpenReleasesPage);
        // Тап по строке-тумблеру переключает тумблер — но не когда источником тапа был он сам.
        WireRow(RowPreRelease, () => togEnableCheckPreReleaseUpdate.IsChecked = togEnableCheckPreReleaseUpdate.IsChecked != true,
            skipToggle: true);

        Meter.SizeChanged += (_, _) => ApplyFraction();

        this.WhenActivated(disposables =>
        {
            this.Bind(ViewModel, vm => vm.EnableCheckPreReleaseUpdate, v => v.togEnableCheckPreReleaseUpdate.IsChecked).DisposeWith(disposables);
            ViewModel.WhenAnyValue(vm => vm.State).Subscribe(_ => Render()).DisposeWith(disposables);
        });

        // Подписка на общее состояние — только пока экран на виду (см. CheckUpdateViewModel.Attach).
        AttachedToVisualTree += (_, _) =>
        {
            ViewModel?.Attach();
            L.Instance.LanguageChanged += OnLanguageChanged;
            // Строка «готово» говорит о подключении: подключились или отключились, пока экран открыт, —
            // перерисовать. Событие приходит с фонового потока.
            _coreStateSub ??= AppEvents.CoreRunningStateChanged.AsObservable()
                .Subscribe(_ => Dispatcher.UIThread.Post(Render));
            Render();
            ViewModel?.CheckIfStale();
        };
        DetachedFromVisualTree += (_, _) =>
        {
            ViewModel?.Detach();
            L.Instance.LanguageChanged -= OnLanguageChanged;
            _coreStateSub?.Dispose();
            _coreStateSub = null;
        };
    }

    private void OnLanguageChanged(object? sender, EventArgs e) => Dispatcher.UIThread.Post(Render);

    /// <summary>Строка-действие: тап и Enter/Space с фокуса — один и тот же жест (клавиатура обязательна, 00-rules 14.8).</summary>
    private static void WireRow(Border row, Action activate, bool skipToggle = false)
    {
        row.Focusable = true;
        row.IsTabStop = true;
        row.Tapped += (_, e) =>
        {
            if (!skipToggle || !SubPageUtil.OriginatedIn<ToggleSwitch>(e.Source))
            {
                activate();
            }
        };
        row.KeyDown += (_, e) =>
        {
            if (e.Key is Key.Enter or Key.Space)
            {
                activate();
                e.Handled = true;
            }
        };
    }

    // ==================== Состояние → экран ====================

    private void Render()
    {
        if (ViewModel is null)
        {
            return;
        }
        var s = ViewModel.State;
        var look = LookOf(s);

        // Плитка: глиф или круг на его месте (motion.md «на месте иконки вращается круг»).
        icoStatus.Data = FindGlyph(look.Glyph);
        icoStatus.IsVisible = !look.Spinner;
        spinStatus.IsVisible = look.Spinner;
        spinStatus.Classes.Set("spinning", look.Spinner);
        tileStatus.Classes.Set("accent", look.Tone == Tone.Accent);
        tileStatus.Classes.Set("error", look.Tone == Tone.Error);

        txtStatusTitle.Text = look.Title;
        txtStatusLine.Text = look.Line;
        txtStatusLine.IsVisible = !string.IsNullOrEmpty(look.Line);

        RenderProgress(s);

        // Основное действие. Нет действия — нет и строки с разделителем: пустая строка обещала бы шаг,
        // которого нет.
        var hasAction = look.Action is not null;
        RowAction.IsVisible = DividerAction.IsVisible = hasAction;
        _run = look.Run;
        _runEnabled = look.ActionEnabled && look.Run is not null;
        txtAction.Text = look.Action;
        icoAction.Data = FindGlyph(look.ActionGlyph);
        txtAction.Classes.Set("accent", look.ActionAccent && _runEnabled);
        RowAction.Classes.Set("tap", _runEnabled);
        RowAction.Classes.Set("idle", !_runEnabled);
        RowAction.IsTabStop = RowAction.Focusable = _runEnabled;

        RowSecondary.IsVisible = DividerSecondary.IsVisible = look.ReleasesPage;

        // Пока приложение уже выходит в установку, переключать канал поздно.
        RowPreRelease.IsEnabled = s.Stage != AppUpdateStage.Installing;

        txtFoot.Text = Foot(s);
    }

    private Look LookOf(AppUpdateState s)
    {
        var vm = ViewModel!;
        var running = AppUpdateManager.Instance.Running.ToString();
        var offer = s.Offer?.Version.ToString() ?? string.Empty;
        Action check = () => Execute(vm.CheckCmd);
        Action download = () => Execute(vm.DownloadCmd);
        Action cancel = () => Execute(vm.CancelCmd);
        Action restart = () => UpdateRestartFlyout.ShowAt(RowAction);

        Look Retry(string title, string line, bool withDownload = false, bool page = false) =>
            new("Geo.Update.Alert", Tone.Error, false, title, line,
                L.T("Common_Retry"), "Geo.Sub.Refresh", true, true, withDownload && s.Offer is not null ? download : check, page);

        Look Page(string glyph, Tone tone, string title, string line) =>
            new(glyph, tone, false, title, line, L.T("Update_OpenReleases"), "Geo.Update.Open", true, true, OpenReleasesPage, false);

        return s.Stage switch
        {
            AppUpdateStage.Idle => new("Geo.Update.Schedule", Tone.Neutral, false, L.F("Update_IdleTitle", running), L.T("Update_IdleLine"),
                L.T("Update_Check"), "Geo.Sub.Refresh", true, true, check, false),

            AppUpdateStage.Checking => new("Geo.Sub.Refresh", Tone.Neutral, true, L.T("Update_CheckingTitle"), L.F("Update_CheckingLine", running),
                L.T("Update_Check"), "Geo.Sub.Refresh", true, false, null, false),

            AppUpdateStage.UpToDate => new("Geo.Sub.Check", Tone.Neutral, false, L.T("Update_NoneTitle"),
                s.NoReleaseYet ? L.T("Update_NoReleaseLine") : L.F("Update_NoneLine", running),
                L.T("Update_Check"), "Geo.Sub.Refresh", true, true, check, false),

            AppUpdateStage.Available => new("Geo.Update.Newer", Tone.Accent, false,
                s.Offer!.IsPreRelease ? L.F("Update_FoundPreTitle", offer) : L.F("Update_FoundTitle", offer), L.T("Update_FoundLine"),
                L.T("Update_Download"), "Geo.Update.Install", true, true, download, false),

            // Отмена — тихая строка без акцента: главное на экране сейчас полоса, а не выход из неё.
            AppUpdateStage.Downloading => new("Geo.Update.Install", Tone.Neutral, false, L.F("Update_DownloadingTitle", offer), null,
                L.T("Update_Cancel"), "Geo.Update.Cancel", false, true, cancel, false),

            AppUpdateStage.Verifying => new("Geo.Update.Install", Tone.Neutral, true, L.T("Update_VerifyingTitle"), L.T("Update_VerifyingLine"),
                L.T("Update_Cancel"), "Geo.Update.Cancel", false, true, cancel, false),

            AppUpdateStage.Ready => new("Geo.Update.Done", Tone.Accent, false, L.F("Update_ReadyTitle", offer), ReadyLine(),
                L.T("Update_Restart"), "Geo.Update.Restart", true, true, restart, false),

            AppUpdateStage.Installing => new("Geo.Update.Done", Tone.Neutral, true, L.T("Update_InstallingTitle"), L.T("Update_InstallingLine"),
                null, "Geo.Update.Restart", false, false, null, false),

            _ => s.Failure switch
            {
                // Нет сети — не поломка приложения: тон спокойный, глиф говорит сам.
                AppUpdateFailure.Offline => new("Geo.Update.Offline", Tone.Neutral, false, L.T("Update_ErrOfflineTitle"), L.T("Update_ErrOfflineLine"),
                    L.T("Common_Retry"), "Geo.Sub.Refresh", true, true, s.Offer is not null ? download : check, false),
                AppUpdateFailure.Unreachable => Retry(L.T("Update_ErrCheckTitle"), L.T("Update_ErrUnreachableLine")),
                AppUpdateFailure.RateLimited => Retry(L.T("Update_ErrCheckTitle"), L.T("Update_ErrRateLine")),
                AppUpdateFailure.UnsupportedPlatform => Page("Geo.Update.Info", Tone.Neutral, L.T("Update_ErrUnavailableTitle"), L.T("Update_ErrPlatformLine")),
                // Нового пакета нет — повтор ответа не изменит, пока не выйдет следующая сборка; «Проверить
                // обновление» остаётся, как у «обновлений нет».
                AppUpdateFailure.NoPackage => new("Geo.Update.Info", Tone.Neutral, false, L.T("Update_ErrUnavailableTitle"), L.T("Update_ErrNoPackageLine"),
                    L.T("Update_Check"), "Geo.Sub.Refresh", true, true, check, false),
                AppUpdateFailure.DownloadFailed => Retry(L.T("Update_ErrDownloadTitle"), L.T("Update_ErrDownloadLine"), withDownload: true),
                AppUpdateFailure.ChecksumMismatch => Retry(L.T("Update_ErrChecksumTitle"), L.T("Update_ErrChecksumLine"), withDownload: true),
                AppUpdateFailure.ForeignRedirect => Page("Geo.Update.Alert", Tone.Error, L.T("Update_ErrRedirectTitle"), L.T("Update_ErrRedirectLine")),
                AppUpdateFailure.BadPackage => Page("Geo.Update.Alert", Tone.Error, L.T("Update_ErrForeignTitle"), L.T("Update_ErrForeignLine")),
                AppUpdateFailure.InstallerMissing => Page("Geo.Update.Alert", Tone.Error, L.T("Update_ErrInstallerTitle"), L.T("Update_ErrInstallerLine")),
                AppUpdateFailure.InstallFailed => Retry(L.T("Update_ErrInstallTitle"), L.F("Update_ErrInstallLine", running), withDownload: true, page: true),
                _ => Retry(L.T("Update_ErrCheckTitle"), L.T("Update_ErrServerLine")),
            },
        };
    }

    /// <summary>
    /// Строка под «Обновление готово» — по настоящему подключению, как и подтверждение: без подключения
    /// о нём ни слова, с подключением — что оно прервётся и вернётся само (UpdateReconnect). Экран
    /// перерисовывается на каждом подключении и отключении.
    /// </summary>
    private static string ReadyLine() =>
        AppManager.Instance.IsRunningCore(ECoreType.Xray) || AppManager.Instance.IsRunningCore(ECoreType.sing_box)
            ? L.T("Update_ReadyLineConnected")
            : L.T("Update_ReadyLine");

    /// <summary>
    /// Полоса и цифры загрузки. Длину сервер назвал — доля и «12,4 МБ из 38,1 МБ»; не назвал — полоса
    /// без заливки и «Скачано 12,4 МБ»: процент без знаменателя был бы украшением, а не прогрессом.
    /// </summary>
    private void RenderProgress(AppUpdateState s)
    {
        var downloading = s.Stage == AppUpdateStage.Downloading;
        ProgressBlock.IsVisible = downloading;
        if (!downloading)
        {
            _fraction = -1;
            return;
        }
        if (s.Total > 0)
        {
            _fraction = Math.Clamp((double)s.Received / s.Total, 0, 1);
            txtProgress.Text = L.F("Update_DownloadingSize", Megabytes(s.Received), Megabytes(s.Total));
        }
        else
        {
            _fraction = 0;
            txtProgress.Text = L.F("Update_DownloadingDone", Megabytes(s.Received));
        }
        ApplyFraction();
    }

    private void ApplyFraction()
    {
        MeterFill.Width = _fraction < 0 ? 0 : Math.Round(Meter.Bounds.Width * _fraction);
    }

    private static string Megabytes(long bytes) =>
        (bytes / 1024d / 1024d).ToString("0.0", CultureInfo.CurrentUICulture);

    /// <summary>«departament 1.2.0 · проверено в 14:32»: версия всегда, время — когда лента ответила.</summary>
    private static string Foot(AppUpdateState s)
    {
        var running = AppUpdateManager.Instance.Running.ToString();
        if (s.CheckedAt is not { } at)
        {
            return L.F("Update_Foot", running);
        }
        return at.Date == DateTime.Today
            ? L.F("Update_FootToday", running, at.ToString("HH:mm", CultureInfo.CurrentUICulture))
            : L.F("Update_FootDate", running, at.ToString("dd.MM.yyyy", CultureInfo.CurrentUICulture));
    }

    private Geometry? FindGlyph(string key) =>
        this.TryFindResource(key, out var value) ? value as Geometry : null;

    private static void Execute<TOut>(ReactiveCommand<Unit, TOut> command) =>
        command.Execute().Subscribe(_ => { }, ex => Logging.SaveLog("CheckUpdateView", ex));

    private static void OpenReleasesPage()
    {
        ProcUtils.TryProcessStart(AppUpdateManager.Instance.Channel.ReleasesPageUrl);
    }
}
