using Avalonia.Animation;
using ServiceLib.Services.AppUpdate;
using departament.Desktop.Common;

namespace departament.Desktop.Views;

/// <summary>
/// Уведомление об обновлении в строке окна (разметка и обоснование места — в UpdateNotice.axaml).
///
/// <para>Нажатие делает следующий шаг того же пути, что и экран «Проверить обновление»: у предложения —
/// «Обновить» (скачать и проверить), у готового — «Перезапустить» с подтверждением во флайауте. Пока
/// идёт загрузка или случился сбой, нажатие открывает экран: там полоса с цифрами, «Отменить» и причина
/// сбоя рядом с «Повторить».</para>
/// </summary>
public partial class UpdateNotice : UserControl
{
    private readonly AppUpdateManager _manager = AppUpdateManager.Instance;

    public UpdateNotice()
    {
        InitializeComponent();

        btnChip.Click += (_, _) => Activate();

        AttachedToVisualTree += (_, _) =>
        {
            _manager.StateChanged += OnStateChanged;
            L.Instance.LanguageChanged += OnLanguageChanged;
            Render();
        };
        DetachedFromVisualTree += (_, _) =>
        {
            _manager.StateChanged -= OnStateChanged;
            L.Instance.LanguageChanged -= OnLanguageChanged;
        };
    }

    /// <summary>
    /// Автопроверка обновлений: первая — через 20–30 с после первого кадра окна, дальше раз в 6 часов,
    /// пока приложение открыто. Вызывает оболочка (MainWindow.OnOpened); повторный вызов ничего не делает.
    /// Проверка не на пути запуска: к моменту первого запроса окно давно нарисовано и список серверов прочитан.
    /// </summary>
    public static void StartAutoCheck()
    {
        var first = TimeSpan.FromSeconds(Random.Shared.Next(20, 31));
#if DEBUG
        // Прогон на машине разработчика не ждёт полминуты (только отладочная сборка, см. AppUpdateChannel.Current).
        if (int.TryParse(Environment.GetEnvironmentVariable("DP_DEV_UPDATE_FIRST_S"), out var seconds) && seconds >= 0)
        {
            first = TimeSpan.FromSeconds(seconds);
        }
        if (DevState(Environment.GetEnvironmentVariable("DP_DEV_UPDATE_STATE")) is { } forced)
        {
            AppUpdateManager.Instance.DevShowState(forced);
            return;
        }
#endif
        AppUpdateManager.Instance.StartSchedule(first, TimeSpan.FromHours(6));
    }

#if DEBUG
    /// <summary>
    /// Только отладочная сборка: <c>DP_DEV_UPDATE_STATE=available|downloading|…</c> рисует экран и чип в
    /// этом состоянии без сети — чтобы каждое состояние можно было снять и посмотреть глазами.
    /// </summary>
    private static AppUpdateState? DevState(string? name)
    {
        if (string.IsNullOrEmpty(name) || !AppVersion.TryParseTag("v1.2.0", out var v) || !AppVersion.TryParseTag("v1.2.0-rc.1", out var rc))
        {
            return null;
        }
        var offer = new AppUpdateOffer(v, "v1.2.0", false, 163_300_000, null);
        var pre = new AppUpdateOffer(rc, "v1.2.0-rc.1", true, 163_300_000, null);
        var now = DateTime.Now;
        AppUpdateState Fail(AppUpdateFailure f, AppUpdateOffer? o = null) =>
            new() { Stage = AppUpdateStage.Failed, Failure = f, Offer = o, CheckedAt = now };
        return name switch
        {
            "checking" => new AppUpdateState { Stage = AppUpdateStage.Checking },
            "uptodate" => new AppUpdateState { Stage = AppUpdateStage.UpToDate, CheckedAt = now },
            "norelease" => new AppUpdateState { Stage = AppUpdateStage.UpToDate, NoReleaseYet = true, CheckedAt = now },
            "available" => new AppUpdateState { Stage = AppUpdateStage.Available, Offer = offer, CheckedAt = now },
            "availablepre" => new AppUpdateState { Stage = AppUpdateStage.Available, Offer = pre, CheckedAt = now },
            "downloading" => new AppUpdateState { Stage = AppUpdateStage.Downloading, Offer = offer, Received = 68_400_000, Total = 163_300_000, CheckedAt = now },
            "verifying" => new AppUpdateState { Stage = AppUpdateStage.Verifying, Offer = offer, CheckedAt = now },
            "ready" => new AppUpdateState { Stage = AppUpdateStage.Ready, Offer = offer, CheckedAt = now },
            "installing" => new AppUpdateState { Stage = AppUpdateStage.Installing, Offer = offer, CheckedAt = now },
            "offline" => Fail(AppUpdateFailure.Offline),
            "unreachable" => Fail(AppUpdateFailure.Unreachable),
            "rate" => Fail(AppUpdateFailure.RateLimited),
            "server" => Fail(AppUpdateFailure.ServerError),
            "platform" => Fail(AppUpdateFailure.UnsupportedPlatform),
            "nopackage" => Fail(AppUpdateFailure.NoPackage),
            "download" => Fail(AppUpdateFailure.DownloadFailed, offer),
            "redirect" => Fail(AppUpdateFailure.ForeignRedirect, offer),
            "checksum" => Fail(AppUpdateFailure.ChecksumMismatch, offer),
            "foreign" => Fail(AppUpdateFailure.BadPackage, offer),
            "installer" => Fail(AppUpdateFailure.InstallerMissing, offer),
            "install" => Fail(AppUpdateFailure.InstallFailed, offer),
            _ => null,
        };
    }
#endif

