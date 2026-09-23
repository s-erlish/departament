namespace v2rayN.Desktop.Common;

// ─────────────────────────────────────────────────────────────────────────────
// OWNER: блок 7. Начальный экран + экран прогрузки.  Keys: Start_* / Flow_*.
// Views: OnboardingView(.axaml/.cs), AccountSyncView(.axaml/.cs).
//
// ПОЧЕМУ ОТДЕЛЬНЫЙ ФАЙЛ, А НЕ ДОПИСКА В L.Home.cs / L.Common.cs: L.cs прямо говорит, что
// таблица разбита по областям, «чтобы параллельные пакеты работ никогда не правили один файл».
// L.Home.cs принадлежит «Главной», L.Account.cs «Аккаунту», оба сейчас в работе у соседних
// агентов. Свой партиал это единственный способ добавить строки, не встав в чужой файл.
//
// Строки чужих областей, которые эти два экрана ПЕРЕИСПОЛЬЗУЮТ (здесь НЕ определяются):
//   • Home_Welcome («Приветствуем!») в L.Home.cs;
//   • Common_AddSubscription / Common_AddFromClipboard / Common_AddViaQr /
//     Common_SignInTelegram / Common_SignInEmail в L.Common.cs. Все пять уже несут ровно тот
//     текст, что стоит в screens.md, поэтому дублировать их своими ключами было бы вторым
//     источником правды.
// ─────────────────────────────────────────────────────────────────────────────

public sealed partial class L
{
    partial void RegisterStart()
    {
        // ==================== Начальный экран (screens.md «Начальный экран») ====================

        // Подзаголовок под «Приветствуем!». Один текст на оба варианта экрана (пустой буфер и
        // найденная ссылка): при найденной ссылке ВЫШЕ появляется карточка, текст не меняется.
        Add(
            "Start_Subtitle",
            "Войдите в аккаунт, и подписка добавится сама. Или вставьте ссылку из буфера обмена.",
            "Sign in and your subscription arrives on its own. Or paste a link from the clipboard.");

        // Карточка найденной ссылки: заголовок + пояснение (CTA это Common_AddSubscription).
        Add("Start_ClipCardTitle", "Подписка", "Subscription");
        Add("Start_ClipCardNote", "Ссылка в буфере обмена", "Link in the clipboard");

        // Текстовая кнопка с кареткой, раскрывающая QR и вход через сайт.
        Add("Start_MoreWays", "Другие способы", "Other ways");

        // ==================== Экран прогрузки (screens.md «Экран прогрузки») ====================
        // Два набора по четыре шага: вход через Telegram и добавление из буфера обмена.
        // Имя подписки не показывается НИГДЕ: ни в заголовке, ни в пояснении, ни в тосте.

        // Поток «Войти через Telegram».
        Add("Flow_TgTitle0", "Открываем Telegram", "Opening Telegram");
        Add("Flow_TgNote0", "Подтвердите вход в приложении", "Confirm the sign-in in the app");
        Add("Flow_TgTitle1", "Проверяем вход", "Checking the sign-in");
        Add("Flow_TgNote1", "Сверяем аккаунт с сервером", "Matching your account with the server");
        Add("Flow_TgTitle2", "Добавляем подписку", "Adding the subscription");
        Add("Flow_TgNote2", "Почти готово", "Almost done");
        Add("Flow_TgTitle3", "Подписка добавлена", "Subscription added");
        Add("Flow_TgNote3", "Аккаунт привязан", "Account linked");

        // Поток «Добавить из буфера обмена».
        Add("Flow_ClipTitle0", "Читаем буфер обмена", "Reading the clipboard");
        Add("Flow_ClipNote0", "Ищем ссылку на подписку", "Looking for a subscription link");
        Add("Flow_ClipTitle1", "Нашли подписку", "Subscription found");
        Add("Flow_ClipNote1", "Загружаем список серверов", "Loading the server list");
        Add("Flow_ClipTitle2", "Проверяем серверы", "Checking the servers");
        Add("Flow_ClipNote2", "Измеряем задержку до каждого", "Measuring the latency to each");
        Add("Flow_ClipTitle3", "Подписка добавлена", "Subscription added");
        Add("Flow_ClipNote3", "Серверы готовы", "Servers ready");

        // Тост шага 3, свой на поток.
        Add("Flow_ToastTg", "Аккаунт привязан", "Account linked");
        Add("Flow_ToastClip", "Подписка добавлена", "Subscription added");

        // Вход не дошёл: истекли три минуты ожидания, ссылка входа устарела (410) или сервер её не
        // принял (401). Это НЕ «не удалось синхронизировать»: аккаунта ещё нет, и «Войти заново»
        // было бы не про что; вторая кнопка ветки в этом случае ведёт назад, на начальный экран.
        Add("Flow_LoginErrorTitle", "Не удалось войти", "Sign-in didn't finish");
        Add("Flow_LoginErrorHint",
            "Подтверждение из Telegram так и не пришло. Попробуйте ещё раз.",
            "The confirmation from Telegram never arrived. Try again.");
    }
}
