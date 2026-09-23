using v2rayN.Desktop.Common;

namespace v2rayN.Desktop.Views;

/// <summary>
/// «Резервное копирование» — подэкран настроек по единому лекалу (screens.md «Подэкраны»):
/// «Данные» (сохранить копию / восстановить из файла) → «Облако» (настройки WebDAV).
///
/// Real: пакует весь каталог конфигурации в .zip и восстанавливает из него движковым
/// <see cref="BackupAndRestoreViewModel"/> (LocalBackup / LocalRestore). Восстановление сначала
/// делает собственную страховочную копию, затем перезапускает приложение. Ядро не трогается.
///
/// Строка «Настройки WebDAV» кладёт <see cref="BackupAndRestoreView"/> на ТОТ ЖЕ стек оболочки —
/// подэкран поверх подэкрана, без единого отдельного окна. Подпись строки — живое состояние
/// (адрес сервера либо «Не настроено»), поэтому по возврате она перечитывается.
/// Стрелка «назад» поднимает <see cref="BackRequested"/>.
/// </summary>
public partial class BackupPage : UserControl, ISubPage
{
    private readonly BackupAndRestoreViewModel _vm = new();
    private bool _busy;

    public event EventHandler? BackRequested;

    public BackupPage()
    {
        InitializeComponent();

        RefreshWebDavState();

        btnBack.Click += (_, _) => BackRequested?.Invoke(this, EventArgs.Empty);
        RowExport.Tapped += async (_, _) => await ExportAsync();
        RowImport.Tapped += async (_, _) => await ImportAsync();
        RowWebDav.Tapped += (_, _) => OpenWebDav();
    }

    private void OpenWebDav()
    {
        if (TopLevel.GetTopLevel(this) is not MainWindow main)
        {
            return;
        }
        var page = new BackupAndRestoreView();
        // Подпись строки — живое состояние: после правки адреса она обязана перечитаться.
        page.BackRequested += (_, _) => RefreshWebDavState();
        main.OpenSubPage(page);
    }

    /// <summary>Показываем ХОСТ, а не полный адрес: строка узкая, а из «https://…/remote.php/dav/»
    /// опознают сервер по хосту.</summary>
    private void RefreshWebDavState()
    {
        var url = AppManager.Instance.Config.WebDavItem?.Url;
        if (url.IsNullOrEmpty())
        {
            txtWebDavState.Text = L.T("Backup_WebDavNotSet");
            return;
        }
        txtWebDavState.Text = Uri.TryCreate(url, UriKind.Absolute, out var uri) ? uri.Host : url!;
    }

    private async Task ExportAsync()
    {
        if (_busy)
        {
            return;
        }
        var fileName = await UI.SaveFileDialog("Zip|*.zip");
        if (fileName.IsNullOrEmpty())
        {
            return;
        }
        if (!fileName!.EndsWith(".zip", StringComparison.OrdinalIgnoreCase))
        {
            fileName += ".zip";
        }
        _busy = true;
        txtStatus.Text = L.T("Backup_Saving");
        try
        {
            var ok = await _vm.LocalBackup(fileName);
            txtStatus.Text = ok ? L.F("Backup_Saved", fileName) : (_vm.OperationMsg.IsNotEmpty() ? _vm.OperationMsg : L.T("Backup_SaveFailed"));
        }
        catch (Exception ex)
        {
            txtStatus.Text = L.T("Backup_ExportError") + ex.Message;
        }
        finally
        {
            _busy = false;
        }
    }

    private async Task ImportAsync()
    {
        if (_busy)
        {
            return;
        }
        var fileName = await UI.OpenFileDialog(null);
        if (fileName.IsNullOrEmpty())
        {
            return;
        }
        _busy = true;
        txtStatus.Text = L.T("Backup_Restoring");
        try
        {
            // LocalRestore validates the zip, makes a safety backup, then extracts and reboots the app.
            await _vm.LocalRestore(fileName!);
            // If it returns without rebooting, surface any message.
            if (_vm.OperationMsg.IsNotEmpty())
            {
                txtStatus.Text = _vm.OperationMsg;
            }
        }
        catch (Exception ex)
        {
            txtStatus.Text = L.T("Backup_ImportError") + ex.Message;
        }
        finally
        {
            _busy = false;
        }
    }
}
