namespace v2rayN.Desktop.Views;

/// <summary>
/// Общий контракт для полноэкранных in-app суб-страниц, живущих в хосте <c>subPageHost</c> оболочки
/// (MainWindow). Раньше эти экраны были отдельными OS-окнами (<c>*Window</c>) — теперь это
/// <see cref="Avalonia.Controls.UserControl"/>, которые кладутся на стек «назад» через
/// <c>MainWindow.OpenSubPage</c>. Страница поднимает <see cref="BackRequested"/> из своего
/// бесшовного тулбара (стрелка «назад») — хост снимает её со стека. Никаких отдельных окон.
/// </summary>
public interface ISubPage
{
    /// <summary>Стрелка «назад» бесшовного тулбара: хост убирает суб-страницу со стека.</summary>
    event EventHandler? BackRequested;
}
