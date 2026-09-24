package com.v2ray.ang.ui.component

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.auth.AuthManager
import com.v2ray.ang.auth.AuthManager.LoginState
import com.v2ray.ang.auth.BackendConfig
import com.v2ray.ang.ui.MainActivity
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.viewmodel.AuthViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * УДЕРЖАНИЕ ГЛАВНОЙ НА ВРЕМЯ ПОЛНОЭКРАННОГО ПОТОКА — одно правило, два адресата.
 *
 * Оболочка знает то, чего не знает вкладка: её флаг переживает пересоздание вьюх фрагмента
 * (поворот экрана посреди потока), поэтому, когда хозяин — `MainActivity`, держим через неё, а
 * [HomeHandoff] остаётся путём для любого другого хозяина. [HomeHandoff] — это КОНТРАКТ между
 * потоком и Главной; здесь же выбирается только адресат, и выбор этот один на все потоки.
 *
 * Правило жило приватной парой методов в [GateView] и было бы скопировано в каждую следующую
 * вкладку, которая поднимет над собой слой прогрузки. Копий больше не заводить: и поток «из
 * буфера», и вход через Telegram, откуда бы его ни начали, зовут эти два метода.
 */
object HomeHold {

    /**
     * Первая половина стыка: Главная ставится в предсборочное состояние ПОД уже поднятым слоем,
     * пока её никто не видит. Иначе она соберётся сама, за секунду с небольшим, и слой уйдёт с
     * готового экрана — это и есть грабля №6.
     */
    @MainThread
    fun take(activity: ComponentActivity) {
        val shell = activity as? MainActivity
        if (shell != null) shell.holdHomeEntrance() else HomeHandoff.prime()
    }

    /**
     * Отпустить Главную. Зовётся на ВСЕХ выходах из потока — успех, отказ, «назад», — потому что
     * [take] оставляет Главную с нулевой прозрачностью строки аккаунта, кнопки и списка, и без
     * этой строки сорвавшийся поток оставил бы под собой пустой экран.
     *
     * На успешном пути её место — внутри `FlowOverlay.finish { }`, то есть в том же кадре, в
     * котором слой снимается с иерархии (грабля №6). Отдельного метода для этого не нужно:
     * `finish` зовёт свой колбэк синхронно.
     */
    @MainThread
    fun release(activity: ComponentActivity) {
        val shell = activity as? MainActivity
        if (shell != null) shell.revealHome { } else HomeHandoff.assemble()
    }
}

/**
 * ВХОД ЧЕРЕЗ TELEGRAM БЕЗ ЭКРАНА ВХОДА — один поток, который зовут обе вкладки.
 *
 * В дизайне у этого пути нет экрана входа, есть слой прогрузки: пользователь нажимает «Войти через
 * Telegram», открывается Telegram, а ожидание идёт ТАМ ЖЕ, где нажали, под [FlowOverlay]. Никакой
 * Activity при этом не открывается — и в этом весь смысл. Владелец сказал это трижды, последний
 * раз так: «меня кидает на новое окно где опять кнопки открыть телеграм и вход через сайт, этого
 * быть не должно, ВСЁ ДОЛЖНО ПРОИСХОДИТЬ НА ВКЛАДКЕ АККАУНТ».
 *
 * ПОЧЕМУ ЭТО ОТДЕЛЬНЫЙ КЛАСС, А НЕ ВТОРАЯ КОПИЯ. Механика жила приватными методами [GateView] и
 * работала — но принадлежала начальному экрану. Вкладка «Аккаунт» показывает тот же блок входа,
 * значит ей нужен тот же поток, а не свой: две реализации одного входа разойдутся на первой же
 * правке. Поэтому поток вынесен целиком, [GateView] зовёт его так же, как `AccountFragment`, и под
 * ним по-прежнему работает тот самый `AuthManager.beginTelegramLogin()`, которым живёт
 * `AuthViewModel`. `LoginActivity` со всем, что у неё есть (почта, OTP, 2FA, Google, гейт с двумя
 * кнопками, `MODE_TELEGRAM_START`), остаётся в проекте и открывается строкой «Войти по почте»:
 * она перестала быть путём по умолчанию для ОДНОЙ кнопки, а не исчезла.
 *
 * ЖИЗНЬ ЭКЗЕМПЛЯРА — жизнь того, кто его держит. Работа идёт в `lifecycleScope` окна, слой висит в
 * `android.R.id.content` того же окна: уходит окно — уходит и то, и другое. Держателю остаётся
 * пробросить сюда две вещи: возвращение на передний план ([onReturn]) и, если он того хочет,
 * отмену ([cancel]).
 */
