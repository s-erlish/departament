namespace departament.Desktop.Common;

// ─────────────────────────────────────────────────────────────────────────────
// OWNER: WP0 (Foundation). Shared Common_* keys reused across every screen.
// WP1..WP6 only *reference* these keys via {loc:T Common_*} / L.T("Common_*"):
// they must NOT edit this file. Add screen-specific keys to your own L.<Area>.cs.
// ─────────────────────────────────────────────────────────────────────────────

public sealed partial class L
{
    partial void RegisterCommon()
    {
        // ── Actions / buttons ──
        Add("Common_Back", "Назад", "Back");
        Add("Common_Retry", "Повторить", "Retry");
        Add("Common_Cancel", "Отмена", "Cancel");
        Add("Common_Delete", "Удалить", "Delete");
        Add("Common_Edit", "Изменить", "Edit");
        Add("Common_Add", "Добавить", "Add");
        Add("Common_Copy", "Копировать", "Copy");
        Add("Common_Refresh", "Обновить", "Refresh");
        Add("Common_Manage", "Управление", "Manage");

        // ── Subscription / server actions ──
        Add("Common_AddSubscription", "Добавить подписку", "Add subscription");
        Add("Common_AddFromClipboard", "Добавить из буфера обмена", "Add from clipboard");
        Add("Common_AddViaQr", "Добавить по QR-коду", "Add via QR code");
        Add("Common_TestLatency", "Проверить задержку", "Test latency");

        // ── Auth / commerce ──
        Add("Common_SignInTelegram", "Войти через Telegram", "Sign in with Telegram");
        Add("Common_SignInWebsite", "Войти через сайт", "Sign in via website");
        // Вход по почте ведёт на форму «Вход» ВНУТРИ приложения (почта + пароль, там же регистрация),
        // а не в браузер. «Войти через сайт» остаётся ровно там, где действительно открывается сайт:
        // ссылка на форме «Вход» и схема departamentvpn://auth в «Схемах URL». Та же строка, что на Android
        // (onb_login_site).
        Add("Common_SignInEmail", "Войти по почте", "Sign in with email");
        Add("Common_BuySubscription", "Купить подписку", "Buy subscription");
        Add("Common_PaymentHistory", "История платежей", "Payment history");

        // ── Field / value tokens ──
        Add("Common_Default", "По умолчанию", "Default");
        Add("Common_Custom", "Свой", "Custom");
        Add("Common_On", "Вкл", "On");
        Add("Common_Off", "Выкл", "Off");

        // ── Error / status family (API + connection) ──
        Add("Common_CouldntConnect", "Не удалось подключиться", "Couldn't connect");
        Add("Common_CouldntOpenPayment", "Не удалось открыть страницу оплаты", "Couldn't open the payment page");
        Add("Common_CompletePaymentInBrowser", "Завершите оплату в браузере", "Complete the payment in your browser");
        Add("Common_ServiceUnavailable", "Сервис временно недоступен", "Service is temporarily unavailable");
        Add("Common_NetworkError", "Ошибка сети. Проверьте подключение", "Network error. Check your connection");
        Add("Common_SignInRequired", "Требуется вход в аккаунт", "Sign-in required");
        Add("Common_TooManyRequests", "Слишком много запросов. Попробуйте позже", "Too many requests. Try again later");
        Add("Common_Timeout", "Превышено время ожидания", "Request timed out");
        Add("Common_SomethingWrong", "Что-то пошло не так", "Something went wrong");

        // ── Units / formats (positional templates → use with L.F; arrays → split on ',') ──
        // Лестница единиц объёма, делится на ','. Берут её ByteSize (объём и скорость по всему
        // приложению) и подпись файлов в «Файлах ресурсов».
        Add("Common_ByteUnits", "Б,КБ,МБ,ГБ,ТБ,ПБ", "B,KB,MB,GB,TB,PB");
        // Скорость = объём в секунду: «1,2 МБ/с». Шаблон целиком, а не суффикс «/с», чтобы язык сам
        // решал форму. Собирает ByteSize.Speed.
        Add("Common_PerSecond", "{0}/с", "{0}/s");
        Add("Common_HoursShort", "{0} ч.", "{0} h");
        Add("Common_MinutesShort", "{0} мин.", "{0} min");
        Add("Common_DaysShort", "{0} дн.", "{0} days");

        // ── Plurals (locale-aware, via L.Plural). RU = {one, few, many}; EN = {one, other}. ──
        AddPlural(
            "Common_ServersPlural",
            new[] { "сервер", "сервера", "серверов" },
            new[] { "server", "servers" });
        AddPlural(
            "Common_ProvidersPlural",
            new[] { "провайдер", "провайдера", "провайдеров" },
            new[] { "provider", "providers" });
    }
}
