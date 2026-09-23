namespace v2rayN.Desktop.Base;

public class WindowBase<TViewModel> : ReactiveWindow<TViewModel> where TViewModel : class
{
    public WindowBase()
    {
        Loaded += OnLoaded;
        Loaded += (s, e) =>
        {
            if (Owner != null && !ShowInTaskbar)
            {
                CanMinimize = false;
            }
        };
    }

    protected virtual void OnLoaded(object? sender, RoutedEventArgs e)
    {
        try
        {
            var screen = Screens.ScreenFromWindow(this) ?? Screens.Primary;
            if (screen is null)
            {
                return;
            }
            var scaling = screen.Scaling > 0 ? screen.Scaling : 1.0;
            var workingArea = screen.WorkingArea;

            // Желаемый размер: сохранённый (если есть) ИЛИ текущие дефолты XAML (компакт 310×630).
            // ВСЕГДА клампим в рабочую область экрана — окно борелесс (WindowDecorations=None), и на
            // ноутбуке 1366×768 высокое окно центрировалось с y<0, унося кастомный заголовок
            // (close/maximize) за верх экрана. Гарантируем: размер ≤ рабочей области, а верх окна
            // никогда не выше её (y ≥ workingArea.Y), поэтому title-bar всегда на экране.
            // Раскладка адаптивна, поэтому проектный потолок 1120×760 НЕ навязываем — только
            // границу рабочей области. Развёрнутый/макс. размер не персистится (OnClosed: только Normal).
            var sizeItem = ConfigHandler.GetWindowSizeItem(AppManager.Instance.Config, GetType().Name);
            var desiredWidth = sizeItem?.Width ?? Width;
            var desiredHeight = sizeItem?.Height ?? Height;

            var maxWidth = workingArea.Width / scaling;
            var maxHeight = workingArea.Height / scaling;
            var minWidth = Math.Min(MinWidth, maxWidth);
            var minHeight = Math.Min(MinHeight, maxHeight);
            var width = Math.Clamp(desiredWidth, minWidth, maxWidth);
            var height = Math.Clamp(desiredHeight, minHeight, maxHeight);

            var physW = width * scaling;
            var physH = height * scaling;
            var x = workingArea.X + Math.Max(0, (workingArea.Width - physW) / 2);
            var y = workingArea.Y + Math.Max(0, (workingArea.Height - physH) / 2);
            x = Math.Max(workingArea.X, Math.Min(x, workingArea.X + workingArea.Width - physW));
            y = Math.Max(workingArea.Y, Math.Min(y, workingArea.Y + workingArea.Height - physH));

            Width = width;
            Height = height;
            Position = new PixelPoint((int)x, (int)y);
        }
        catch { }
    }

    protected override void OnClosed(EventArgs e)
    {
        base.OnClosed(e);
        try
        {
            // Не персистим развёрнутый/свёрнутый размер — только обычное состояние окна,
            // иначе следующий запуск восстанавливается «на весь экран».
            if (WindowState != WindowState.Normal)
            {
                return;
            }
            ConfigHandler.SaveWindowSizeItem(AppManager.Instance.Config, GetType().Name, Width, Height);
        }
        catch { }
    }
}
