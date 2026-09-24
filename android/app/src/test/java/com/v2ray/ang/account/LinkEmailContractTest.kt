package com.v2ray.ang.account

import com.v2ray.ang.R
import com.v2ray.ang.auth.ApiError
import com.v2ray.ang.auth.ApiGson
import com.v2ray.ang.auth.dto.LinkEmailRequestDto
import com.v2ray.ang.auth.dto.UserProfileDto
import com.v2ray.ang.auth.serverMessage
import com.v2ray.ang.viewmodel.AuthViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Привязка почты к аккаунту, в который вошли через Telegram. Три вещи, ошибка в которых молчит.
 *
 * ЧТО УХОДИТ. `POST /client/link-email-request` принимает ОДИН адрес. Пароля в этом теле нет и не
 * должно быть: аккаунт уже существует, запрос подписан его токеном, и у панели просят письмо, а не
 * учётные данные. Лишнее поле здесь — это отправленный на сервер пароль, которого никто не вводил.
 *
 * ЧТО ПРИХОДИТ В ОТКАЗЕ. У 400 три разных смысла — «Почта уже привязана», «Некорректный email» и
 * «Эта почта уже используется другим аккаунтом», — и различить их может только панель. Сообщение
 * достаётся тем же [serverMessage], что и у регистрации, из уже обеззараженного тела.
 *
 * КОГДА ПРИХОДИТ 401. Здесь это не «неверная почта или пароль» — пароля не было. Это умерший
 * семидневный токен, и сказать надо про сессию.
 *
 * Сам поток (`AuthManager.beginLinkEmail`, опрос профиля) — корутины поверх OkHttp и `object`ы
 * поверх MMKV, из JVM-теста недостижим; закреплено то, что достижимо.
 */
class LinkEmailContractTest {

    // region что уходит на панель

    @Test
    fun `the request body is the address and nothing else`() {
        assertEquals(
            """{"email":"user@example.com"}""",
            ApiGson.instance.toJson(LinkEmailRequestDto("user@example.com")),
        )
    }

    @Test
    fun `no password ever travels with a link request`() {
        val json = ApiGson.instance.toJson(LinkEmailRequestDto("user@example.com"))
        assertFalse(json.contains("password", ignoreCase = true))
    }

    // endregion

    // region что приходит в отказе

    /** Все три смысла 400 из контракта панели, и ни один из них не выводится из кода статуса. */
    @Test
    fun `every refusal the panel spells out reaches the screen in its own words`() {
        assertEquals(
            "Почта уже привязана",
            ApiError.Server(400, """{"message":"Почта уже привязана"}""").serverMessage(),
        )
        assertEquals(
            "Некорректный email",
            ApiError.Server(400, """{"message":"Некорректный email"}""").serverMessage(),
        )
        assertEquals(
            "Эта почта уже используется другим аккаунтом",
            ApiError.Server(400, """{"message":"Эта почта уже используется другим аккаунтом"}""")
                .serverMessage(),
        )
    }

    @Test
    fun `the mail-is-not-configured answer is quoted from the 503, not paraphrased`() {
        assertEquals(
            "Отправка писем не настроена. Обратитесь в поддержку.",
            ApiError.ServiceUnavailable(
                """{"message":"Отправка писем не настроена. Обратитесь в поддержку."}"""
            ).serverMessage(),
        )
    }

    @Test
    fun `a 500 that explains itself is quoted too`() {
        assertEquals(
            "Не удалось отправить письмо. Попробуйте позже.",
            ApiError.Server(500, """{"message":"Не удалось отправить письмо. Попробуйте позже."}""")
                .serverMessage(),
        )
    }

    // endregion

    // region 401 на этом экране — про сессию, а не про пароль

    @Test
    fun `a dead token during linking names the session, not a password nobody typed`() {
        assertEquals(
            R.string.auth_err_session_expired,
            AuthViewModel.messageFor(
                ApiError.Unauthorized(),
                AuthViewModel.Surface.MAIL,
                linkEmail = true,
            ),
        )
    }

    /** Тот же 401 на обычном входе по-прежнему называет исправление: проверить почту и пароль. */
    @Test
    fun `the same 401 on an ordinary sign-in still names the credentials`() {
        assertEquals(
            R.string.auth_err_credentials,
            AuthViewModel.messageFor(ApiError.Unauthorized(), AuthViewModel.Surface.MAIL),
        )
        assertNotEquals(
            R.string.auth_err_session_expired,
            AuthViewModel.messageFor(ApiError.Unauthorized(), AuthViewModel.Surface.MAIL),
        )
    }

    // endregion

    // region сигнал, которого ждёт опрос

    /**
     * Опрос ждёт ровно одного: непустого `email` в профиле. У аккаунта из Telegram его нет, и
     * панель отдаёт там JSON null — [ApiGson] превращает его в "", а не роняет разбор.
     */
    @Test
    fun `a telegram-only profile carries no address, and a null one is not a crash`() {
        val telegramOnly = ApiGson.instance.fromJson(
            """{"id":"c1","email":null,"telegramLinked":true,"telegramUsername":"erlish"}""",
            UserProfileDto::class.java,
        )
        assertEquals("", telegramOnly.email)
        assertTrue(telegramOnly.email.isBlank())

        val linked = ApiGson.instance.fromJson(
            """{"id":"c1","email":"user@example.com","telegramLinked":true}""",
            UserProfileDto::class.java,
        )
        assertTrue(linked.email.isNotBlank())
    }

    // endregion
}
