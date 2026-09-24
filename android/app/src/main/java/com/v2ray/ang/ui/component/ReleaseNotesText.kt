package com.v2ray.ang.ui.component

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan

/**
 * Описание выпуска с GitHub — для экрана, а не как есть.
 *
 * Описание пишется разметкой GitHub (`release-notes/android-v*.md`), и экран обновления показывал её
 * буквально: «## Что в этом выпуске», «---», строка о лицензии. Здесь из него берётся раздел «Что в
 * этом выпуске» — остальные разделы объясняют, как поставить и обновить, и рядом с кнопкой
 * «Обновить» лишние, — пункты «- » становятся «•» с переносом строк под текст пункта, а «**»
 * пропадают. Если такого раздела нет, показывается всё описание до черты «---», с заголовками
 * полужирным. [paint] — шрифт поля, в котором текст покажут: по нему меряется отступ пункта.
 */
object ReleaseNotesText {

    private const val WHATS_NEW = "Что в этом выпуске"

    private const val BULLET = "•  "

    fun whatsNew(body: String?, paint: TextPaint): CharSequence? {
        val lines = body.orEmpty().replace("\r", "").lines()
            .takeWhile { it.trim() != "---" }
        val start = lines.indexOfFirst { it.startsWith("## ") && it.contains(WHATS_NEW, ignoreCase = true) }
        val section = if (start >= 0) {
            val rest = lines.drop(start + 1)
            rest.takeWhile { !it.startsWith("## ") }
        } else {
            lines
        }
        return format(section, paint.measureText(BULLET).toInt())
    }

    private fun format(lines: List<String>, bulletIndent: Int): CharSequence? {
        val out = SpannableStringBuilder()
        var blank = false
        for (raw in lines) {
            val line = raw.trimEnd().replace("**", "")
            if (line.isBlank()) {
                blank = out.isNotEmpty()
                continue
            }
            val bullet = line.startsWith("- ") || line.startsWith("* ")
            if (out.isNotEmpty()) {
                out.append("\n")
                // Пустая строка между абзацами и между пунктами — в половину высоты: воздух, а не дыра.
                if (blank || bullet) {
                    val from = out.length
                    out.append("\n")
                    out.setSpan(RelativeSizeSpan(0.5f), from, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            blank = false
            when {
                line.startsWith("#") -> {
                    val text = line.trimStart('#').trim()
                    val from = out.length
                    out.append(text)
                    out.setSpan(StyleSpan(Typeface.BOLD), from, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                bullet -> {
                    val from = out.length
                    out.append(BULLET).append(line.substring(2).trim())
                    out.setSpan(LeadingMarginSpan.Standard(0, bulletIndent), from, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> out.append(line.trim())
            }
        }
        return out.takeIf { it.isNotEmpty() }
    }
}
