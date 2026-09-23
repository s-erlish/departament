using v2rayN.Desktop.Common;

namespace v2rayN.Desktop.Views;

/// <summary>
/// «Настройки WebDAV» — подэкран настроек по единому лекалу (screens.md «Подэкраны», группа
/// «Облако» на экране резервного копирования). Открывается строкой «Настройки WebDAV» из
/// <see cref="BackupPage"/> и ложится на ТОТ ЖЕ стек «назад» оболочки.
///
/// Раньше это было легаси-представление v2rayN: англоязычный ResUI, сетка 300×200 с кнопками и
/// вложенный flyout с четырьмя полями. Разметка переписана, ЛОГИКА НЕ ДУБЛИРУЕТСЯ — под экраном тот
/// же движковый <see cref="BackupAndRestoreViewModel"/> (WebDavCheck / RemoteBackup / RemoteRestore),
/// а адрес, логин, пароль и папка по-прежнему двусторонне связаны с <c>SelectedSource</c>.
///
/// Локальные «сохранить копию» и «восстановить из файла» отсюда УБРАНЫ: они живут строками группы
/// «Данные» на <see cref="BackupPage"/>. Два входа в одно и то же действие на соседних экранах —
/// это не дублирование кода, а дублирование решения для пользователя.
/// </summary>
public partial class BackupAndRestoreView : ReactiveUserControl<BackupAndRestoreViewModel>, ISubPage
{
    private bool _busy;

    public event EventHandler? BackRequested;

    public BackupAndRestoreView()
    {
        InitializeComponent();

        // Раньше модель приходила только через DataContext локатора. Подэкран создаётся оболочкой
        // напрямую (new BackupAndRestoreView()), поэтому модель заводим сами — иначе поля пустые.
        ViewModel ??= new BackupAndRestoreViewModel();

        btnBack.Click += async (_, _) =>
        {
            await PersistAsync();
            BackRequested?.Invoke(this, EventArgs.Empty);
        };

        RowCheck.Tapped += (_, _) => Run(ViewModel.WebDavCheckCmd);
        RowUpload.Tapped += (_, _) => Run(ViewModel.RemoteBackupCmd);
        RowDownload.Tapped += (_, _) => Run(ViewModel.RemoteRestoreCmd);

        this.WhenActivated(disposables =>
        {
            this.Bind(ViewModel, vm => vm.OperationMsg, v => v.txtMsg.Text).DisposeWith(disposables);

            this.Bind(ViewModel, vm => vm.SelectedSource.Url, v => v.txtWebDavUrl.Text).DisposeWith(disposables);
            this.Bind(ViewModel, vm => vm.SelectedSource.UserName, v => v.txtWebDavUserName.Text).DisposeWith(disposables);
            this.Bind(ViewModel, vm => vm.SelectedSource.Password, v => v.txtWebDavPassword.Text).DisposeWith(disposables);
            this.Bind(ViewModel, vm => vm.SelectedSource.DirName, v => v.txtWebDavDirName.Text).DisposeWith(disposables);
        });
    }

    /// <summary>Введённое сохраняется при уходе с экрана, а не только по «Проверить подключение»:
    /// движковая модель пишет SelectedSource в конфиг лишь внутри WebDavCheck, и адрес, набранный без
    /// проверки, терялся — строка «Настройки WebDAV» на экране копирования так и говорила
    /// «Не настроено». Экран настроек не имеет права терять введённое.</summary>
    private async Task PersistAsync()
    {
        var config = AppManager.Instance.Config;
        config.WebDavItem = ViewModel!.SelectedSource;
        await ConfigHandler.SaveConfig(config);
    }

    /// <summary>Пока команда идёт, действия теряют акцент и не откликаются: акцентный текст читается
    /// как «нажми», а нажимать в этот момент нечего (то же правило, что в «Файлах ресурсов»).</summary>
    private void Run(ReactiveCommand<Unit, Unit> cmd)
    {
        if (_busy)
        {
            return;
        }
        _busy = true;
        SetBusy(true);
        txtMsg.Text = L.T("Backup_Working");

        cmd.Execute().Subscribe(
            _ => { },
            _ => Done(),
            Done);

        void Done() => Dispatcher.UIThread.Post(() =>
        {
            _busy = false;
            SetBusy(false);
        });
    }

    private void SetBusy(bool busy)
    {
        foreach (var (text, row) in new[] { (txtCheck, RowCheck), (txtUpload, RowUpload), (txtDownload, RowDownload) })
        {
            text.Classes.Set("accent", !busy);
            row.Classes.Set("tap", !busy);
        }
    }
}
