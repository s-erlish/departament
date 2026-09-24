package com.v2ray.ang.account

import com.v2ray.ang.R
import com.v2ray.ang.auth.ApiError
import com.v2ray.ang.auth.ApiGson
import com.v2ray.ang.auth.CODE_INVALID_PASSWORD
import com.v2ray.ang.auth.CODE_PASSWORD_REQUIRED
import com.v2ray.ang.auth.dto.ChangeEmailRequestDto
import com.v2ray.ang.auth.dto.SetPasswordRequestDto
import com.v2ray.ang.auth.dto.UserProfileDto
import com.v2ray.ang.auth.dto.canSetPassword
import com.v2ray.ang.auth.dto.emailArrived
import com.v2ray.ang.auth.dto.emailSignInWorks
import com.v2ray.ang.auth.serverCode
import com.v2ray.ang.auth.serverMessage
import com.v2ray.ang.viewmodel.AuthViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пароль после привязки и смена адреса. Всё, что здесь закреплено, снято с исходников панели
 * (`client.routes.ts`), а не угадано.
 *
 * Каждый из четырёх блоков — место, где ошибка не падает, а тихо делает не то: отказывает в
 * пароле, который сервер принял бы; шлёт пароль туда, где его не просят; называет опечатку
 * истёкшей сессией; ждёт подтверждения, которое уже пришло, или которого никогда не будет.
 */
class EmailPasswordContractTest {

    // region тела запросов

    @Test
    fun `set-password sends the new password and nothing else`() {
        assertEquals(
            """{"newPassword":"hunter6"}""",
            ApiGson.instance.toJson(SetPasswordRequestDto("hunter6")),
        )
    }

    /** У аккаунта с паролем панель требует текущий — иначе 400 PASSWORD_REQUIRED. */
    @Test
    fun `change-email carries the current password when the account has one`() {
        assertEquals(
            """{"newEmail":"new@example.com","currentPassword":"hunter6"}""",
            ApiGson.instance.toJson(ChangeEmailRequestDto("new@example.com", "hunter6")),
        )
    }

    /**
     * А у аккаунта без пароля поля быть НЕ должно. Пустая строка прошла бы как значение и
     * поехала бы на сравнение с хешем: «я его не отправлял» превратилось бы в «неверный пароль».
     */
    @Test
    fun `change-email omits the field entirely when there is no password`() {
        assertEquals(
            """{"newEmail":"new@example.com"}""",
            ApiGson.instance.toJson(ChangeEmailRequestDto("new@example.com", null)),
        )
    }

    // endregion

    // region код панели, а не код статуса

    @Test
    fun `the panel's own name for the two password refusals is read back`() {
        assertEquals(
            CODE_PASSWORD_REQUIRED,
            ApiError.Server(400, """{"message":"Введите текущий пароль","code":"PASSWORD_REQUIRED"}""")
                .serverCode(),
        )
        assertEquals(
            CODE_INVALID_PASSWORD,
            ApiError.Unauthorized("""{"message":"Неверный пароль","code":"INVALID_PASSWORD"}""")
                .serverCode(),
        )
    }

    @Test
    fun `a refusal carries its sentence and its code at the same time`() {
        val body = """{"message":"Неверный пароль","code":"INVALID_PASSWORD"}"""
        assertEquals("Неверный пароль", ApiError.Unauthorized(body).serverMessage())
        assertEquals(CODE_INVALID_PASSWORD, ApiError.Unauthorized(body).serverCode())
    }

    @Test
    fun `a refusal without a code is not given one`() {
        assertNull(ApiError.Server(400, """{"message":"Некорректные данные"}""").serverCode())
        assertNull(ApiError.Server(400).serverCode())
        assertNull(ApiError.Network().serverCode())
    }

    /** Код — это имя, а не фраза: что-то другое в поле `code` сравнивать не с чем. */
    @Test
    fun `prose in the code field is not a code`() {
        assertNull(ApiError.Server(400, """{"code":"Введите текущий пароль"}""").serverCode())
        assertNull(ApiError.Server(400, """{"code":""}""").serverCode())
        assertNull(ApiError.Server(400, """{"code":{"name":"X"}}""").serverCode())
    }

    // endregion

    // region 401 значит разное на разных экранах

    @Test
    fun `a wrong current password is not a dead session`() {
        // Экран смены почты берёт формулировку панели, потому что различает по коду; общая
        // раскладка 401 для привязки по-прежнему говорит про сессию, а для входа — про пароль.
        assertEquals(
            R.string.auth_err_session_expired,
            AuthViewModel.messageFor(
                ApiError.Unauthorized(),
                AuthViewModel.Surface.MAIL,
                linkEmail = true,
            ),
        )
        assertEquals(
            R.string.auth_err_credentials,
            AuthViewModel.messageFor(ApiError.Unauthorized(), AuthViewModel.Surface.MAIL),
        )
    }

