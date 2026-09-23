using System.Reactive.Concurrency;
using ServiceLib.Services.AppUpdate;

namespace ServiceLib.ViewModels;

/// <summary>
/// Экран «Проверить обновление»: состояние самообновления (<see cref="AppUpdateManager"/>) и действия над
/// ним. Логики здесь нет — только проводка: состояние одно на приложение, и то же самое показывает
/// уведомление в окне.
///
/// <para>Раньше модель собирала список «компонентов» (приложение из выпусков апстрима, Xray, mihomo, sing-box,
/// Geo-базы) и по «Обновить» ставила поверх departament программу апстрима и незакреплённые ядра последней
/// версии. Теперь компонент один — сам departament. Ядра едут в выпуске, а Geo-базы обновляются из
/// «Файлов ресурсов».</para>
///
/// <para>Подписка на состояние живёт между <see cref="Attach"/> и <see cref="Detach"/>: экран создаётся
/// на каждое открытие, и вечная подписка на одиночку держала бы в памяти каждый когда-то открытый.</para>
/// </summary>
public class CheckUpdateViewModel : MyReactiveObject
{
    private static readonly string _tag = "CheckUpdateViewModel";

    // Экран, открытый позже этого срока после последнего ответа ленты, спрашивает её заново.
    private static readonly TimeSpan StaleAfter = TimeSpan.FromMinutes(10);

    private readonly AppUpdateManager _manager = AppUpdateManager.Instance;
    private readonly CheckUpdateModel _row = new() { CoreType = ECoreType.departament, IsSelected = true };
    private bool _attached;

    [Reactive] public AppUpdateState State { get; private set; }

    /// <summary>«Искать предварительный выпуск» (CheckUpdateItem.CheckPreReleaseUpdate).</summary>
    [Reactive] public bool EnableCheckPreReleaseUpdate { get; set; }

    public ReactiveCommand<Unit, Unit> CheckCmd { get; }
    public ReactiveCommand<Unit, Unit> DownloadCmd { get; }
    public ReactiveCommand<Unit, Unit> CancelCmd { get; }

    /// <summary>Установка с перезапуском. Только после подтверждения пользователя — это забота экрана.</summary>
    public ReactiveCommand<Unit, bool> InstallCmd { get; }

    // ---- Остались от прежнего представления WPF, которого в departament больше нет: одна строка и две
    // ---- кнопки. Установки оттуда нет вовсе:
    // ---- перезапуск без подтверждения рвал бы подключение.
    public IObservableCollection<CheckUpdateModel> CheckUpdateModels { get; } = new ObservableCollectionExtended<CheckUpdateModel>();
    public ReactiveCommand<Unit, Unit> CheckOnlyCmd => CheckCmd;
    public ReactiveCommand<Unit, Unit> CheckUpdateCmd { get; }

    public CheckUpdateViewModel()
    {
        _config = AppManager.Instance.Config;
        State = _manager.State;
        EnableCheckPreReleaseUpdate = _config.CheckUpdateItem.CheckPreReleaseUpdate;

        CheckCmd = ReactiveCommand.CreateFromTask(() => _manager.CheckAsync(userInitiated: true));
        DownloadCmd = ReactiveCommand.CreateFromTask(_manager.DownloadAsync);
        CancelCmd = ReactiveCommand.Create(_manager.CancelDownload);
        InstallCmd = ReactiveCommand.CreateFromTask(_manager.InstallAsync);
        CheckUpdateCmd = ReactiveCommand.CreateFromTask(() => State.Offer is not null && State.Stage is AppUpdateStage.Available or AppUpdateStage.Failed
            ? _manager.DownloadAsync()
            : _manager.CheckAsync(userInitiated: true));

        foreach (var cmd in new IHandleObservableErrors[] { CheckCmd, DownloadCmd, CancelCmd, InstallCmd, CheckUpdateCmd })
        {
            cmd.ThrownExceptions.Subscribe(ex => Logging.SaveLog(_tag, ex));
        }

        this.WhenAnyValue(x => x.EnableCheckPreReleaseUpdate)
            .Skip(1)
            .Subscribe(async on => await OnPreReleaseChanged(on));

        CheckUpdateModels.Add(_row);
        UpdateRow();
    }

    /// <summary>Экран на виду: подписаться на состояние и взять текущее.</summary>
    public void Attach()
    {
        if (_attached)
        {
            return;
        }
        _attached = true;
        _manager.StateChanged += OnStateChanged;
        State = _manager.State;
        UpdateRow();
    }

    /// <summary>Экран ушёл: отписаться.</summary>
    public void Detach()
    {
        if (!_attached)
        {
            return;
        }
        _attached = false;
        _manager.StateChanged -= OnStateChanged;
    }

    /// <summary>
    /// Открытие экрана — вопрос «есть ли что-нибудь новое», поэтому ленту спрашиваем, если ответа в этом
    /// сеансе ещё не было или он старше <see cref="StaleAfter"/>. Предложение, загрузку и готовый пакет не
    /// трогаем, и сбой уже начатого обновления тоже: его причина должна остаться на экране.
    /// </summary>
    public void CheckIfStale()
    {
        var s = _manager.State;
        var stale = s.CheckedAt is null || DateTime.Now - s.CheckedAt > StaleAfter;
        var idle = s.Stage == AppUpdateStage.Idle
                   || (stale && (s.Stage == AppUpdateStage.UpToDate || s is { Stage: AppUpdateStage.Failed, Offer: null }));
        if (idle)
        {
            CheckCmd.Execute().Subscribe(_ => { }, ex => Logging.SaveLog(_tag, ex));
        }
    }

    private void OnStateChanged(object? sender, AppUpdateState e)
    {
        // Берём САМЫЙ СВЕЖИЙ снимок в момент отрисовки, а не тот, что пришёл с событием: иначе поздно
        // доставленное событие вернуло бы на экран уже прошедшее состояние.
        RxSchedulers.MainThreadScheduler.Schedule(() =>
        {
            State = _manager.State;
            UpdateRow();
        });
    }

    private async Task OnPreReleaseChanged(bool on)
    {
        if (_config.CheckUpdateItem.CheckPreReleaseUpdate == on)
        {
            return;
        }
        _config.CheckUpdateItem.CheckPreReleaseUpdate = on;
        await ConfigHandler.SaveConfig(_config);
        await _manager.OnPreReleaseChangedAsync();
    }

    private void UpdateRow()
    {
        var s = State;
        _row.Remarks = s.Offer is null ? $"{_manager.Running}: {s.Stage}" : $"{_manager.Running} → {s.Offer.Version}: {s.Stage}";
    }
}
