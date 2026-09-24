package com.v2ray.ang.account

import com.v2ray.ang.auth.ApiError
import com.v2ray.ang.auth.ApiGson
import com.v2ray.ang.auth.dto.RegisterResponseDto
import com.v2ray.ang.auth.dto.UserProfileDto
import com.v2ray.ang.auth.dto.canSetPassword
import com.v2ray.ang.auth.dto.emailSignInWorks
import com.v2ray.ang.auth.serverMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two halves of in-app registration that a JVM test can actually reach: the SHAPE of what the
 * panel answers with, and the SENTENCE it refuses with.
 *
 * Both are places where being wrong is silent rather than loud. A registration that answers 201
 * without a token is a SUCCESS — the letter is on its way — and reading it as a failure would put
 * an error on screen for an account that now exists. A refusal carries the only description of
 * what went wrong that anyone has: 400 covers «Этот email уже зарегистрирован» and «Некорректные
 * данные» alike, and a status code cannot tell them apart.
 *
 * The flow around them (`AuthManager.beginRegister`, the login poll) is coroutines over OkHttp and
 * `object`s over MMKV, so it is not reachable from here; these are the parts that can be pinned.
 */
class RegistrationContractTest {

    // region the 201, in both of its shapes

    @Test
    fun `a panel with verification off answers with a session`() {
        val raw = ApiGson.instance.fromJson(
            """{"token":"jwt-value","client":{"id":"c1","email":"a@b.ru"}}""",
            RegisterResponseDto::class.java,
        )
        assertEquals("jwt-value", raw.token)
        assertNotNull(raw.client)
        assertEquals("a@b.ru", raw.client?.email)
        assertTrue(!raw.requiresVerification)
    }

    @Test
    fun `a panel with verification on answers with a letter and no token`() {
        val raw = ApiGson.instance.fromJson(
            """{"message":"Письмо отправлено","requiresVerification":true}""",
            RegisterResponseDto::class.java,
        )
        assertNull(raw.token)
        assertNull(raw.client)
        assertTrue(raw.requiresVerification)
        assertEquals("Письмо отправлено", raw.message)
    }

    /**
     * И В ОБОИХ ОТВЕТАХ КЛИЕНТ РОЖДАЕТСЯ С НЕЗАКОНЧЕННЫМ ОНБОРДИНГОМ, хотя пароль у него уже есть:
     * панель ставит `onboardingCompleted` в `false` и в `/client/auth/register` при выключенной
     * проверке почты, и в `/client/auth/verify-email`. Отсюда всё остальное — см. следующий блок.
     */
    @Test
    fun `the client the panel creates has a password and an unfinished onboarding`() {
        val raw = ApiGson.instance.fromJson(
            """{"token":"jwt-value","client":{"id":"c1","email":"a@b.ru","hasPassword":true,"onboardingCompleted":false}}""",
            RegisterResponseDto::class.java,
        )
        assertEquals(true, raw.client?.hasPassword)
        assertEquals(false, raw.client?.onboardingCompleted)
    }

    // endregion

    // region почему за регистрацией идёт complete-onboarding

    /**
     * ФЛАГ — ВТОРАЯ ПОЛОВИНА ПОРУЧЕНИЯ, И У РЕГИСТРАЦИИ ТОЖЕ.
     *
     * `set-password` панель отклоняет ровно при `passwordHash && onboardingCompleted`, и
     * [canSetPassword] это условие зеркалит. Аккаунт, который панель только что создала, ломает
     * вторую половину условия, а не первую: пароль у него ЕСТЬ, а флага нет. Значит «можно задать
     * пароль» остаётся истинным навсегда, панель примет повторную установку, и «Способы входа»
     * будут звать на шаг, которого аккаунту не нужно.
     *
     * Проглатывать неудачу этого вызова можно, не делать его — нельзя.
     */
    @Test
    fun `a freshly registered account stays eligible for a password step it does not need`() {
        val justRegistered = UserProfileDto(
            email = "a@b.ru",
            hasPassword = true,
            onboardingCompleted = false,
        )
        assertTrue(justRegistered.canSetPassword())
        // Войти по этой почте при этом можно: пароль настоящий. Ровно тот случай, где два вопроса
        // расходятся, и где «Нужен пароль для входа» было бы неправдой.
        assertTrue(justRegistered.emailSignInWorks())
    }

