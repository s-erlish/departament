package com.v2ray.ang.auth

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/**
 * Shared Gson for the Departament backend DTOs.
 *
 * The backend legitimately omits or nulls string fields — a Telegram-only user has no
 * `email`, a fresh account has no `currency`, etc. — but the Kotlin DTOs declare those as
 * non-null `String` with `= ""` defaults. Plain [Gson] writes a JSON `null` straight into
 * the field, defeating the default, so the field is actually `null` at runtime and the UI
 * crashes with an NPE the moment it calls a String method on it (this was the Account-tab
 * crash). The null-tolerant String adapter below maps any JSON `null` to `""`, guaranteeing
 * every DTO string is non-null regardless of what the backend sends.
 */
object ApiGson {
    val instance: Gson = GsonBuilder()
        .registerTypeAdapter(String::class.java, object : TypeAdapter<String>() {
            override fun read(reader: JsonReader): String {
                return when (reader.peek()) {
                    JsonToken.NULL -> {
                        reader.nextNull()
                        ""
                    }
                    JsonToken.BOOLEAN -> reader.nextBoolean().toString()
                    else -> reader.nextString()
                }
            }

            override fun write(out: JsonWriter, value: String?) {
                out.value(value)
            }
        })
        .create()
}
