package com.v2ray.ang.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ItemRecyclerLogcatBinding
import com.v2ray.ang.ui.component.ChipBinder
import com.v2ray.ang.viewmodel.LogcatViewModel

/**
 * One `logcat -v time` line per row (A-35).
 *
 * `-v time` gives `MM-DD HH:MM:SS.mmm L/tag(pid): message`, and the old adapter threw away
 * everything but a mangled tag and the message - so the level, which is the one thing a person
 * scanning a log is looking for, never reached the screen. It is parsed here and shown as a status
 * chip on the lines that went wrong, in the three sanctioned hues (R12: warn and error, and no
 * fourth). An ordinary line carries no chip, because a chip on every row is decoration.
 *
 * Parsing is deliberately defensive: a line that does not match the format is shown whole rather
 * than dropped. A log viewer that hides lines it did not understand is worse than useless.
 */
class LogcatRecyclerAdapter(
    private val viewModel: LogcatViewModel,
    private val onLongClick: ((String) -> Boolean)? = null
) : RecyclerView.Adapter<LogcatRecyclerAdapter.LogViewHolder>() {

    override fun getItemCount() = viewModel.getAll().size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder =
        LogViewHolder(ItemRecyclerLogcatBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        val line = viewModel.getAll().getOrNull(position).orEmpty()
        val parsed = parse(line)
        val binding = holder.binding
        val context = binding.root.context

        binding.logContent.text = parsed.message
        binding.logTag.text = listOfNotNull(parsed.time, parsed.tag).joinToString(SEPARATOR)
        binding.logTag.isVisible = parsed.time != null || parsed.tag != null

        val level = parsed.level
        if (level == null) {
            binding.logLevel.isVisible = false
        } else {
            binding.logLevel.isVisible = true
            ChipBinder.bind(
                chip = binding.logLevel,
                text = context.getString(level.labelRes),
                tone = level.tone,
            )
        }

        binding.root.setOnLongClickListener { onLongClick?.invoke(line) ?: false }
    }

    class LogViewHolder(val binding: ItemRecyclerLogcatBinding) :
        RecyclerView.ViewHolder(binding.root)

    /** The two levels worth interrupting a scan for. Everything quieter carries no chip. */
    private enum class Level(val labelRes: Int, val tone: ChipBinder.Tone) {
        WARN(R.string.log_level_warn, ChipBinder.Tone.WARN),
        ERROR(R.string.log_level_error, ChipBinder.Tone.ERROR),
    }

    private data class Parsed(
        val time: String?,
        val tag: String?,
        val level: Level?,
        val message: String,
    )

    /**
     * `07-26 14:23:01.123 E/GoLog(1234): message` -> time `14:23:01`, tag `GoLog`, level ERROR.
     * Anything that does not match returns the whole line as the message.
     */
    private fun parse(line: String): Parsed {
        val split = line.split("): ", limit = 2)
        if (split.size < 2) return Parsed(null, null, null, line)

        val head = split[0]
        val message = split[1].trim()

        // The level letter is the single character before the slash that opens the tag.
        val slash = head.indexOf('/')
        val open = head.lastIndexOf('(')
        if (slash <= 0 || open <= slash) return Parsed(null, null, null, line)

        val level = when (head[slash - 1]) {
            'W' -> Level.WARN
            'E', 'F' -> Level.ERROR
            else -> null
        }
        val tag = head.substring(slash + 1, open).trim().ifEmpty { null }
        // «07-26 14:23:01.123» -> «14:23:01»: the date repeats on every line and the milliseconds
        // are noise at this density.
        val time = head.substring(0, slash - 1).trim()
            .substringAfter(' ', "")
            .substringBefore('.')
            .ifEmpty { null }

        return Parsed(time, tag, level, message)
    }

    private companion object {
        const val SEPARATOR = "  ·  "
    }
}