    // endregion

    // region кому предлагать пароль

    /** Ровно та же проверка, что у панели: отказ только при passwordHash И onboardingCompleted. */
    @Test
    fun `the password step mirrors the panel's own gate`() {
        assertTrue(profile(hasPassword = false, onboarding = true).canSetPassword())
        assertTrue(profile(hasPassword = false, onboarding = false).canSetPassword())
        // Пароль есть, но онбоардинг не завершён — это dummy от email-регистрации, панель заменит.
        assertTrue(profile(hasPassword = true, onboarding = false).canSetPassword())
        // Настоящий пароль: панель ответит «Пароль уже установлен», шаг показывать нельзя.
        assertFalse(profile(hasPassword = true, onboarding = true).canSetPassword())
    }

    @Test
    fun `the profile reads the two flags the panel sends, and defaults safely without them`() {
        val sent = ApiGson.instance.fromJson(
            """{"id":"c1","email":"a@b.ru","hasPassword":true,"onboardingCompleted":false}""",
            UserProfileDto::class.java,
        )
        assertTrue(sent.hasPassword)
        assertFalse(sent.onboardingCompleted)

        // Бэкенд постарше полей не шлёт: «пароля нет» и «онбоардинг пройден» — безопасная пара,
        // при которой шаг предлагается, а панель, если он не нужен, скажет об этом сама.
        val old = ApiGson.instance.fromJson("""{"id":"c1"}""", UserProfileDto::class.java)
        assertFalse(old.hasPassword)
        assertTrue(old.onboardingCompleted)
        assertTrue(old.canSetPassword())
    }

    // endregion

    // region что показывает строка «Почта»

    /**
     * Три состояния строки, и решает их ДРУГОЙ вопрос, чем тот, по которому предлагается шаг.
     * Строка отвечает «работает ли вход по почте», шаг — «примет ли панель set-password».
     */
    @Test
    fun `the row asks whether e-mail sign-in works, not whether a password may be set`() {
        // Адреса нет: входить нечем и не с чего.
        assertFalse(UserProfileDto(email = "", hasPassword = false).emailSignInWorks())
        assertFalse(UserProfileDto(email = "", hasPassword = true).emailSignInWorks())
        // Адрес есть, пароля нет: ровно тот случай, ради которого третье состояние и появилось.
        assertFalse(UserProfileDto(email = "a@b.ru", hasPassword = false).emailSignInWorks())
        // Оба на месте.
        assertTrue(UserProfileDto(email = "a@b.ru", hasPassword = true).emailSignInWorks())
    }

    /**
     * Единственный случай, где два вопроса расходятся, и где перепутать их видно на экране:
     * пароль настоящий, онбоардинг не завершён. Входить можно (панель проверяет только хеш), а
     * `set-password` панель ещё примет. Строка, спрошенная про шаг, сказала бы «нужен пароль»
     * человеку, которому он не нужен.
     */
    @Test
    fun `an unfinished onboarding with a real password still signs in`() {
        val profile = UserProfileDto(
            email = "a@b.ru",
            hasPassword = true,
            onboardingCompleted = false,
        )
        assertTrue(profile.emailSignInWorks())
        assertTrue(profile.canSetPassword())
    }

    // endregion

    // region чего ждёт опрос

    @Test
    fun `attaching waits for any address at all`() {
        assertFalse(profileWith("").emailArrived(null))
        assertTrue(profileWith("new@example.com").emailArrived(null))
    }

    /**
     * А смена — только за новым. Старый адрес непустой с самого начала, и «есть ли адрес»
     * ответило бы «да» на первом же круге, закрыв ожидание до того, как ссылку открыли.
     */
    @Test
    fun `replacing waits for the new address and is not fooled by the old one`() {
        assertFalse(profileWith("old@example.com").emailArrived("new@example.com"))
        assertTrue(profileWith("new@example.com").emailArrived("new@example.com"))
    }

    /** Панель хранит адрес в нижнем регистре — иначе ожидание для `A@B.RU` не кончилось бы. */
    @Test
    fun `the address the panel lower-cased still answers the address the user typed`() {
        assertTrue(profileWith("user@example.com").emailArrived("User@Example.COM"))
        assertTrue(profileWith("user@example.com").emailArrived("  user@example.com  "))
    }

    // endregion

    private fun profile(hasPassword: Boolean, onboarding: Boolean) =
        UserProfileDto(hasPassword = hasPassword, onboardingCompleted = onboarding)

    private fun profileWith(email: String) = UserProfileDto(email = email)
}
