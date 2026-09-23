using v2rayN.Desktop.ViewModels;

namespace v2rayN.Desktop.Views;

/// <summary>
/// Экран «Устройства»: seamless-тулбар «← Устройства» + счётчик, ОДНА карта со строками устройств
/// подписки (HWID) и отвязкой через in-view подтверждение. Порт Android activity_devices.xml +
/// DeviceManagementActivity.kt. DATA-DRIVEN: всё биндится к <see cref="DevicesViewModel"/>
/// (GET /client/devices departament-API), пусто до реального ответа.
///
/// Самодостаточен: DataContext ставит сам (design-time — образец списка для превьювера).
/// Хост подписывается на <see cref="BackRequested"/>, чтобы закрыть суб-страницу.
/// </summary>
public partial class DevicesView : UserControl
{
    private readonly DevicesViewModel _viewModel;
    private readonly IDisposable? _confirmFocusSub;

    /// <summary>Стрелка «назад» тулбара (и CTA «нет подписки»): хост убирает суб-страницу.</summary>
    public event EventHandler? BackRequested;

    public DevicesView()
    {
        InitializeComponent();
        _viewModel = Design.IsDesignMode ? DevicesViewModel.CreateDesign() : new DevicesViewModel();
        DataContext = _viewModel;

        // Открытие подтверждения — фокус на «Отмена»: Escape/Tab работают сразу с клавиатуры.
        _confirmFocusSub = _viewModel.WhenAnyValue(vm => vm.ShowDeleteConfirm)
            .Subscribe(show =>
            {
                if (show)
                {
                    Dispatcher.UIThread.Post(() => DeleteCancelButton.Focus());
                }
            });
    }

    private void OnBackClick(object? sender, RoutedEventArgs e)
    {
        BackRequested?.Invoke(this, EventArgs.Empty);
    }

    /// <summary>Escape закрывает подтверждение отвязки (пока запрос не в полёте).</summary>
    protected override void OnKeyDown(KeyEventArgs e)
    {
        if (e.Key == Key.Escape && _viewModel.ShowDeleteConfirm)
        {
            _viewModel.CancelDelete();
            e.Handled = true;
            return;
        }
        base.OnKeyDown(e);
    }

    /// <summary>Клик по самому скриму (не по карте) — отмена, стандартная модальная афорданса.</summary>
    private void OnScrimPressed(object? sender, PointerPressedEventArgs e)
    {
        if (ReferenceEquals(e.Source, sender))
        {
            _viewModel.CancelDelete();
            e.Handled = true;
        }
    }

    /// <summary>Отсоединяем статическую подписку VM на AccountSession, чтобы суб-страница не текла.</summary>
    protected override void OnDetachedFromVisualTree(VisualTreeAttachmentEventArgs e)
    {
        _confirmFocusSub?.Dispose();
        _viewModel.Dispose();
        base.OnDetachedFromVisualTree(e);
    }
}