    /** И тот же аккаунт после отметки перестаёт быть кандидатом на шаг. */
    @Test
    fun `the flag is what closes the step`() {
        val settled = UserProfileDto(
            email = "a@b.ru",
            hasPassword = true,
            onboardingCompleted = true,
        )
        assertFalse(settled.canSetPassword())
        assertTrue(settled.emailSignInWorks())
    }

    /**
     * Аккаунт из Telegram отметка не касается: у него нет пароля, и шаг ему как раз нужен. Поэтому
     * complete-onboarding идёт ТОЛЬКО за регистрацией, закончившейся сессией, и за set-password.
     */
    @Test
    fun `an account with no password still needs the step, flag or no flag`() {
        assertTrue(UserProfileDto(hasPassword = false, onboardingCompleted = true).canSetPassword())
        assertTrue(UserProfileDto(hasPassword = false, onboardingCompleted = false).canSetPassword())
    }

    // endregion

    // region the panel's own sentence

    @Test
    fun `the sentence is taken from every error that carries a body`() {
        val taken = """{"message":"Этот email уже зарегистрирован"}"""
        assertEquals(
            "Этот email уже зарегистрирован",
            ApiError.Server(400, taken).serverMessage(),
        )
        assertEquals(
            "Регистрация по email не настроена",
            ApiError.ServiceUnavailable("""{"message":"Регистрация по email не настроена"}""")
                .serverMessage(),
        )
        assertEquals(
            "Слишком часто",
            ApiError.RateLimited("""{"message":"Слишком часто"}""").serverMessage(),
        )
    }

    /** The older spelling some endpoints use for the same field. */
    @Test
    fun `an error field reads as the same sentence`() {
        assertEquals(
            "Некорректные данные",
            ApiError.Server(400, """{"error":"Некорректные данные"}""").serverMessage(),
        )
    }

    @Test
    fun `an error with no body of its own has nothing to quote`() {
        assertNull(ApiError.Server(400).serverMessage())
        assertNull(ApiError.Network().serverMessage())
        assertNull(ApiError.Timeout.serverMessage())
        assertNull(ApiError.Gone.serverMessage())
    }

    @Test
    fun `a payload that is not a sentence never reaches the screen`() {
        // Not JSON at all — an HTML error page from something standing in the way.
        assertNull(ApiError.Server(400, "<html><body>Bad Request</body></html>").serverMessage())
        // JSON, but with no message in it.
        assertNull(ApiError.Server(400, """{"statusCode":400}""").serverMessage())
        // A message that is empty, or blank, says nothing.
        assertNull(ApiError.Server(400, """{"message":""}""").serverMessage())
        assertNull(ApiError.Server(400, """{"message":"   "}""").serverMessage())
        // A message with no letters in it is a code wearing a field name.
        assertNull(ApiError.Server(400, """{"message":"400"}""").serverMessage())
        // A nested object serialises back with braces: a payload, not copy.
        assertNull(ApiError.Server(400, """{"message":{"ru":"Занято"}}""").serverMessage())
        // Anything long enough to be a stack trace is a payload that leaked.
        val long = "а".repeat(201)
        assertNull(ApiError.Server(400, """{"message":"$long"}""").serverMessage())
    }

    @Test
    fun `the sentence is trimmed but otherwise quoted verbatim`() {
        assertEquals(
            "Этот email уже зарегистрирован",
            ApiError.Server(400, """{"message":"  Этот email уже зарегистрирован  "}""")
                .serverMessage(),
        )
    }

    // endregion
}