class TelegramFlow(private val host: Host) {

    /** То немногое, что поток не может сделать сам: это знает вкладка, а не он. */
    interface Host {

        // ЗДЕСЬ БОЛЬШЕ НЕТ `refreshSubscriptions()`, И ЭТО НЕ УПРОЩЕНИЕ, А УБРАННАЯ РАБОТА.
        // Поток отвечал на успешный вход обходом подписок, УЖЕ лежащих на устройстве
        // (`MainActivity.importConfigViaSub`), а подписку аккаунта заводит другой путь —
        // `HomeFragment.onLoggedIn` -> `AccountRepository.autoImportSubscriptions`, и он идёт на
        // ЛЮБОМ входе, потому что его запускает переход состояния аккаунта, а не кнопка. Так что
        // обход стоил ровно одного из двух:
        //   • новый аккаунт — обходить нечего: пустой прогон, `showLoading`/`hideLoading` с
        //     задержкой в полсекунды и уведомление «Нет подписок для обновления», которое
        //     `NoticePolicy` правилом 5 всё равно глушит, потому что идёт поток;
        //   • повторный вход — та же подписка выкачивается ДВАЖДЫ, обходом и импортом, в одну и ту
        //     же секунду, по тем же URL.
        // Ждать поток и так умеет — [awaitSubscriptionImport], и ждёт он именно импорт, а не
        // обход; это записано в его же комментарии с тех пор, как ожидание появилось.

        /**
         * Дождаться, пока импорт подписки аккаунта действительно закончится.
         *
         * Существует для того, чтобы слой с полоской не уходил раньше времени: «должно
         * продолжаться начальное окно, где добавление подписки вот это идёт с полосочкой и только
         * потом как добавилось перекидывать на главную». Ограничено по времени на стороне
         * оболочки — зависнуть не может, а на путях, где импорта нет вовсе, возвращается сразу.
         */
        suspend fun awaitSubscriptionImport()

        /**
         * Поток сорвался. Причина показывается ТАМ, где её ждёт эта вкладка, — подписью под
         * заголовком, а не тостом и не диалогом, — поэтому решает это держатель, а не поток.
         */
        fun onFailed(@StringRes message: Int)
    }

    private var overlay: FlowOverlay? = null
    private var job: Job? = null
    private var activity: ComponentActivity? = null

    /** Ссылка открыта. Второй раз сама она не открывается — только по кнопке на слое. */
    private var opened = false

    /** Идёт ли попытка. Держатель спрашивает, чтобы не начать вторую поверх первой. */
    val isRunning: Boolean get() = overlay != null

    /**
     * Начать вход. Слой поднимается сразу, Telegram открывается, как только бэкенд выдаст ссылку.
     *
     * @return false, если бэкенда нет вовсе: входить некуда, и звать сюда нечего — держатель
     * отправляет пользователя туда, где экран скажет это своими словами («Войти по почте»).
     * Повторный вызов на идущем потоке возвращает true и ничего не делает.
     */
    @MainThread
    fun start(activity: ComponentActivity): Boolean {
        if (overlay != null) return true
        if (!BackendConfig.isConfigured()) return false

        this.activity = activity
        opened = false
        HomeHold.take(activity)
        val surface = FlowOverlay.show(activity, FlowOverlay.Kind.TELEGRAM) { cancel() }
        overlay = surface
        job = activity.lifecycleScope.launch {
            AuthManager().beginTelegramLogin().collect { state ->
                when (state) {
                    is LoginState.AwaitingTelegram -> openTelegram(state.deepLink)
                    is LoginState.Polling -> openTelegram(state.deepLink)
                    is LoginState.Success -> {
                        surface.step(STEP_IMPORTING)
                        // ЖДЁМ ЗДЕСЬ, А НЕ В ОБОЛОЧКЕ. MainActivity.revealHome держит на тех же
                        // фактах ВХОД Главной, но к моменту его вызова FlowOverlay.finish слой
                        // уже снял — и ожидание показывает тёмный незаполненный экран вместо
                        // полоски, на которую человек смотрел. Один шаг раньше — и окно остаётся.
                        //
                        // Ждём ИМПОРТ, а не обход подписок: обход отсюда убран целиком, причина —
                        // в [Host].
                        host.awaitSubscriptionImport()
                        delay(STEP_DWELL_MS)
                        handOff(surface)
                    }

                    is LoginState.Error -> fail(
                        AuthViewModel.messageFor(state.error, awaitingTelegram = opened)
                    )

                    // Never emitted by the Telegram flow: the password path and the e-mail
                    // verification wait belong to the sign-in screen's other two entry points.
                    is LoginState.Idle, is LoginState.SiteLoading,
                    is LoginState.AwaitingEmailVerification -> Unit
                }
            }
        }
        return true
    }

