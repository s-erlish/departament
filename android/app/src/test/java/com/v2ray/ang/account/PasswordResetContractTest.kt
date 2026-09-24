package com.v2ray.ang.account

import com.v2ray.ang.R
import com.v2ray.ang.auth.ApiError
import com.v2ray.ang.auth.ApiGson
import com.v2ray.ang.auth.BackendConfig
import com.v2ray.ang.auth.dto.PasswordResetRequestDto
import com.v2ray.ang.auth.serverMessage
import com.v2ray.ang.viewmodel.AuthViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Восстановить пароль», перенесённое из браузера в приложение. Четыре вещи из контракта панели,
 * ошибка в каждой из которых молчит.
 *
 * ЧТО УХОДИТ. `POST /client/auth/password-reset/request` принимает ОДИН адрес. Пароля здесь нет и
 * быть не может: сюда приходит тот, у кого пароля нет. Лишнее поле в этом теле было бы отправленным
 * на сервер паролем, которого никто не вводил.
 *
 * КУДА УХОДИТ. Под `/client/auth`, а не на корень клиента, где живут привязка и смена почты: те два
 * поручения делает аккаунт, в который уже вошли, а это — способ войти. Перепутать пути значит
 * получить 404 на живом эндпоинте.
 *
 * ЧТО ПРИХОДИТ В УСПЕХЕ, И ЧЕГО В НЁМ НЕТ. 200 одинаков для существующего адреса и для
 * несуществующего: панель так защищается от перечисления клиентов. Значит текст на экране условный
 * («если аккаунт существует»), и утверждать факт отправки он не имеет права.
 *
 * ЧТО ПРИХОДИТ В ОТКАЗЕ. Формулировку знает только панель, и она достаётся тем же [serverMessage],
 * что у регистрации и привязки. А 401 здесь не значит ни «неверная почта или пароль» (пароля никто
 * не вводил), ни «сессия истекла» (сессии не было).
 *
 * Сам поток — корутины поверх OkHttp и `object`ы поверх MMKV, из JVM-теста недостижим; закреплено
 * то, что достижимо.
 */
class PasswordResetContractTest {

    // region что уходит на панель

    @Test
    fun `the request body is the address and nothing else`() {
        assertEquals(
            """{"email":"user@example.com"}""",
            ApiGson.instance.toJson(PasswordResetRequestDto("user@example.com")),
        )
    }

    @Test
    fun `no password ever travels with a reset request`() {
        val json = ApiGson.instance.toJson(PasswordResetRequestDto("user@example.com"))
        assertFalse(json.contains("password", ignoreCase = true))
    }

    /**
     * Панель кладёт сброс под `/client/auth` вместе со входом и регистрацией, а привязку и смену
     * почты — на корень клиента. Это не стилистика путей: там поручения аккаунта, который уже
     * вошёл, здесь способ войти.
     */
    @Test
    fun `the reset lives with the ways IN, not with the errands of an account that has one`() {
        assertEquals(
            "/client/auth/password-reset/request",
            BackendConfig.Endpoints.passwordResetRequest,
        )
        assertTrue(BackendConfig.Endpoints.passwordResetRequest.startsWith("/client/auth/"))
        assertFalse(BackendConfig.Endpoints.linkEmailRequest.startsWith("/client/auth/"))
    }

    // endregion

    // region что приходит в отказе

    @Test
    fun `every refusal the panel spells out reaches the screen in its own words`() {
        assertEquals(
            "Некорректный email",
            ApiError.Server(400, """{"message":"Некорректный email"}""").serverMessage(),
        )
        assertEquals(
            "Отправка писем не настроена. Обратитесь в поддержку.",
            ApiError.ServiceUnavailable(
                """{"message":"Отправка писем не настроена. Обратитесь в поддержку."}"""
            ).serverMessage(),
        )
        assertEquals(
            "Слишком много запросов. Попробуйте через минуту.",
            ApiError.RateLimited("""{"message":"Слишком много запросов. Попробуйте через минуту."}""")
                .serverMessage(),
        )
    }

    /**
     * 401 на этом поручении не про учётные данные и не про сессию: ни того ни другого в запросе
     * нет. Обе соседние формулировки послали бы человека чинить то, чего на экране не существует.
     */
    @Test
    fun `a 401 here names neither a password nobody typed nor a session nobody opened`() {
        val message = AuthViewModel.messageFor(
            ApiError.Unauthorized(),
            AuthViewModel.Surface.MAIL,
            passwordReset = true,
        )
        assertEquals(R.string.auth_err_generic, message)
        assertNotEquals(R.string.auth_err_credentials, message)
        assertNotEquals(R.string.auth_err_session_expired, message)
    }

    /** И тот же 401 на обычном входе по-прежнему называет исправление. */
    @Test
    fun `the same 401 on an ordinary sign-in still names the credentials`() {
        assertEquals(
            R.string.auth_err_credentials,
            AuthViewModel.messageFor(ApiError.Unauthorized(), AuthViewModel.Surface.MAIL),
        )
    }

    /** Остальные отказы читаются как везде: у сброса нет своей версии «нет сети». */
    @Test
    fun `everything that is not a 401 keeps the wording the whole product uses`() {
        assertEquals(
            R.string.auth_err_rate_limited,
            AuthViewModel.messageFor(
                ApiError.RateLimited(),
                AuthViewModel.Surface.MAIL,
                passwordReset = true,
            ),
        )
        assertEquals(
            R.string.auth_err_network,
            AuthViewModel.messageFor(
                ApiError.Network(),
                AuthViewModel.Surface.MAIL,
                passwordReset = true,
            ),
        )
        assertEquals(
            R.string.auth_err_unavailable,
            AuthViewModel.messageFor(
                ApiError.ServiceUnavailable(),
                AuthViewModel.Surface.MAIL,
                passwordReset = true,
            ),
        )
    }

    // endregion

    // region экран после запроса ничего не ждёт

    /**
     * Состояние отправленного письма — СВОЁ, а не то, в котором приложение опрашивает панель.
     * Разница между ними и есть весь смысл: у [AuthViewModel.AuthUiState.EmailVerification] крутится
     * кольцо, потому что за ним стоит вопрос («открыли ли ссылку»), и ответ на него меняет вход или
     * профиль. Смена пароля не меняет ни того ни другого, спрашивать нечего, и кольца быть не
     * должно. Если однажды эти два состояния сольют в одно, здесь станет красно.
     */
    @Test
    fun `the letter nobody waits on has a state of its own`() {
        val sent = AuthViewModel.AuthUiState.PasswordResetSent("user@example.com")
        val anyState: AuthViewModel.AuthUiState = sent
        assertFalse(anyState is AuthViewModel.AuthUiState.EmailVerification)
        assertEquals("user@example.com", sent.email)
        // Покой по умолчанию: «Отправить снова» — это единственное, что делает экран занятым.
        assertFalse(sent.resending)
        assertTrue(AuthViewModel.AuthUiState.PasswordResetSent("a@b.c", resending = true).resending)
    }

    // endregion
}
