using Avalonia.Controls.Primitives;
using Avalonia.Layout;
using ServiceLib.Services.AppUpdate;
using departament.Desktop.Common;

namespace departament.Desktop.Views;

/// <summary>
/// Подтверждение «Перезапустить» — общее для экрана обновления и уведомления в строке окна.
///
/// <para>Перезапуск не делается ни сам, ни одним нажатием: установка закрывает приложение, а с ним рвёт
/// подключение. Поэтому «Перезапустить» открывает флайаут у нажатой строки (00-rules 7.6: флайаут, а
/// не модальное окно) с последствием, сказанным словами, и двумя кнопками. Фокус по умолчанию на «Отмена»:
/// случайный Enter не закроет приложение. Фокус ставится без кольца: кольцо на «Отмена» при открытии
/// выглядело выбранной кнопкой и спорило с «Перезапустить». Кольцо появляется, как только человек
/// начинает ходить по кнопкам клавишей Tab.</para>
/// </summary>
internal static class UpdateRestartFlyout
{
    public static void ShowAt(Control anchor)
    {
        var manager = AppUpdateManager.Instance;
        var offer = manager.State.Offer;
        if (manager.State.Stage != AppUpdateStage.Ready || offer is null)
        {
            return;
        }

        var connected = AppManager.Instance.IsRunningCore(ECoreType.Xray) || AppManager.Instance.IsRunningCore(ECoreType.sing_box);

        var cancel = new Button
        {
            Classes = { "Tonal" },
            Content = L.T("Common_Cancel"),
            HorizontalAlignment = HorizontalAlignment.Stretch,
            Padding = new Thickness(16, 0),
        };
        var restart = new Button
        {
            Classes = { "Primary" },
            Content = L.T("Update_Restart"),
            HorizontalAlignment = HorizontalAlignment.Stretch,
            Padding = new Thickness(16, 0),
        };
        Grid.SetColumn(restart, 2);

        var flyout = new Flyout
        {
            Placement = PlacementMode.BottomEdgeAlignedRight,
            Content = new StackPanel
            {
                Width = 320,
                Spacing = 12,
                Children =
                {
                    new TextBlock { Classes = { "Title" }, Text = L.T("Update_ConfirmTitle"), TextWrapping = TextWrapping.Wrap },
                    new TextBlock
                    {
                        Classes = { "Subtitle" },
                        TextWrapping = TextWrapping.Wrap,
                        Text = connected ? L.T("Update_ConfirmConnected") : L.F("Update_ConfirmIdle", offer.Version.ToString()),
                    },
                    new Grid
                    {
                        Margin = new Thickness(0, 4, 0, 0),
                        ColumnDefinitions = new ColumnDefinitions("*,8,*"),
                        Children = { cancel, restart },
                    },
                },
            },
        };
        if (Application.Current?.TryFindResource("IncyFlyoutTheme", out var theme) == true && theme is ControlTheme presenter)
        {
            flyout.FlyoutPresenterTheme = presenter;
        }

        cancel.Click += (_, _) => flyout.Hide();
        restart.Click += async (_, _) =>
        {
            cancel.IsEnabled = restart.IsEnabled = false;
            // true — пакет передан установщику и приложение уже выходит. false — передать не удалось:
            // флайаут закрывается, причину и «Повторить» показывает состояние.
            if (!await manager.InstallAsync())
            {
                flyout.Hide();
            }
        };
        flyout.Opened += (_, _) => cancel.Focus();
        flyout.ShowAt(anchor);
    }
}
