package com.v2ray.ang.extension

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.v2ray.ang.AngApplication
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.ui.Notice
import com.v2ray.ang.ui.NoticePolicy
import java.io.Serializable
import java.net.URI
import java.util.Locale

val Context.v2RayApplication: AngApplication?
    get() = applicationContext as? AngApplication

/*
 * ============================================================================
 * THE THREE DOORS THE OLD NOTIFICATION LAYER CAME THROUGH.
 * ============================================================================
 *
 * These names stay because ~50 call sites across upstream's screens use them, and a rename would
 * be fifty edits that the next merge undoes. What changed is what is behind them: NOT `Toasty`
 * (the green tick / red cross / system capsule the owner asked to have removed outright — «это же
 * старые от в2рей уведомления… их убрать надо совсем») but `NoticePolicy`, which decides whether
 * anything reaches a human at all, and `Notice`, the ONE bottom surface that shows it when it
 * does. Read `NoticePolicy.kt`; the rules and the reasoning live there.
 *
 * The short version, for anyone porting an upstream call site:
 *
 *   - `toastSuccess(…)` shows NOTHING, ever. The state change is the confirmation.
 *   - `toast(…)` / `toastError(…)` show the string ONLY if its resource id is on the policy's
 *     allow-list. A new upstream message is silent until somebody reads it and lists it.
 *   - the `CharSequence` overloads show nothing at all, because a built string has no id the
 *     policy can recognise, and everything that is built by concatenation today is a counter, a
 *     node summary or an exception message. To say something, pass a resource id.
 */

/** A note about what happened, if the policy recognises it. @see NoticePolicy */
fun Context.toast(message: Int) = Notice.say(this, message, NoticePolicy.Kind.INFO)

/** Built text has no identity the policy can check, so it is never shown. @see NoticePolicy */
@Suppress("UNUSED_PARAMETER")
fun Context.toast(message: CharSequence) = Unit

/** Success is silent — the screen behind the message already said it. @see NoticePolicy */
@Suppress("UNUSED_PARAMETER")
fun Context.toastSuccess(message: Int) = Unit

/** Success is silent — the screen behind the message already said it. @see NoticePolicy */
@Suppress("UNUSED_PARAMETER")
fun Context.toastSuccess(message: CharSequence) = Unit

/** A failure the user has to act on, if the policy recognises it. @see NoticePolicy */
fun Context.toastError(message: Int) = Notice.say(this, message, NoticePolicy.Kind.FAILURE)

/** Built text has no identity the policy can check, so it is never shown. @see NoticePolicy */
@Suppress("UNUSED_PARAMETER")
fun Context.toastError(message: CharSequence) = Unit

/** U+00A0: число и единица не расходятся по строкам. */
private const val UNIT_SPACE = '\u00A0'

/** Единицы объёма по-русски, как у кольца на вкладке «Аккаунт» и у карточки тарифа. */
private val BYTE_UNITS = arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ", "ПБ")

/**
 * Число с одним знаком после запятой, запятой, а не точкой. Цифры собираются под [Locale.US],
 * чтобы телефон на фарси или бенгали не получил свои цифры, и только разделитель меняется - тот же
 * приём, что у `SubscriptionPagerAdapter.formatBytes`.
 */
private fun Double.oneDecimal(): String = String.format(Locale.US, "%.1f", this).replace('.', ',')

/**
 * Скорость так, как её пишет интерфейс: «1,2 МБ/с», «240 КБ/с», «0 КБ/с».
 *
 * ЕДИНИЦЫ ПО-РУССКИ, И ОДНИ НА ВСЁ ПРИЛОЖЕНИЕ. Здесь стояло «1,2 MB/s» латиницей, а разделитель
 * брался из языка ТЕЛЕФОНА: на телефоне с английским языком Главная и шторка писали «1.2 MB/s» -
 * посреди русского интерфейса, рядом с «12,4 ГБ» на вкладке «Аккаунт». Остались байты, как в том
 * виде Главной, который владелец назвал правильным («↑ 1,0 KB/s 00:11:52 ↓ 1,2 KB/s»): те же
 * числа, по-русски.
 *
 * Начинается с КБ/с - скорость меньше килобайта всё равно пишется в КБ/с («0,4 КБ/с»), а не
 * третьей единицей. Один знак после запятой до 100, дальше без него («248 КБ/с»); к следующей
 * единице - с 1000, так что в числе не больше трёх цифр до запятой. Ноль - одним видом, «0 КБ/с»,
 * как на Главной в покое (@string/home_speed_zero).
 *
 * @receiver байт в секунду.
 */
