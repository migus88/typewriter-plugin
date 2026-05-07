package games.engineroom.typewriter

import games.engineroom.typewriter.commands.Command
import games.engineroom.typewriter.commands.MoveCaretCommand
import games.engineroom.typewriter.commands.PauseCommand
import games.engineroom.typewriter.commands.ReformatCommand
import games.engineroom.typewriter.commands.TriggerAutocompleteCommand
import games.engineroom.typewriter.commands.WriteTextCommand
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAwareAction
import kotlin.random.Random

class TypeWriterAction : DumbAwareAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        TypeWriterDialog(project).show()
    }
}

private val OPEN_TO_CLOSE = mapOf('{' to '}', '(' to ')', '[' to ']')
private val CLOSE_TO_OPEN = OPEN_TO_CLOSE.entries.associate { (k, v) -> v to k }

private sealed class Classification {
    data class AutoPair(
        val open: Char,
        val leading: String,
        val trailing: String,
        val close: Char,
    ) : Classification()

    /** Source position is part of leading whitespace already inserted by the auto-pair sequence. */
    object ConsumeOnly : Classification()

    /** Source position is part of trailing whitespace or the closer — caret steps over it. */
    object SkipChar : Classification()
}

fun executeTyping(
    editor: Editor,
    text: String,
    openingSequence: String,
    closingSequence: String,
    delay: Long,
    jitter: Int,
    completionDelay: Long,
    scheduler: TypewriterExecutorService,
    onDone: () -> Unit,
) {
    val templateRegex = """${Regex.escape(openingSequence)}(.*?)${Regex.escape(closingSequence)}""".toRegex()

    // Build a "code only" view (template markers stripped) for bracket-matching.
    val concat = StringBuilder().apply {
        var lastEnd = 0
        for (m in templateRegex.findAll(text)) {
            append(text, lastEnd, m.range.first)
            lastEnd = m.range.last + 1
        }
        append(text, lastEnd, text.length)
    }.toString()

    val classification = classify(concat)

    fun pause(): Int = (delay + Random.nextInt(-jitter, jitter + 1))
        .toInt()
        .coerceAtLeast(0)

    val commands = mutableListOf<Command>()
    var concatPos = 0
    var lastEnd = 0

    fun emitAutoPair(cls: Classification.AutoPair) {
        // Type the opener through TypedAction (single-char route in WriteTextCommand). The IDE
        // auto-pairs the closer for us — we deliberately **do not** type the closer ourselves.
        // The matching source closer is classified `SkipChar` and advances the caret past the
        // auto-paired character when reached.
        //
        // Subsequent leading/trailing inserts go via insertString (multi-char chunks). Each
        // insert pushes the auto-paired closer rightward; after all chunks land, caret is past
        // the trailing whitespace and we move it back by `trailing.length` to land on the
        // body slot.
        commands += WriteTextCommand(cls.open.toString(), pause(), editor)
        for (chunk in chunkWhitespace(cls.leading)) {
            commands += WriteTextCommand(chunk, pause(), editor)
        }
        for (chunk in chunkWhitespace(cls.trailing)) {
            commands += WriteTextCommand(chunk, pause(), editor)
        }
        val backDelta = -cls.trailing.length
        if (backDelta != 0) commands += MoveCaretCommand(backDelta, pause(), editor)
    }

    fun appendSegment(segment: String) {
        var i = 0
        while (i < segment.length) {
            val pos = concatPos + i
            when (val cls = classification[pos]) {
                is Classification.AutoPair -> {
                    emitAutoPair(cls)
                    i++
                }

                Classification.ConsumeOnly -> {
                    i++
                }

                Classification.SkipChar -> {
                    var count = 0
                    while (i + count < segment.length &&
                        classification[concatPos + i + count] == Classification.SkipChar
                    ) {
                        count++
                    }
                    commands += MoveCaretCommand(count, pause(), editor)
                    i += count
                }

                null -> {
                    val c = segment[i]
                    if (isLineStart(concat, pos) && isIndentChar(c)) {
                        var len = 0
                        while (i + len < segment.length &&
                            classification[concatPos + i + len] == null &&
                            isIndentChar(segment[i + len])
                        ) {
                            len++
                        }
                        commands += WriteTextCommand(segment.substring(i, i + len), pause(), editor)
                        i += len
                    } else {
                        commands += WriteTextCommand(c.toString(), pause(), editor)
                        i++
                    }
                }
            }
        }
        concatPos += segment.length
    }

    for (m in templateRegex.findAll(text)) {
        appendSegment(text.substring(lastEnd, m.range.first))
        // Don't trim the raw body — for `complete`, the word may legitimately contain leading
        // or trailing whitespace (e.g. `{{complete:3:private }}` to type "private " followed by
        // the next token). Trim only the bits we need numeric/keyword parsing for.
        val raw = m.value
            .substringAfter(openingSequence)
            .substringBeforeLast(closingSequence)
        val firstColon = raw.indexOf(':')
        val name = (if (firstColon < 0) raw else raw.substring(0, firstColon)).trim()
        val rest = if (firstColon < 0) "" else raw.substring(firstColon + 1)
        var consumedAfterTemplate = 0
        when (name) {
            "pause" -> commands += PauseCommand(rest.trim().toLongOrNull() ?: 0L)
            "reformat" -> commands += ReformatCommand(editor)
            "complete" -> {
                // Format: complete:N:Word — type N chars of Word, trigger auto-popup, wait
                // completionDelay ms, then drop the rest of Word in a single tick.
                val secondColon = rest.indexOf(':')
                if (secondColon > 0) {
                    val n = rest.substring(0, secondColon).trim().toIntOrNull() ?: 0
                    val word = rest.substring(secondColon + 1)
                    if (word.isNotEmpty()) {
                        val effectiveN = n.coerceIn(0, word.length)
                        val prefix = word.substring(0, effectiveN)
                        var tail = word.substring(effectiveN)
                        // Workaround for Rider's C# typing-assist: when the doc lands on
                        // `<keyword> ` (keyword + trailing space) at any indent, the formatter
                        // "fixes" the line by shuffling whitespace — moving a space from after
                        // the keyword to the start of the line. To avoid the intermediate
                        // doc state, absorb the next whitespace run + the immediately-following
                        // non-whitespace char into the tail so the doc lands directly on
                        // `<keyword> <next_char>` in one insert.
                        val afterStart = m.range.last + 1
                        var idx = afterStart
                        val sb = StringBuilder()
                        while (idx < text.length && text[idx] != '\n' && text[idx].isWhitespace()) {
                            sb.append(text[idx])
                            idx++
                        }
                        val wsLen = sb.length
                        if (wsLen > 0 && idx < text.length && !text[idx].isWhitespace()) {
                            sb.append(text[idx])
                            idx++
                        }
                        // Only absorb when we got *both* whitespace and a non-whitespace
                        // boundary char; otherwise the absorption doesn't avoid the bad
                        // intermediate state, just delays it.
                        if (sb.length > wsLen) {
                            tail += sb.toString()
                            consumedAfterTemplate = sb.length
                        }
                        if (prefix.isNotEmpty()) commands += WriteTextCommand(prefix, pause(), editor)
                        commands += TriggerAutocompleteCommand(completionDelay, editor)
                        if (tail.isNotEmpty()) commands += WriteTextCommand(tail, pause(), editor)
                    }
                }
            }
        }
        lastEnd = m.range.last + 1 + consumedAfterTemplate
        concatPos += consumedAfterTemplate
    }
    appendSegment(text.substring(lastEnd))

    scheduler.start(commands, onDone)
}