    private void OnStateChanged(object? sender, AppUpdateState e) => Dispatcher.UIThread.Post(Render);

    private void OnLanguageChanged(object? sender, EventArgs e) => Dispatcher.UIThread.Post(Render);

    private void Activate()
    {
        var s = _manager.State;
        switch (s.Stage)
        {
            case AppUpdateStage.Available:
                _ = _manager.DownloadAsync();
                break;

            case AppUpdateStage.Ready:
                UpdateRestartFlyout.ShowAt(btnChip);
                break;

            default:
                if (TopLevel.GetTopLevel(this) is MainWindow main)
                {
                    main.OpenUpdatePage();
                }
                break;
        }
    }

    private void Render()
    {
        var s = _manager.State;
        var version = s.Offer?.Version.ToString() ?? string.Empty;

        // Чип есть, только пока пользователю есть что делать с найденной версией.
        var (text, glyph, tone, spinner, tip) = s.Offer is null ? (null, null, 0, false, null) : s.Stage switch
        {
            AppUpdateStage.Available => (L.F("Update_ChipAvailable", version), "Geo.Notice.Install", 1, false, L.F("Update_ChipAvailableHint", version)),
            AppUpdateStage.Downloading => (s.Total > 0
                    ? L.F("Update_ChipDownloading", Math.Clamp(s.Received * 100 / s.Total, 0, 100))
                    : L.T("Update_ChipDownloadingPlain"), null, 0, true, (string?)null),
            AppUpdateStage.Verifying => (L.T("Update_ChipVerifying"), null, 0, true, null),
            AppUpdateStage.Ready => (L.T("Update_ChipReady"), "Geo.Notice.Restart", 1, false, L.F("Update_ChipReadyHint", version)),
            AppUpdateStage.Installing => (L.T("Update_ChipInstalling"), null, 0, true, null),
            AppUpdateStage.Failed => (L.T("Update_ChipFailed"), "Geo.Notice.Alert", 2, false, null),
            _ => ((string?)null, (string?)null, 0, false, (string?)null),
        };

        var show = text is not null;
        if (show != IsVisible)
        {
            Reveal(show);
        }
        if (!show)
        {
            return;
        }

        txtChip.Text = text;
        ToolTip.SetTip(btnChip, tip);
        chipBg.Classes.Set("accent", tone == 1);
        chipBg.Classes.Set("error", tone == 2);
        icoChip.IsVisible = !spinner;
        if (glyph is not null && this.TryFindResource(glyph, out var data) && data is Geometry geometry)
        {
            icoChip.Data = geometry;
        }
        spinChip.IsVisible = spinner;
        spinChip.Classes.Set("spinning", spinner);
    }

    /// <summary>
    /// Появление — проявлением за Dur.Reveal (300 мс, OutQuint), уход — мгновенно: чип уходит, когда его
    /// работа сделана, и провожать его взглядом незачем. В облегчённом режиме — без движения.
    /// </summary>
    private void Reveal(bool show)
    {
        if (!show)
        {
            IsVisible = false;
            return;
        }
        if (MotionState.IsLite)
        {
            Transitions = null;
            Opacity = 1;
            IsVisible = true;
            return;
        }
        Transitions = null;
        Opacity = 0;
        IsVisible = true;
        Transitions = [new DoubleTransition { Property = OpacityProperty, Duration = Motion.Dur.Reveal, Easing = Motion.Ease.OutQuint }];
        Opacity = 1;
    }
}
