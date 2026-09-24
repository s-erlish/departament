package com.v2ray.ang.util

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.reflect.TypeToken
import com.v2ray.ang.AppConfig
import java.lang.reflect.Type

object JsonUtil {
    private var gson = Gson()

    /**
     * Converts an object to its JSON representation.
     *
     * @param src The object to convert.
     * @return The JSON representation of the object.
     */
    fun toJson(src: Any?): String {
        return gson.toJson(src)
    }

    /**
     * Parses a JSON string into an object of the specified class.
     *
     * @param src The JSON string to parse.
     * @param cls The class of the object to parse into.
     * @return The parsed object.
     */
    fun <T> fromJson(src: String, cls: Class<T>): T? {
        return gson.fromJson(src, cls)
    }

    /**
     * Safely parses a JSON string into an object of the specified class.
     * Returns null if parsing fails instead of throwing an exception.
     *
     * @param src The JSON string to parse.
     * @param cls The class of the object to parse into.
     * @return The parsed object, or null if parsing fails.
     */
    fun <T> fromJsonSafe(src: String, cls: Class<T>): T? {
        return try {
            gson.fromJson(src, cls)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to parse JSON", e)
            null
        }
    }

    /**
     * The pretty-printer, BUILT ONCE.
     *
     * A `Gson` is not a call: it is an immutable, thread-safe engine with a list of type-adapter
     * factories and a cache of the reflective adapters it has already resolved, and building one
     * costs about a microsecond on a desktop JVM (measured) before it has serialised anything —
     * more on ART, and the resolved-adapter cache starts empty every time, so a fresh instance also
     * re-derives the reflective adapter for every class it meets.
     *
     * [toJsonPretty] minted one PER CALL, and its callers are loops: the XRAY_JSON import path goes
     * through it TWICE per server (`AngConfigManager.stripVendorRootKey` and the array loop that
     * calls it), so a 100-server подписка refresh built two hundred of these; the real-ping batch
     * builds one per server on the test-config path. The engine has no per-call state — the
     * settings below are fixed and `Gson` is documented thread-safe — so one instance answers every
     * caller, including the batch's own thread pool.
     */
    private val prettyGson: Gson by lazy {
        GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .registerTypeAdapter( // custom serializer is needed here since JSON by default parse number as Double, core will fail to start
                object : TypeToken<Double>() {}.type,
                JsonSerializer { src: Double?, _: Type?, _: JsonSerializationContext? ->
                    JsonPrimitive(
                        src?.toInt()
                    )
                }
            )
            .create()
    }

    /**
     * Converts an object to its pretty-printed JSON representation.
     *
     * @param src The object to convert.
     * @return The pretty-printed JSON representation of the object, or null if the object is null.
     */
    fun toJsonPretty(src: Any?): String? {
        if (src == null)
            return null
        return prettyGson.toJson(src)
    }

    /**
     * Parses a JSON string into a JsonObject.
     *
     * @param src The JSON string to parse.
     * @return The parsed JsonObject, or null if parsing fails.
     */
    fun parseString(src: String?): JsonObject? {
        if (src == null)
            return null
        try {
            return JsonParser.parseString(src).getAsJsonObject()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to parse JSON string", e)
            return null
        }
    }
}