using Avalonia.Automation;
using CliWrap.Buffered;
using DialogHostAvalonia;
using v2rayN.Desktop.Common;

namespace v2rayN.Desktop.Views;

public partial class SudoPasswordInputView : UserControl
{
    public SudoPasswordInputView()
    {
        InitializeComponent();

        Loaded += (s, e) => txtPassword.Focus();

        btnSave.Click += async (_, _) => await SavePasswordAsync();

        btnCancel.Click += (_, _) =>
        {
            DialogHost.Close(null);
        };

        //  Глаз следит за САМИМ полем, а не за своим флагом: поле само снова прячет пароль, когда в
        //  него ставят фокус (проверено кликом по живому окну), и отдельный флаг рисовал
        //  открытый глаз над точками.
        btnReveal.Click += (_, _) => txtPassword.RevealPassword = !txtPassword.RevealPassword;
        txtPassword.GetObservable(TextBox.RevealPasswordProperty).Subscribe(ShowReveal);

        //  Ошибка относится к уже отвергнутому паролю: как только человек печатает новый, она уходит.
        txtPassword.TextChanged += (_, _) => ShowError(false);
    }

    //  Глаз «показать / скрыть» — тот же рисунок и те же подсказки, что у поля пароля на «Входе».
    private void ShowReveal(bool reveal)
    {
        EyeOnIcon.IsVisible = !reveal;
        EyeOffIcon.IsVisible = reveal;
        ToolTip.SetTip(btnReveal, L.T(reveal ? "Login_HidePassword" : "Login_ShowPassword"));
        AutomationProperties.SetName(btnReveal, L.T(reveal ? "Login_HidePassword" : "Login_ShowPassword"));
    }

    //  Неверный пароль называется ПОД полем, а не всплывающим уведомлением где-то в углу окна:
    //  исправлять его здесь же, и причина должна стоять рядом с местом исправления.
    private void ShowError(bool show)
    {
        txtError.IsVisible = show;
        txtPassword.Classes.Set("fieldError", show);
    }

    private async Task SavePasswordAsync()
    {
        if (txtPassword.Text.IsNullOrEmpty())
        {
            txtPassword.Focus();
            return;
        }

        var password = txtPassword.Text;
        btnSave.IsEnabled = false;

        try
        {
            // Verify if the password is correct
            if (await CheckSudoPasswordAsync(password))
            {
                // Password verification successful, return password and close dialog
                await Dispatcher.UIThread.InvokeAsync(() =>
                {
                    DialogHost.Close(null, password);
                });
            }
            else
            {
                // Password verification failed, display error and let user try again
                ShowError(true);
                txtPassword.SelectAll();
                txtPassword.Focus();
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog("SudoPassword", ex);
        }
        finally
        {
            btnSave.IsEnabled = true;
        }
    }

    private async Task<bool> CheckSudoPasswordAsync(string password)
    {
        try
        {
            // Use sudo echo command to verify password
            var arg = new List<string>() { "-c", "sudo -S echo SUDO_CHECK" };
            var result = await CliWrap.Cli.Wrap(Global.LinuxBash)
                .WithArguments(arg)
                .WithStandardInputPipe(CliWrap.PipeSource.FromString(password))
                .ExecuteBufferedAsync();

            return result.ExitCode == 0;
        }
        catch (Exception ex)
        {
            Logging.SaveLog("CheckSudoPassword", ex);
            return false;
        }
    }
}
