namespace departament.Desktop.Common;

// ─────────────────────────────────────────────────────────────────────────────
// OWNER: WP4. Account / Login / Onboarding.  Keys: Account_*, Login_*, Onboarding_*
//        (+ Common_* references).
// Views: AccountView(.axaml/.cs), AccountViewModel, LoginView(.axaml/.cs),
//        OnboardingView(.axaml/.cs).
// Inventory: LOCALIZATION_PLAN.md §2.4. Add each key with Add("Account_X", "ru", "en").
// This is the ONLY L file WP4 edits.
// ─────────────────────────────────────────────────────────────────────────────

public sealed partial class L
{
    partial void RegisterAccount()
    {
        // ── Account screen (AccountView) ──
        Add("Account_TopUp", "Пополнить", "Top up");
        Add("Account_TopUpTitle", "Пополнение баланса", "Top up balance");
        Add("Account_TopUpHint",
            "Введите сумму в рублях. Откроется страница оплаты.",
            "Enter an amount in rubles. The payment page will open.");
        Add("Account_AmountRub", "Сумма, ₽", "Amount, ₽");
        Add("Account_Continue", "Продолжить", "Continue");
        Add("Account_TopUpMethod", "Способ оплаты", "Payment method");
        Add("Account_TopUpVia", "Оплата · {0}", "Payment · {0}");
        // На «вы», как весь остальной интерфейс. Обе строки лежали на «ты» ровно потому, что их
        // НИКТО НЕ ПОКАЗЫВАЛ: пустое состояние «Аккаунта» не было привязано к разметке. Теперь оно
        // на экране, значит и голос обязан совпасть с остальным приложением.
        Add("Account_FirstSub", "Оформите первую подписку", "Set up your first subscription");
        Add("Account_NoSubHint",
            "Выберите тариф: оплата в рублях, подключение сразу.",
            "Pick a plan: pay in rubles, connect right away.");
        Add("Account_Devices", "Устройства", "Devices");
        Add("Account_SignOut", "Выйти", "Sign out");
        // На «вы», как весь остальной интерфейс. Эти две строки были единственными на «ты» во всём
        // приложении, и от них гейт входа в «Аккаунте» читался как экран из прошлой версии.
        Add("Account_SignInTitle", "Войдите в departament", "Sign in to departament");
        // Вторая кнопка гейта называется «Войти по почте» и ведёт на форму внутри приложения,
        // поэтому подсказка больше не отправляет на сайт.
        Add("Account_SignInHint",
            "Через Telegram быстро и без пароля. По почте можно войти или создать аккаунт.",
            "Telegram is fast and passwordless. With email you can sign in or create an account.");

        // ── Account view-model (imperative / computed) ──
        Add("Account_AmountGtZero", "Введите сумму больше 0", "Enter an amount greater than 0");
        Add("Account_ReferralCode", "Реф-код {0}", "Referral code {0}");
        Add("Account_MySubs", "Мои подписки", "My subscriptions");
        Add("Account_ValidUntil", "Действует до {0}", "Valid until {0}");
        Add("Account_DevicesCount", "Устройства: {0} / {1}", "Devices: {0} / {1}");

        // ── Subscription card (identity caption · health chip · expiry urgency · devices) ──
        Add("Account_TariffCaption", "Тариф · {0}", "Plan · {0}");
        Add("Account_TrialPeriod", "Пробный период", "Trial period");
        Add("Account_HealthActive", "Активна", "Active");
        Add("Account_HealthExpiring", "Истекает", "Expiring");
        Add("Account_HealthExpired", "Истекла", "Expired");
        Add("Account_ExpiresInDays", "Осталось {0} дн.", "{0} days left");
        Add("Account_ExpiredOn", "Истекла", "Expired");
        Add("Account_Perpetual", "Бессрочно", "No expiry");
        Add("Account_DevicesTotal", "{0} устройств", "{0} devices");
        Add("Account_Renew", "Продлить", "Renew");

        // ── Subscription card (redesign: named sub · state-led meters · auto-renew · renew flow) ──
        Add("Account_YourSubscription", "Ваша подписка", "Your subscription");
        Add("Account_SubscriptionN", "Подписка {0}", "Subscription {0}");
        Add("Account_ActiveUntil", "Активна до {0}", "Active until {0}");
        Add("Account_ExpiredOnDate", "Истекла {0}", "Expired {0}");
        Add("Account_DevicesShort", "{0} / {1} устройств", "{0} / {1} devices");
        Add("Account_DevicesUnlimited", "Безлимит устройств", "Unlimited devices");
        Add("Account_TrafficUnlimited", "{0} · безлимит", "{0} · unlimited");
        Add("Account_AutoRenew", "Автопродление", "Auto-renew");
        Add("Account_AutoRenewNext", "Продлится {0}, спишем {1}", "Renews {0}, we'll charge {1}");
        Add("Account_AutoRenewOn", "Автопродление включено", "Auto-renew is on");
        Add("Account_AutoRenewOnDate", "Продлится {0}", "Renews {0}");
        Add("Account_AutoRenewOff", "Автопродление выключено", "Auto-renew is off");
        Add("Account_AutoRenewNudge",
            "Включите автопродление, чтобы не прерывать",
            "Turn on auto-renew so it doesn't lapse");
        Add("Account_RenewFromBalance", "С баланса · {0}", "From balance · {0}");
        Add("Account_RenewWithCard", "Оплатить картой", "Pay by card");
        Add("Account_RenewDone", "Подписка продлена", "Subscription renewed");

        // ── Overflow «Ещё»: докупка устройств + улучшение тарифа ──
        // Client-side estimate («≈»): the final amount is computed by the backend at payment.
        Add("Account_DeviceEstimate", "≈ {0}", "≈ {0}");
        Add("Account_DevicesAdded", "Устройства добавлены", "Devices added");
        Add("Account_UpgradeTo", "Улучшить до {0}", "Upgrade to {0}");
        // {0} = amount, {1} = effective days.
        Add("Account_UpgradeQuote", "{0} · +{1} дн.", "{0} · +{1} days");
        Add("Account_UpgradeDone", "Тариф улучшен", "Plan upgraded");
        Add("Account_BackAction", "Назад", "Back");

        // ── Вкладка «Аккаунт», редизайн: семь полос (screens.md «Вкладка Аккаунт») ──
        // Кольцо трафика: подпись под крупной цифрой потраченного.
        Add("Account_TrafficNoLimit", "без ограничений", "no limit");
        Add("Account_TrafficOf", "из {0}", "of {0}");
        // Строки «Управление»: подпись под названием (что за этой строкой).
        Add("Account_BuySubtitle", "Тарифы и продление", "Plans and renewal");
        Add("Account_DevicesSubtitle", "Управление устройствами", "Manage devices");
        Add("Account_HistorySubtitle", "Все ваши операции", "All your transactions");
        // Полоса «Выйти из аккаунта»: отдельная карточка в цвете «плохо».
        Add("Account_SignOutFull", "Выйти из аккаунта", "Sign out of your account");
        // «Способы входа»: подпись строки несёт состояние привязки.
        Add("Account_SiteMethod", "Сайт", "Website");
        Add("Account_LinkedAs", "Привязан · {0}", "Linked · {0}");
        Add("Account_NotLinked", "Не привязан", "Not linked");
        // Третье состояние строки «Почта»: адрес привязан, а пароля за ним нет — войти по нему
        // нельзя. «Привязан · адрес» здесь обещал бы вход, которого не существует.
        Add("Account_EmailNoPassword", "Нужен пароль для входа · {0}", "Password needed to sign in · {0}");

        // ── Linking block (Способы входа) ──
        Add("Account_LinkingTitle", "Способы входа", "Sign-in methods");
        Add("Account_LinkEmail", "Email и пароль", "Email & password");
        Add("Account_Linked", "Привязан", "Linked");
        Add("Account_LinkAction", "Привязать", "Link");
        Add("Account_AddAction", "Добавить", "Add");
        Add("Account_OpenAction", "Открыть", "Open");
        Add("Account_TgLinkCode", "Код: {0}", "Code: {0}");
        Add("Account_OpenBot", "Открыть бота", "Open the bot");
        // Строк флайаута привязки почты («Привязать почту» · «Пришлём ссылку…» · «Отправить» ·
        // «Письмо отправлено на …») здесь больше нет: сам флайаут снят. Он умел ровно половину дела:
        // отправить письмо и сказать об этом всплывашкой, а ждать ответа было негде. Привязка живёт
        // на суб-странице (Account_LinkEmail* ниже), и слова ожидания теперь у неё.
        Add("Account_LinkDone", "Готово", "Done");

        // ── Строка «Почта» в «Способах входа»: три состояния, по одному действию в каждом ──
        Add("Account_ChangeAction", "Изменить", "Change");
        Add("Account_SetPasswordAction", "Задать пароль", "Set a password");

        // ── Привязка почты (суб-страница на лекале «Входа») ──
        // Подсказка НЕ обещает большего, чем делает: ссылка привязывает адрес, пароля у аккаунта
        // после неё всё ещё нет, его просит следующий шаг.
        Add("Account_LinkEmailTitle", "Привязать почту", "Link an email");
        Add("Account_LinkEmailSubtitle",
            "Аккаунт останется тем же, почта добавится к нему.",
            "Your account stays the same, the address is added to it.");
        Add("Account_SendLink", "Отправить ссылку", "Send the link");
        Add("Account_LinkEmailWaitTitle", "Проверьте почту", "Check your email");
        Add("Account_LinkEmailWaitHint",
            "Мы отправили ссылку на {0}. Откройте её, и почта привяжется к аккаунту.",
            "We've sent a link to {0}. Open it and the address will be attached to your account.");

        // ── Смена адреса ──
        // Отвязки почты в панели НЕТ: адрес можно только заменить, и экран этого не обещает.
        Add("Account_ChangeEmailTitle", "Изменить почту", "Change your email");
        Add("Account_ChangeEmailSubtitle",
            "Отправим ссылку на новый адрес. До перехода по ней почта остаётся прежней.",
            "We'll email a link to the new address. Until you open it, the old one stays.");
        Add("Account_NewEmail", "Новая почта", "New email");
        Add("Account_CurrentPassword", "Текущий пароль", "Current password");
        Add("Account_ChangeEmailWaitTitle", "Проверьте новую почту", "Check the new address");
        Add("Account_ChangeEmailWaitHint",
            "Мы отправили ссылку на {0}. Откройте её, и почта аккаунта сменится.",
            "We've sent a link to {0}. Open it and your account address will change.");
        // Панель различает «пароль не введён» и «пароль неверный», интерфейс тоже: иначе человек
        // ищет опечатку в поле, которого не заполнял.
        Add("Account_PasswordRequired", "Введите текущий пароль", "Enter your current password");
        Add("Account_PasswordWrong", "Неверный текущий пароль", "That's not your current password");

        // ── Первый пароль (POST /client/set-password) ──
        Add("Account_SetPasswordTitle", "Придумайте пароль", "Choose a password");
        Add("Account_SetPasswordSubtitle",
            "С ним можно будет входить по почте. Без пароля адрес остаётся опознавателем.",
            "With it you can sign in by email. Without one the address is just an identifier.");
        // Требование к длине сказано ОДИН раз, подсказкой под полем: она остаётся на экране, пока
        // человек печатает, а watermark исчезает с первым же символом. Дублировать его ещё и в
        // watermark значило бы написать одно и то же дважды в двух строках подряд.
        Add("Account_NewPasswordHint", "Минимум 6 символов", "At least 6 characters");
        Add("Account_SavePassword", "Сохранить пароль", "Save password");
        Add("Account_SkipPassword", "Пропустить", "Skip");
        // 401 на поручении это не «неверная почта или пароль»: пароля здесь не спрашивали. Это умерший
        // семидневный токен, и сказано именно про сессию.
        Add("Account_SessionExpired", "Сессия истекла. Войдите заново", "Your session expired. Sign in again");

        // ── Login screen (LoginView) ──
        Add("Login_SignIn", "Вход", "Sign in");
        Add("Login_Title", "Вход в departament", "Sign in to departament");
        Add("Login_Subtitle",
            "Войдите по email и паролю или через Telegram в один тап.",
            "Sign in with your email and password, or with Telegram in one tap.");
        Add("Login_Or", "или", "or");
        Add("Login_Email", "Электронная почта", "Email");
        Add("Login_EmailInvalid",
            "Введите корректный email, например name@example.com",
            "Enter a valid email, for example name@example.com");
        Add("Login_Password", "Пароль", "Password");
        Add("Login_ShowPassword", "Показать пароль", "Show password");
        Add("Login_HidePassword", "Скрыть пароль", "Hide password");
        Add("Login_EnterCode",
            "Введите 6-значный код из приложения",
            "Enter the 6-digit code from your app");
        Add("Login_CodeIs6", "Код состоит из 6 цифр", "The code is 6 digits");
        Add("Login_Confirm", "Подтвердить", "Confirm");
        // Строк экрана ожидания Telegram здесь больше нет («Ожидаем подтверждения в Telegram»,
        // «Открыть Telegram», «Начать заново», «Другой способ входа»): владелец убрал сам экран,
        // ждать подтверждения теперь шаг 0 экрана прогрузки (Flow_TgTitle0/Flow_TgNote0).

        // ── Start-page auth: sign-in ⇄ register segment, providers, passwordless links ──
        Add("Login_TabSignIn", "Вход", "Sign in");
        Add("Login_TabRegister", "Регистрация", "Register");
        Add("Login_TitleRegister", "Создайте аккаунт", "Create your account");
        Add("Login_SubtitleRegister",
            "Зарегистрируйтесь по email или войдите через Telegram в один тап.",
            "Register with your email, or sign in with Telegram in one tap.");
        Add("Login_PasswordRegister", "Пароль (не менее 8 символов)", "Password (at least 8 characters)");
        Add("Login_PasswordHint", "Минимум 8 символов", "At least 8 characters");
        Add("Login_ConfirmPassword", "Повторите пароль", "Repeat password");
        Add("Login_PasswordMismatch", "Пароли не совпадают", "The passwords don't match");
        Add("Login_CreateAccount", "Создать аккаунт", "Create account");
        Add("Login_MagicLink", "Войти по ссылке", "Sign in with a link");
        Add("Login_ForgotPassword", "Забыли пароль?", "Forgot password?");
        Add("Login_ContinueGoogle", "Продолжить с Google", "Continue with Google");
        Add("Login_ComingSoon", "Скоро", "Soon");

        // Email/password form submit («Войти»), distinct from the browser handoff «Войти через сайт»
        // (Common_SignInWebsite) and the manual-code fallback below.
        Add("Login_SubmitSignIn", "Войти", "Sign in");
        // Manual browser→app handoff fallback: paste the code the site shows if the scheme callback misses.
        Add("Login_ByCode", "Войти по коду", "Sign in with a code");
        Add("Login_CodePaste", "Вставьте код из браузера", "Paste the code from your browser");
        // Transient step while the departamentvpn://auth handoff code is being redeemed.
        Add("Login_SiteHandoff", "Завершаем вход через сайт…", "Finishing sign-in via the website…");

        // Email-pending states (verify email · magic link sent · reset sent). {0} = the address.
        Add("Login_VerifyTitle", "Подтвердите почту", "Confirm your email");
        Add("Login_VerifyHint",
            "Мы отправили ссылку на {0}. Откройте её, чтобы подтвердить вход: остальное сделаем сами.",
            "We've sent a link to {0}. Open it to confirm your sign-in, and we'll take care of the rest.");
        Add("Login_MagicSentTitle", "Ссылка отправлена", "Link sent");
        Add("Login_MagicSentHint",
            "Если аккаунт с {0} существует, мы отправили ссылку для входа. Откройте её в браузере.",
            "If an account for {0} exists, we've sent a sign-in link. Open it in your browser.");
        Add("Login_ResetSentTitle", "Письмо отправлено", "Email sent");
        Add("Login_ResetSentHint",
            "Если аккаунт с {0} существует, мы отправили ссылку для сброса пароля. Задайте новый пароль и вернитесь ко входу.",
            "If an account for {0} exists, we've sent a password-reset link. Set a new password, then return to sign in.");
        Add("Login_Resend", "Отправить снова", "Send again");
        Add("Login_BackToSignIn", "Вернуться ко входу", "Back to sign in");

        // Login error family (login-flow diagnostics; shown in the error line).
        Add("Login_ErrBadCreds", "Неверный email или пароль", "Incorrect email or password");
        Add("Login_ErrLinkExpired", "Ссылка устарела, начните заново", "The link has expired, start over");
        Add("Login_ErrUnavailable", "Вход недоступен", "Sign-in is unavailable");
        Add("Login_ErrEmailTaken",
            "Аккаунт с этой почтой уже существует",
            "An account with this email already exists");
        Add("Login_ErrRetry",
            "Что-то пошло не так, попробуйте снова",
            "Something went wrong, try again");

        // ── Account sync overlay (AccountSyncView) ──
        // Live stage line: tracks the real post-login phase (checking, subscriptions, servers).
        Add("Account_SyncStageAccount", "Проверяем аккаунт", "Checking your account");
        Add("Account_SyncSubtitle", "Загружаем подписки…", "Loading subscriptions…");
        Add("Account_SyncStageServers", "Обновляем серверы", "Refreshing servers");

        // Sync error surface (a failed import shows retry, not an eternal spinner).
        Add("Account_SyncErrorTitle", "Не удалось синхронизировать", "Sync didn't finish");
        Add("Account_SyncErrorHint",
            "Проверьте соединение и попробуйте снова.",
            "Check your connection and try again.");
        Add("Account_SyncRetry", "Повторить", "Try again");
        Add("Account_SyncReLogin", "Войти заново", "Sign in again");
    }
}