private fun isIndentChar(c: Char): Boolean = c == ' ' || c == '\t'

private fun isLineStart(concat: String, pos: Int): Boolean = pos == 0 || concat[pos - 1] == '\n'

/**
 * Split a whitespace string into typing chunks. Each `\n` carries its following indent run, so
 * `"\n    "` is one chunk (one tick), and `"\n\n    "` is two chunks (`"\n"` then `"\n    "`).
 * A pure indent run with no preceding newline is also its own chunk.
 */
private fun chunkWhitespace(ws: String): List<String> {
    if (ws.isEmpty()) return emptyList()
    val result = mutableListOf<String>()
    var i = 0

    // Leading non-newline whitespace (indent without a preceding `\n`) is its own chunk.
    var leadingEnd = 0
    while (leadingEnd < ws.length && ws[leadingEnd] != '\n') leadingEnd++
    if (leadingEnd > 0) {
        result += ws.substring(0, leadingEnd)
        i = leadingEnd
    }

    while (i < ws.length) {
        val start = i
        i++ // the `\n`
        while (i < ws.length && ws[i] != '\n') i++
        result += ws.substring(start, i)
    }
    return result
}

/**
 * Stack-based bracket matching producing a per-index classification map. Bracket pairs that are
 * correctly nested produce an [Classification.AutoPair] at the opener index, [Classification.ConsumeOnly]
 * over the leading whitespace, and [Classification.SkipChar] over the trailing whitespace and the
 * closer. Everything else is unclassified (`null`) and treated as a regular character.
 */
private fun classify(content: String): Map<Int, Classification> {
    val pairs = mutableMapOf<Int, Int>()
    val stack = ArrayDeque<Pair<Char, Int>>()
    for (i in content.indices) {
        val c = content[i]
        when {
            c in OPEN_TO_CLOSE -> stack.addLast(c to i)
            c in CLOSE_TO_OPEN -> {
                val top = stack.lastOrNull()
                if (top != null && top.first == CLOSE_TO_OPEN[c]) {
                    pairs[top.second] = i
                    stack.removeLast()
                }
            }
        }
    }

    val result = mutableMapOf<Int, Classification>()
    for ((openIdx, closeIdx) in pairs) {
        var firstNonWs = openIdx + 1
        while (firstNonWs < closeIdx && content[firstNonWs].isWhitespace()) firstNonWs++

        val leadingEnd: Int
        val trailingStart: Int
        if (firstNonWs >= closeIdx) {
            // No body — everything between is whitespace. Treat it all as leading.
            leadingEnd = closeIdx
            trailingStart = closeIdx
        } else {
            var lastNonWs = closeIdx - 1
            while (lastNonWs > openIdx && content[lastNonWs].isWhitespace()) lastNonWs--
            leadingEnd = firstNonWs
            trailingStart = lastNonWs + 1
        }

        result[openIdx] = Classification.AutoPair(
            open = content[openIdx],
            leading = content.substring(openIdx + 1, leadingEnd),
            trailing = content.substring(trailingStart, closeIdx),
            close = content[closeIdx],
        )
        for (i in (openIdx + 1) until leadingEnd) result[i] = Classification.ConsumeOnly
        for (i in trailingStart until closeIdx) result[i] = Classification.SkipChar
        result[closeIdx] = Classification.SkipChar
    }
    return result
}
