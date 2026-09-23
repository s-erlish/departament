namespace ServiceLib.Services.AppUpdate;

/// <summary>Где сейчас обновление. Один набор состояний на экран обновления и на уведомление в окне.</summary>
public enum AppUpdateStage
{
    /// <summary>Проверки в этом сеансе ещё не было.</summary>
    Idle,

    Checking,

    /// <summary>Новее нет (или выпусков нет вовсе, см. <see cref="AppUpdateState.NoReleaseYet"/>).</summary>
    UpToDate,

    /// <summary>Есть предложение (<see cref="AppUpdateState.Offer"/>), ждёт «Обновить».</summary>
    Available,

    Downloading,

    /// <summary>Сверка контрольной суммы и состава пакета.</summary>
    Verifying,

    /// <summary>Пакет скачан и проверен, ждёт «Перезапустить» с подтверждением.</summary>
    Ready,

    /// <summary>Пакет передан установщику, приложение выходит.</summary>
    Installing,

    /// <summary>Остановилось, причина в <see cref="AppUpdateState.Failure"/>.</summary>
    Failed,
}

/// <summary>
/// Снимок состояния обновления. Неизменяемый: подписчик получает целый снимок и не может увидеть
/// полусобранное состояние (скажем, «скачивание» без предложения).
/// </summary>
public sealed record AppUpdateState
{
    public AppUpdateStage Stage { get; init; }

    /// <summary>
    /// Предлагаемая версия. Есть с «доступна» до «устанавливаем», и у сбоев загрузки, проверки и установки:
    /// по ней видно, что пользователь уже начал обновление. У сбоя проверки предложения нет.
    /// </summary>
    public AppUpdateOffer? Offer { get; init; }

    /// <summary>Скачано байт (Downloading).</summary>
    public long Received { get; init; }

    /// <summary>Всего байт (Downloading); 0 — сервер длину не назвал.</summary>
    public long Total { get; init; }

    public AppUpdateFailure? Failure { get; init; }

    /// <summary>UpToDate потому, что в ленте нет ни одного выпуска: это ответ, а не сбой.</summary>
    public bool NoReleaseYet { get; init; }

    /// <summary>Когда лента ответила последний раз (местное время).</summary>
    public DateTime? CheckedAt { get; init; }

    /// <summary>Идёт работа, которую экран не должен запускать повторно.</summary>
    public bool IsBusy => Stage is AppUpdateStage.Checking or AppUpdateStage.Downloading
        or AppUpdateStage.Verifying or AppUpdateStage.Installing;
}