fun Long.toSpeedString(): String {
    var value = coerceAtLeast(0L) / 1024.0
    var unit = 1
    while (value >= 999.5 && unit < BYTE_UNITS.lastIndex) {
        value /= 1024.0
        unit++
    }
    val figure = when {
        value < 0.05 -> "0"
        value < 99.95 -> value.oneDecimal()
        else -> String.format(Locale.US, "%.0f", value)
    }
    return "$figure$UNIT_SPACE${BYTE_UNITS[unit]}/с"
}

/**
 * Объём так, как его пишет интерфейс: «12,4 ГБ», «512 Б», «0 Б».
 *
 * Тот же счёт, что у кольца на вкладке «Аккаунт» (`SubscriptionPagerAdapter.formatBytes`): по
 * 1024, с единицы, до которой дорос объём, один знак после запятой. Здесь стояли латинские «GB» и
 * порог 1000 вместо 1024, и одни и те же байты подписки читались на Главной и на «Аккаунте»
 * по-разному.
 *
 * @receiver байт.
 */
fun Long.toTrafficString(): String {
    if (this < 1024L) return "${coerceAtLeast(0L)}$UNIT_SPACE${BYTE_UNITS[0]}"
    var value = toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < BYTE_UNITS.lastIndex) {
        value /= 1024.0
        unit++
    }
    return "${value.oneDecimal()}$UNIT_SPACE${BYTE_UNITS[unit]}"
}

val URI.idnHost: String
    get() = host?.replace("[", "")?.replace("]", "").orEmpty()

/**
 * Removes all whitespace from the string.
 *
 * @return The string without whitespace.
 */
fun String?.removeWhiteSpace(): String? = this?.replace(" ", "")

/**
 * Returns null if the string is null or blank, otherwise returns the string itself.
 *
 * @return The string or null.
 */
fun String?.nullIfBlank(): String? = this?.takeIf { it.isNotBlank() }

// `String.toLongEx()` AND `Context.listenForPackageChanges()` USED TO STAND HERE, both from the
// донор and both without a caller in this app. The second is the one worth naming: it registered a
// PACKAGE_ADDED / PACKAGE_REMOVED receiver with `RECEIVER_EXPORTED` on API 33+ and unregistered it
// from inside its own `onReceive`, i.e. an exported receiver whose lifetime was one broadcast — a
// shape nothing here needs and nobody was maintaining. The per-app proxy screen reads the installed
// list when it opens; it does not watch for changes.

/**
 * Retrieves a serializable object from the Bundle.
 *
 * @param key The key of the serializable object.
 * @return The serializable object, or null if not found.
 */
inline fun <reified T : Serializable> Bundle.serializable(key: String): T? = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> getSerializable(key, T::class.java)
    else -> @Suppress("DEPRECATION") getSerializable(key) as? T
}

/**
 * Retrieves a serializable object from the Intent.
 *
 * @param key The key of the serializable object.
 * @return The serializable object, or null if not found.
 */
inline fun <reified T : Serializable> Intent.serializable(key: String): T? = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> getSerializableExtra(key, T::class.java)
    else -> @Suppress("DEPRECATION") getSerializableExtra(key) as? T
}

/**
 * Checks if the CharSequence is not null and not empty.
 *
 * @return True if the CharSequence is not null and not empty, false otherwise.
 */
fun CharSequence?.isNotNullEmpty(): Boolean = !this.isNullOrBlank()

fun String.concatUrl(vararg paths: String): String {
    val builder = StringBuilder(this.trimEnd('/'))

    paths.forEach { path ->
        val trimmedPath = path.trim('/')
        if (trimmedPath.isNotEmpty()) {
            builder.append('/').append(trimmedPath)
        }
    }

    return builder.toString()
}

/**
 * Checks if the config type is a group type (PolicyGroup or ProxyChain).
 *
 * @return True if the config type is PolicyGroup or ProxyChain, false otherwise.
 */
fun EConfigType.isGroupType(): Boolean {
    return this == EConfigType.POLICYGROUP || this == EConfigType.PROXYCHAIN
}

/**
 * Checks if the config type is a complex type (Custom, PolicyGroup, or ProxyChain).
 *
 * @return True if the config type is Custom, PolicyGroup, or ProxyChain, false otherwise.
 */
fun EConfigType.isComplexType(): Boolean {
    return this == EConfigType.CUSTOM || this == EConfigType.POLICYGROUP || this == EConfigType.PROXYCHAIN
}
