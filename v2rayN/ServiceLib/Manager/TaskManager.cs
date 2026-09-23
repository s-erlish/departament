namespace ServiceLib.Manager;

public class TaskManager
{
    private static readonly Lazy<TaskManager> _instance = new(() => new());
    public static TaskManager Instance => _instance.Value;
    private Config _config;
    private Func<bool, string, Task>? _updateFunc;

    public void RegUpdateTask(Config config, Func<bool, string, Task> updateFunc)
    {
        _config = config;
        _updateFunc = updateFunc;

        Task.Run(ScheduledTasks);
    }

    private async Task ScheduledTasks()
    {
        Logging.SaveLog("Setup Scheduled Tasks");

        var numOfExecuted = 1;
        while (true)
        {
            //1 minute
            await Task.Delay(1000 * 60);

            //Execute once 1 minute
            try
            {
                await UpdateTaskRunSubscription();
            }
            catch (Exception ex)
            {
                Logging.SaveLog("ScheduledTasks - UpdateTaskRunSubscription", ex);
            }

            //Execute once 20 minute
            if (numOfExecuted % 20 == 0)
            {
                //Logging.SaveLog("Execute save config");

                try
                {
                    await ConfigHandler.SaveConfig(_config);
                    await ProfileExManager.Instance.SaveTo();
                }
                catch (Exception ex)
                {
                    Logging.SaveLog("ScheduledTasks - SaveConfig", ex);
                }
            }

            //Execute once 1 hour
            if (numOfExecuted % 60 == 0)
            {
                //Logging.SaveLog("Execute delete expired files");

                FileUtils.DeleteExpiredFiles(Utils.GetBinConfigPath(), DateTime.Now.AddHours(-1), "Test");
                FileUtils.DeleteExpiredFiles(Utils.GetLogPath(), DateTime.Now.AddMonths(-1));
                FileUtils.DeleteExpiredFiles(Utils.GetTempPath(), DateTime.Now.AddMonths(-1));

                try
                {
                    await UpdateTaskRunGeo(numOfExecuted / 60);
                }
                catch (Exception ex)
                {
                    Logging.SaveLog("ScheduledTasks - UpdateTaskRunGeo", ex);
                }
            }

            // Проверки новых ядер здесь нет и не будет: ядра едут вместе с приложением и закреплены в
            // выпуске. Проверка новой версии самого приложения — AppUpdateManager.StartSchedule: её
            // запускает оболочка после первого кадра окна, а не этот цикл.
            numOfExecuted++;
        }
    }

    private async Task UpdateTaskRunSubscription()
    {
        var updateTime = ((DateTimeOffset)DateTime.Now).ToUnixTimeSeconds();
        //  Своя частота у подписки — если задана; иначе общая из «Настройки → Автообновление подписки»
        //  (GuiItem.AutoUpdateInterval, минуты). Раньше общая строка настроек сюда не доходила вовсе:
        //  своей частоты у подписок нет, и подписка сама не обновлялась никогда, что бы там ни стояло.
        //  Обновление безопасно для подключения: ядро перезапускается, только если подключённый сервер
        //  правда изменился (MainWindowViewModel.UpdateTaskHandler).
        var globalInterval = _config.GuiItem.AutoUpdateInterval;
        var lstSubs = (await AppManager.Instance.SubItems())?
            .Where(t => t.Enabled && t.Url.IsNotEmpty())
            .Select(t => (Item: t, Interval: t.AutoUpdateInterval > 0 ? t.AutoUpdateInterval : globalInterval))
            .Where(t => t.Interval > 0 && updateTime - t.Item.UpdateTime >= t.Interval * 60)
            .Select(t => t.Item)
            .ToList();

        if (lstSubs is not { Count: > 0 })
        {
            return;
        }

        Logging.SaveLog("Execute update subscription");

        foreach (var item in lstSubs)
        {
            await SubscriptionHandler.UpdateProcess(_config, item.Id, true, async (success, msg) =>
            {
                await _updateFunc?.Invoke(success, msg);
                if (success)
                {
                    Logging.SaveLog($"Update subscription end. {msg}");
                }
            });
            //  Отмечаем попытку ОДНИМ полем. Раньше сюда писалась вся запись, прочитанная ДО скачивания,
            //  поверх свежей: она затирала новое имя провайдера и ссылку, записанные синхронизацией
            //  аккаунта, а после выхода из аккаунта посреди скачивания возвращала удалённую подписку.
            //  Если записи уже нет, UPDATE просто ничего не находит.
            await SQLiteHelper.Instance.ExecuteAsync("update SubItem set UpdateTime = ? where Id = ?", updateTime, item.Id);
            await Task.Delay(1000);
        }
    }

    private async Task UpdateTaskRunGeo(int hours)
    {
        if (_config.GuiItem.AutoUpdateInterval > 0 && hours > 0 && hours % _config.GuiItem.AutoUpdateInterval == 0)
        {
            Logging.SaveLog("Execute update geo files");

            await new UpdateService(_config, async (success, msg) =>
            {
                await _updateFunc?.Invoke(false, msg);
            }).UpdateGeoFileAll();
        }
    }
}
