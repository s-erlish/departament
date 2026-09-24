package com.v2ray.ang.handler

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.template.TemplateManager
import com.v2ray.ang.util.JsonUtil
import java.security.MessageDigest

/**
 * ТОТ ЖЕ СЕРВЕР ОСТАЁТСЯ ТЕМ ЖЕ СЕРВЕРОМ ПОСЛЕ ОБНОВЛЕНИЯ ПОДПИСКИ.
 *
 * Обновление подписки - это «удалить все серверы провайдера и импортировать заново», и каждый
 * импорт выдавал КАЖДОМУ серверу новый guid, даже если в ответе провайдера не поменялось ни байта.
 * А guid - это всё, по чему приложение узнаёт сервер: под ним лежит задержка (`SERVER_AFF`), на
 * него указывает выбранный сервер, по нему `MainViewModel.runningGuid` помнит, на чём поднят
 * туннель, и по нему список сверяет строки. Новый guid значит «другой сервер»: пинги пропадали,
 * выбор держался только на поиске по имени (`AngConfigManager.findMatchedProfileKey`), туннель
 * после обновления числился «не на выбранном сервере», и нажатие на ту же строку предлагало
 * «Переподключиться» к тому же самому серверу. Владелец видел это на ПК как «программа
 * перегружается»; на ПК это исправлено (ConfigHandler.KeepIndexIdsAcrossRefresh), здесь - то же.
 *
 * Правила узнавания те же, что на ПК:
 *  - обычный сервер (ссылка vless/vmess/trojan/…) узнаётся по полному совпадению настроек
 *    подключения и имени - [ProfileItem.equals] плюс тип, имя и два поля, которые equals не
 *    сравнивает, а ПК сравнивает (`xhttpExtra`, `finalMask`);
 *  - конфиг провайдера (CUSTOM, XRAY_JSON Remnawave) узнаётся по имени: его настройки лежат в
 *    сыром шаблоне, который вправе меняться от ответа к ответу, а имя - это то, что человек видит
 *    в списке. Сначала ищется совпадение имени И адреса (два сервера с одним именем не
 *    перепутаются, пока их адреса различимы), потом - одного имени.
 *
 * Каждый прежний guid отдаётся не больше одного раза. Узнанный сервер получает прежний guid, но
 * записываются под ним НОВЫЕ настройки: узнавание решает только, чей это сервер, а не что в нём
 * лежит. Изменилось ли содержимое, отдельно отвечает [fingerprint].
 *
 * Здесь нет ни хранилища, ни журнала, чтобы правила проверялись тестами на JVM
 * (SubscriptionRefreshIdentityTest); чтение хранилища - в [fingerprintOfStored] и в
 * AngConfigManager.
 */
object SubscriptionRefreshIdentity {

    /**
     * Для каждого сервера нового поколения - guid прошлого поколения, который ему достаётся, или
     * null, если это новый сервер.
     *
     * @param previous серверы, которые лежали под подпиской до обновления, в сохранённом порядке.
     * @param fresh новые серверы В ПОРЯДКЕ ПРОВАЙДЕРА: при двух неразличимых серверах первый в
     *   ответе получает первый из прежних, и строки не меняются местами.
     * @return список той же длины и в том же порядке, что [fresh].
     */
    fun assign(previous: List<Pair<String, ProfileItem>>, fresh: List<ProfileItem>): List<String?> {
        val result = arrayOfNulls<String>(fresh.size)
        if (previous.isEmpty() || fresh.isEmpty()) return result.toList()

        val pool = previous.filter { it.first.isNotBlank() }.toMutableList()
        // Первый проход строгий, второй - для конфигов провайдера, у которых сменился адрес.
        for (strict in listOf(true, false)) {
            for ((index, item) in fresh.withIndex()) {
                if (result[index] != null || pool.isEmpty()) continue
                val match = pool.firstOrNull { (_, old) -> isSameServer(old, item, strict) } ?: continue
                pool.remove(match)
                result[index] = match.first
            }
        }
        return result.toList()
    }

    /**
     * Узнаётся ли в [fresh] сервер прошлого поколения [old].
     *
     * @param strict для конфига провайдера требовать совпадения ещё и адреса с портом; для обычного
     *   сервера ничего не меняет - он и так сравнивается целиком.
     */
    fun isSameServer(old: ProfileItem, fresh: ProfileItem, strict: Boolean = false): Boolean {
        if (old.configType == EConfigType.CUSTOM || fresh.configType == EConfigType.CUSTOM) {
            if (old.configType != fresh.configType) return false
            if (old.remarks.isBlank() || old.remarks != fresh.remarks) return false
            return !strict || (old.server == fresh.server && old.serverPort == fresh.serverPort)
        }
        return old.configType == fresh.configType &&
            old.remarks == fresh.remarks &&
            old == fresh &&
            old.xhttpExtra == fresh.xhttpExtra &&
            old.finalMask == fresh.finalMask
    }

    /**
     * Отпечаток сервера: всё, из чего собирается туннель, кроме времени добавления, которое каждый
     * импорт пишет своё. Для конфига провайдера это ещё и сырой шаблон - [raw], уже расшифрованный:
     * зашифрованный шаблон каждый раз шифруется с новым IV, и сравнивать его как строку значит
     * видеть изменение там, где его нет.
     *
     * Сравнивается, а не хранится: SHA-256 в hex, чтобы в памяти не держать шаблон на десятки
     * килобайт ради одного «равно ли».
     */
    fun fingerprint(profile: ProfileItem, raw: String?): String {
        val text = JsonUtil.toJson(profile.copy(addedTime = 0L)) + '\u0000' + raw.orEmpty()
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * [fingerprint] сервера так, как он лежит в хранилище, или null, если сервера нет.
     *
     * Шаблон читается только у конфига провайдера: остальным туннель собирается из самой записи.
     * Ошибка расшифровки не бросается - отпечаток тогда null, и вызывающий трактует его как
     * «не знаю», а не как «не изменилось».
     */
    fun fingerprintOfStored(guid: String): String? {
        val profile = MmkvManager.decodeServerConfig(guid) ?: return null
        val raw = if (profile.configType == EConfigType.CUSTOM) {
            runCatching { TemplateManager.decodeRuntimeRaw(guid) }.getOrNull() ?: return null
        } else {
            null
        }
        return fingerprint(profile, raw)
    }
}
