package com.v2ray.ang

import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.util.HttpUtil
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpUtilTest {

    @Test
    fun testIdnToASCII() {
        // Regular URL remains unchanged
        val regularUrl = "https://example.com/path"
        assertEquals(regularUrl, HttpUtil.toIdnUrl(regularUrl))

        // Non-ASCII URL converts to ASCII (Punycode)
        val nonAsciiUrl = "https://例子.测试/path"
        val expectedNonAscii = "https://xn--fsqu00a.xn--0zwm56d/path"
        assertEquals(expectedNonAscii, HttpUtil.toIdnUrl(nonAsciiUrl))

        // Mixed URL only converts the host part
        val mixedUrl = "https://例子.com/测试"
        val expectedMixed = "https://xn--fsqu00a.com/测试"
        assertEquals(expectedMixed, HttpUtil.toIdnUrl(mixedUrl))

        // URL with Basic Authentication using regular domain
        val basicAuthUrl = "https://user:password@example.com/path"
        assertEquals(basicAuthUrl, HttpUtil.toIdnUrl(basicAuthUrl))

        // URL with Basic Authentication using non-ASCII domain
        val basicAuthNonAscii = "https://user:password@例子.测试/path"
        val expectedBasicAuthNonAscii = "https://user:password@xn--fsqu00a.xn--0zwm56d/path"
        assertEquals(expectedBasicAuthNonAscii, HttpUtil.toIdnUrl(basicAuthNonAscii))

        // URL with non-ASCII username and password
        val nonAsciiAuth = "https://用户:密码@example.com/path"
        // Basic auth credentials should remain unchanged as they're percent-encoded separately
        assertEquals(nonAsciiAuth, HttpUtil.toIdnUrl(nonAsciiAuth))
    }

    // ================================================================================
    // ОПОЗНАВАТЕЛЬ УСТРОЙСТВА УХОДИТ ТОЛЬКО ИСХОДНОМУ ХОСТУ
    // ================================================================================
    //
    // Подписка тянется циклом с тремя прыжками по `Location`, и на каждом прыжке
    // собирается новый запрос. Раньше опознаватель устройства — постоянный HWID этой
    // установки, модель, ОС и её версия — вешался на каждый из них, поэтому любой
    // `Location` уводил постоянный идентификатор на чужой хост. Учёт устройств живёт
    // на том хосте, которому запрос и был адресован; промежуточному знать нечего.
    //
    // Ниже закреплено ровно это: свой хост — заголовки есть, чужой — нет. И отдельно
    // то, что от изменения не пострадало: `Authorization` как брался из userinfo
    // ТЕКУЩЕГО адреса, так и берётся, поэтому редирект его и раньше не уносил.

    private val hwid = "6f1c2b0d-hwid"

    private fun headersFor(origin: String, hop: String): Map<String, String> {
        val builder = Request.Builder().url(hop)
        HttpUtil.attachDeviceHeaders(UrlContentRequest(url = origin, hwid = hwid), builder, hop)
        val headers = builder.build().headers
        return headers.names().associateWith { headers[it].orEmpty() }
    }

    private fun hasDeviceHeaders(origin: String, hop: String): Boolean {
        val headers = headersFor(origin, hop)
        val hwidSent = headers[AppConfig.HEADER_HWID] != null
        // Всё четыре заголовка — один опознаватель, и они не должны расходиться:
        // «HWID ушёл, а модель нет» — это тоже утечка, только наполовину.
        assertEquals(hwidSent, headers.containsKey(AppConfig.HEADER_DEVICE_OS))
        assertEquals(hwidSent, headers.containsKey(AppConfig.HEADER_VER_OS))
        assertEquals(hwidSent, headers.containsKey(AppConfig.HEADER_DEVICE_MODEL))
        return hwidSent
    }

    /** Первый запрос — по определению исходный хост. */
    @Test
    fun `device identity travels to the address the subscription named`() {
        val url = "https://panel.example.com/sub/abc123"
        assertTrue(hasDeviceHeaders(origin = url, hop = url))
        assertEquals(hwid, headersFor(url, url)[AppConfig.HEADER_HWID])
    }

    /** Тот же хост, другой путь и другая схема — сервер отвечает про себя же. */
    @Test
    fun `a redirect that stays on the host keeps the device identity`() {
        assertTrue(
            hasDeviceHeaders(
                origin = "http://panel.example.com/sub/abc123",
                hop = "https://panel.example.com/api/v1/sub/abc123",
            )
        )
    }

    /** Хост пишется как угодно: DNS регистр не различает, и мы тоже. */
    @Test
    fun `the host is matched the way DNS matches it`() {
        assertTrue(
            hasDeviceHeaders(
                origin = "https://Panel.Example.COM/sub/abc123",
                hop = "https://panel.example.com/sub/abc123",
            )
        )
    }

    /** Чужой домен — ничего. Ради этого всё и делалось. */
    @Test
    fun `a redirect to another host carries no device identity`() {
        assertFalse(
            hasDeviceHeaders(
                origin = "https://panel.example.com/sub/abc123",
                hop = "https://someone-else.example.org/sub/abc123",
            )
        )
    }

    /**
     * И поддомен — тоже чужой хост. Выбор осознанно строгий: соседний хост под тем же
     * доменом ОБЫЧНО того же владельца, но «обычно» — это весь аргумент целиком, а
     * цена ошибки — сам идентификатор.
     */
    @Test
    fun `a subdomain is another host`() {
        assertFalse(
            hasDeviceHeaders(
                origin = "https://example.com/sub/abc123",
                hop = "https://cdn.example.com/sub/abc123",
            )
        )
        // И обратное направление тоже: сужение хоста не привилегия.
        assertFalse(
            hasDeviceHeaders(
                origin = "https://cdn.example.com/sub/abc123",
                hop = "https://example.com/sub/abc123",
            )
        )
    }

    /** Нечитаемый адрес — не хост, который мы можем узнать. Молчим. */
    @Test
    fun `an address that will not parse is never the origin host`() {
        assertFalse(HttpUtil.isOriginHost("not a url at all", "https://panel.example.com/sub"))
        assertFalse(HttpUtil.isOriginHost("https://panel.example.com/sub", "not a url at all"))
        assertFalse(HttpUtil.isOriginHost(null, "https://panel.example.com/sub"))
    }

    /** Без HWID (владелец выключил отправку) не уходит ничего и на своём хосте тоже. */
    @Test
    fun `no hwid means no device headers at all`() {
        val url = "https://panel.example.com/sub/abc123"
        val builder = Request.Builder().url(url)
        HttpUtil.attachDeviceHeaders(UrlContentRequest(url = url, hwid = null), builder, url)
        assertNull(builder.build().header(AppConfig.HEADER_HWID))
        assertNull(builder.build().header(AppConfig.HEADER_DEVICE_MODEL))
    }

    // ------------------------------------------------------------------ Authorization

    /**
     * Встроенный `user:pass@` берётся из адреса ТЕКУЩЕГО прыжка, а не исходного, — так
     * было и так осталось. Поэтому пароль подписки редирект не уносил и до правки:
     * `Location` без userinfo просто не даёт заголовка.
     */
    @Test
    fun `embedded credentials come from the address being fetched`() {
        val builder = Request.Builder().url("https://panel.example.com/sub")
        HttpUtil.applyEmbeddedBasicAuthHeader("https://user:p%40ss@panel.example.com/sub", builder)
        // Basic dXNlcjpwQHNz == user:p@ss, то есть userinfo ещё и раскодирован.
        assertEquals("Basic dXNlcjpwQHNz", builder.build().header("Authorization"))
    }

    @Test
    fun `an address without credentials produces no authorization header`() {
        val builder = Request.Builder().url("https://cdn.example.org/sub")
        HttpUtil.applyEmbeddedBasicAuthHeader("https://cdn.example.org/sub", builder)
        assertNull(builder.build().header("Authorization"))
    }
}
