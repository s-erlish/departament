package com.v2ray.ang.handler

import android.text.TextUtils
import android.util.Log
import com.tencent.mmkv.MMKV
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock

/**
 * MMKV без нативной библиотеки: словарь на каждый ID хранилища, чтобы код, который пишет в
 * [MmkvManager], можно было прогнать на JVM целиком.
 *
 * Подменяются три вещи, и только они: `MMKV.mmkvWithID` (отдаёт словарь), `android.util.Log` (его
 * зовёт LogUtil) и `TextUtils.isEmpty` (с него начинается разбор строки ответа). В JVM все три -
 * заглушки Android, которые бросают исключение.
 *
 * [MmkvManager] держит хранилища лениво, на весь процесс JVM, поэтому словари тоже живут весь
 * процесс и очищаются в [start] и [stop], а не создаются заново.
 */
class InMemoryStores {

    private var mmkvStatic: MockedStatic<MMKV>? = null
    private var logStatic: MockedStatic<Log>? = null
    private var textUtilsStatic: MockedStatic<TextUtils>? = null

    fun start() {
        clear()
        mmkvStatic = Mockito.mockStatic(MMKV::class.java).also {
            it.`when`<MMKV> { MMKV.mmkvWithID(anyString(), anyInt()) }.thenAnswer { inv -> storeFor(inv.getArgument(0)) }
        }
        logStatic = Mockito.mockStatic(Log::class.java)
        textUtilsStatic = Mockito.mockStatic(TextUtils::class.java).also {
            it.`when`<Boolean> { TextUtils.isEmpty(any()) }
                .thenAnswer { inv -> inv.getArgument<CharSequence?>(0).isNullOrEmpty() }
        }
    }

    fun stop() {
        mmkvStatic?.close()
        logStatic?.close()
        textUtilsStatic?.close()
        clear()
    }

    private companion object {
        val stores = HashMap<String, HashMap<String, Any?>>()
        val fakes = HashMap<String, MMKV>()

        fun clear() = stores.values.forEach { it.clear() }

        fun storeFor(id: String): MMKV = fakes.getOrPut(id) { fake(stores.getOrPut(id) { HashMap() }) }

        fun fake(map: HashMap<String, Any?>): MMKV = Mockito.mock(MMKV::class.java) { inv: InvocationOnMock ->
            val key = inv.arguments.firstOrNull() as? String
            val fallback = inv.arguments.getOrNull(1)
            when (inv.method.name) {
                "encode" -> { map[key!!] = inv.arguments[1]; true }
                "decodeString" -> map[key] as? String ?: fallback
                "decodeBool" -> map[key] as? Boolean ?: fallback ?: false
                "decodeInt" -> map[key] as? Int ?: fallback ?: 0
                "decodeLong" -> map[key] as? Long ?: fallback ?: 0L
                "decodeFloat" -> map[key] as? Float ?: fallback ?: 0f
                "decodeStringSet" -> map[key]
                "containsKey" -> map.containsKey(key)
                "allKeys" -> map.keys.toTypedArray()
                "remove" -> { map.remove(key); inv.mock }
                "removeValueForKey" -> { map.remove(key); null }
                "clearAll" -> { map.clear(); null }
                else -> Mockito.RETURNS_DEFAULTS.answer(inv)
            }
        }
    }
}
