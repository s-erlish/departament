package com.v2ray.ang.util

import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import java.util.Locale

object LogUtil {

    private const val DEFAULT_LEVEL = "warning"
    private const val CACHE_UNSET = Int.MIN_VALUE

    @Volatile
    private var cachedMinPriority: Int = CACHE_UNSET

    private fun parsePriority(level: String?): Int {
        return when ((level ?: DEFAULT_LEVEL).lowercase(Locale.US)) {
            "verbose" -> Log.VERBOSE
            "debug" -> Log.DEBUG
            "info" -> Log.INFO
            "warn", "warning" -> Log.WARN
            "error" -> Log.ERROR
            "none", "off" -> Int.MAX_VALUE
            else -> Log.WARN
        }
    }

    @Suppress("unused")
    fun refreshLogLevel() {
        cachedMinPriority = parsePriority(MmkvManager.decodeSettingsString(AppConfig.PREF_LOGLEVEL, DEFAULT_LEVEL))
    }

    private fun minPriority(): Int {
        val cached = cachedMinPriority
        if (cached != CACHE_UNSET) {
            return cached
        }

        return synchronized(this) {
            val current = cachedMinPriority
            if (current != CACHE_UNSET) {
                current
            } else {
                //  Уровень лежит в MMKV, а журнал пишут и раньше MMKV.initialize — в самом начале запуска
                //  и в тестах на JVM. Там MMKV бросает, и запись в журнал роняла того, кто писал. Тогда —
                //  уровень по умолчанию, и он не запоминается: прочтётся, когда MMKV будет готов.
                val stored = try {
                    MmkvManager.decodeSettingsString(AppConfig.PREF_LOGLEVEL, DEFAULT_LEVEL)
                } catch (e: IllegalStateException) {
                    return@synchronized parsePriority(DEFAULT_LEVEL)
                }
                parsePriority(stored).also {
                    cachedMinPriority = it
                }
            }
        }
    }

    private fun isEnabled(priority: Int): Boolean {
        return priority >= minPriority()
    }

    private fun log(priority: Int, tag: String, message: String, throwable: Throwable? = null) {
        if (!isEnabled(priority)) return

        //  Журнал никого не роняет. Вне Android (тесты на JVM) android.util.Log — заглушка, которая
        //  бросает на каждый вызов, и строка журнала в ветке ошибки превращала «вернуть false» в падение.
        try {
            when {
                throwable == null -> Log.println(priority, tag, message)
                priority >= Log.ERROR -> Log.e(tag, message, throwable)
                priority == Log.WARN -> Log.w(tag, message, throwable)
                priority == Log.INFO -> Log.i(tag, message, throwable)
                priority == Log.DEBUG -> Log.d(tag, message, throwable)
                else -> Log.v(tag, message, throwable)
            }
        } catch (e: RuntimeException) {
            // nothing to report it to
        }
    }

    fun d(tag: String = AppConfig.TAG, message: String) = log(Log.DEBUG, tag, message)
    fun i(tag: String = AppConfig.TAG, message: String) = log(Log.INFO, tag, message)
    fun w(tag: String = AppConfig.TAG, message: String) = log(Log.WARN, tag, message)
    fun e(tag: String = AppConfig.TAG, message: String) = log(Log.ERROR, tag, message)

    fun d(tag: String = AppConfig.TAG, message: String, throwable: Throwable) = log(Log.DEBUG, tag, message, throwable)
    fun i(tag: String = AppConfig.TAG, message: String, throwable: Throwable) = log(Log.INFO, tag, message, throwable)
    fun w(tag: String = AppConfig.TAG, message: String, throwable: Throwable) = log(Log.WARN, tag, message, throwable)
    fun e(tag: String = AppConfig.TAG, message: String, throwable: Throwable) = log(Log.ERROR, tag, message, throwable)
}

