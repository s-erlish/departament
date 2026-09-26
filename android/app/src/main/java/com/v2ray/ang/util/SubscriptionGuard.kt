package com.v2ray.ang.util

import android.net.Uri

/**
 * Только подписки departament: вручную, по ссылке depv://, из буфера, с телевизора.
 *
 * Раньше хватало слова «departament» в любом месте адреса, и departament.attacker.tld проходил
 * проверку. Ссылка с любой страницы добавляла такую подписку без вопроса, приложение сразу слало
 * ей HWID и модель телефона, а на чистой установке её первый сервер становился выбранным — трафик
 * шёл через чужой сервер. Теперь — только домен departament.site и его поддомены.
 */
object SubscriptionGuard {

    private val ALLOWED_DOMAINS = listOf("departament.site")

    fun isAllowed(rawUrl: String): Boolean {
        val uri = try {
            Uri.parse(rawUrl.trim())
        } catch (e: Exception) {
            return false
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return false

        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        return ALLOWED_DOMAINS.any { host == it || host.endsWith(".$it") }
    }
}