    /**
     * Окно вернулось на передний план. Если Telegram открывали — значит пользователь оттуда
     * пришёл, и шаг можно двигать дальше: подтверждение проверяется опросом, а не этим событием,
     * но показать «проверяем» надо ровно сейчас.
     */
    @MainThread
    fun onReturn() {
        if (opened) overlay?.step(STEP_CHECKING)
    }

    /**
     * ОТМЕНА — «назад» на слое или уход держателя. Работает на ЛЮБОМ шаге ожидания: опрос
     * бросается, слой снимается, Главная отпускается, и под слоем остаётся ровно тот экран, с
     * которого поток начали. `AuthManager.beginTelegramLogin()` — холодный поток, поэтому отмена
     * его сборщика и есть отмена входа: ни токена, ни опроса после этого не остаётся.
     */
    @MainThread
    fun cancel() {
        val window = activity
        job?.cancel()
        job = null
        overlay?.cancel()
        clear()
        if (window != null) HomeHold.release(window)
    }

    // ------------------------------------------------------------------ Telegram

    /**
     * Ссылка открывается один раз на токен: повторный `Polling` не должен снова прыгать в
     * Telegram. Со второго раза её открывает только человек — кнопкой «Открыть Telegram», которая
     * появляется на слое ровно с этого момента, потому что до него открывать нечего.
     */
    private fun openTelegram(deepLink: String) {
        if (opened || deepLink.isBlank()) return
        opened = true
        overlay?.offerOpenTelegram { launchTelegram(deepLink) }
        launchTelegram(deepLink)
    }

    private fun launchTelegram(deepLink: String) {
        val window = activity ?: return
        try {
            window.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)))
        } catch (e: ActivityNotFoundException) {
            // Telegram не установлен: тот же t.me открывается в браузере и завершает то же
            // подтверждение, поэтому опрос продолжается, а не падает.
            LogUtil.w(AppConfig.TAG, "Telegram is not installed, falling back to the browser", e)
            try {
                window.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(deepLink))
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                )
            } catch (second: ActivityNotFoundException) {
                LogUtil.w(AppConfig.TAG, "No browser to open the Telegram link", second)
                fail(R.string.auth_err_telegram_missing)
            }
        }
    }

    // ------------------------------------------------------------------ финал и срыв

    /**
     * ГРАБЛЯ №6. Последний шаг, тост — и снятие слоя тем же кадром, что и запуск сборки Главной:
     * [FlowOverlay.finish] удаляет слой из иерархии и синхронно, следующей строкой, зовёт этот
     * колбэк. Между ними нет ни ожидания, ни поста, ни второго кадра.
     */
    private fun handOff(surface: FlowOverlay) {
        val window = activity
        clear()
        surface.finish { if (window != null) HomeHold.release(window) }
    }

    /**
     * Отказ. Слой уходит, Главная отпускается, а причина остаётся на экране — её ставит держатель
     * там, где её видно. Сборщик здесь НЕ отменяется: [fail] зовут из него самого, а холодный
     * поток и так кончается на своей `Error`.
     */
    private fun fail(@StringRes message: Int) {
        val window = activity
        overlay?.cancel()
        clear()
        if (window != null) HomeHold.release(window)
        host.onFailed(message)
    }

    /** Забыть окно, слой и попытку. Одно место, чтобы ни один выход не оставил половину. */
    private fun clear() {
        overlay = null
        activity = null
        opened = false
    }

    private companion object {
        /** Шаг 1 — «Проверяем вход»: пользователь вернулся из Telegram. */
        const val STEP_CHECKING = 1

        /** Шаг 2 — «Добавляем подписку»: подтверждение получено, дальше работает сервер. */
        const val STEP_IMPORTING = 2

        /** Нижний порог показа шага: меньше — и текст не успевают прочитать. */
        const val STEP_DWELL_MS = 900L
    }
}
