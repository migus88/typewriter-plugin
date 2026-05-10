package games.engineroom.typewriter

import games.engineroom.typewriter.commands.BackspaceCommand
import games.engineroom.typewriter.commands.BackspaceHoldCommand
import games.engineroom.typewriter.commands.CaretDirection
import games.engineroom.typewriter.commands.CaretMoveByDirectionCommand
import games.engineroom.typewriter.commands.Command
import games.engineroom.typewriter.commands.EnterCommand
import games.engineroom.typewriter.commands.EscapeCommand
import games.engineroom.typewriter.commands.GotoCommand
import games.engineroom.typewriter.commands.ImportCommand
import games.engineroom.typewriter.commands.KeyPressCommand
import games.engineroom.typewriter.commands.MoveCaretCommand
import games.engineroom.typewriter.commands.PauseCommand
import games.engineroom.typewriter.commands.PressTabCommand
import games.engineroom.typewriter.commands.ReformatCommand
import games.engineroom.typewriter.commands.TabCommand
import games.engineroom.typewriter.commands.TriggerAutocompleteCommand
import games.engineroom.typewriter.commands.WriteTextCommand
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import java.awt.event.KeyEvent
import kotlin.random.Random

class TypeWriterAction : DumbAwareAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val existing = project.getUserData(DIALOG_KEY)
        if (existing != null && !existing.isDisposed) {
            existing.bringToFront()
            return
        }
        val dialog = TypeWriterDialog(project)
        project.putUserData(DIALOG_KEY, dialog)
        dialog.show()
    }

    companion object {
        private val DIALOG_KEY: Key<TypeWriterDialog> = Key.create("typewriter.dialog")

        /** Called by the dialog on dispose so a fresh action can open a new instance. */
        fun clearOpenDialog(project: Project, dialog: TypeWriterDialog) {
            if (project.getUserData(DIALOG_KEY) === dialog) {
                project.putUserData(DIALOG_KEY, null)
            }
        }
    }
}

private val OPEN_TO_CLOSE = mapOf('{' to '}', '(' to ')', '[' to ']')
private val CLOSE_TO_OPEN = OPEN_TO_CLOSE.entries.associate { (k, v) -> v to k }

/** Default pause between typing a snippet abbreviation and pressing Tab to expand it. */
private const val SNIP_DEFAULT_DELAY_MS: Long = 200L

/**
 * Built-in macro names. A custom macro can't shadow these — the validator refuses such names,
 * and the expander treats a body matching one of them as a built-in regardless of whether a
 * custom macro of the same name was somehow persisted (e.g. by hand-editing typewriter.xml).
 */
val BUILT_IN_MACRO_NAMES: Set<String> = setOf(
    "pause", "reformat", "caret", "carret", "import",
    "backspace", "backspace-hold", "goto", "snip", "key", "complete", "br",
)

/**
 * Pre-process [text] by substituting `{{name}}` (or `{{name:arg1:arg2}}`) references with the
 * matching custom macro's content. Inside the content, parameter references of the form
 * `$paramName$` are replaced with the corresponding positional argument; missing args resolve to
 * the empty string, extra args are ignored.
 *
 * Recursive — a custom macro's content may itself reference custom or built-in macros. Cycles
 * are broken by tracking the in-flight name set: a macro can't reappear inside its own
 * expansion. [maxDepth] is a hard depth cap for pathologically deep chains.
 *
 * Bodies whose name (the part before the first `:`) matches a built-in are skipped — those go
 * through the regular typing pipeline. Anything else with no matching custom macro is left
 * intact for the pipeline's regex to ignore.
 */
fun expandCustomMacros(
    text: String,
    customMacros: List<CustomMacroData>,
    openingSequence: String,
    closingSequence: String,
    maxDepth: Int = 16,
): String {
    if (customMacros.isEmpty()) return text
    val byName = customMacros.associateBy { it.name }
    return expandRecursive(text, byName, openingSequence, closingSequence, emptySet(), maxDepth)
}

private fun expandRecursive(
    text: String,
    byName: Map<String, CustomMacroData>,
    openingSequence: String,
    closingSequence: String,
    visiting: Set<String>,
    maxDepth: Int,
): String {
    if (visiting.size >= maxDepth) return text
    val regex = """${Regex.escape(openingSequence)}(.*?)${Regex.escape(closingSequence)}""".toRegex()
    val sb = StringBuilder()
    var lastEnd = 0
    var changed = false
    for (m in regex.findAll(text)) {
        sb.append(text, lastEnd, m.range.first)
        val body = m.groupValues[1]
        val firstColon = body.indexOf(':')
        val callName = if (firstColon < 0) body else body.substring(0, firstColon)
        val callArgs: List<String> = if (firstColon < 0) emptyList()
            else body.substring(firstColon + 1).split(':')
        val def = byName[callName]
        val expansion = if (callName !in BUILT_IN_MACRO_NAMES && def != null && callName !in visiting) {
            substituteMacroParams(def.content, def.parameters, callArgs)
        } else null
        if (expansion != null) {
            sb.append(
                expandRecursive(
                    expansion,
                    byName,
                    openingSequence,
                    closingSequence,
                    visiting + callName,
                    maxDepth,
                )
            )
            changed = true
        } else {
            sb.append(m.value)
        }
        lastEnd = m.range.last + 1
    }
    sb.append(text, lastEnd, text.length)
    return if (changed) sb.toString() else text
}

/**
 * Replace each `$paramName$` in [content] with the corresponding positional argument. Unknown
 * `$word$` sequences are left as-is so they don't interfere with typed `$` characters in
 * scripts that don't use parameter substitution. Single-pass over the content, so a substituted
 * value can't be re-substituted (avoids surprises when an arg literally contains a parameter
 * placeholder).
 */
private fun substituteMacroParams(
    content: String,
    paramNames: List<String>,
    args: List<String>,
): String {
    if (paramNames.isEmpty()) return content
    val byName = paramNames.withIndex().associate { (i, name) -> name to (args.getOrElse(i) { "" }) }
    return Regex("""\$(\w+)\$""").replace(content) { match ->
        byName[match.groupValues[1]] ?: match.value
    }
}

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
    preExecutionPause: Long,
    scheduler: TypewriterExecutorService,
    customMacros: List<CustomMacroData> = emptyList(),
    onDone: () -> Unit,
) {
    @Suppress("NAME_SHADOWING")
    val text = expandCustomMacros(text, customMacros, openingSequence, closingSequence)
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
    if (preExecutionPause > 0) commands += PauseCommand(preExecutionPause)
    var concatPos = 0
    var lastEnd = 0

    // True when the cursor is on a line whose indentation is already established — so any
    // subsequent source indent characters are redundant and get dropped. Two situations:
    //
    //   1. Right after an [EnterCommand], the IDE has just auto-indented the new line.
    //   2. At the very start of a run, the user has positioned the caret on a blank indented line
    //      (the typical screencast setup: double-click into an existing class body). Typing the
    //      script's own leading indent on top would double the indentation.
    //
    // Anything that moves the caret or writes text clears the flag; pause leaves it alone since
    // it has no editor side-effects.
    var indentOwnedByIde = true

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
                    indentOwnedByIde = false
                    emitAutoPair(cls)
                    i++
                }

                Classification.ConsumeOnly -> {
                    i++
                }

                Classification.SkipChar -> {
                    indentOwnedByIde = false
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
                    when {
                        c == '\n' -> {
                            // Press Enter via the IDE's action handler so it auto-indents the
                            // new line. Flag the next indent run as IDE-owned so we don't double up.
                            commands += EnterCommand(pause(), editor)
                            indentOwnedByIde = true
                            i++
                        }
                        indentOwnedByIde && isIndentChar(c) -> {
                            // The IDE just auto-indented after the EnterCommand above; drop the
                            // source's redundant indent characters. We stay in this branch until
                            // a non-indent char (or any caret/text emission elsewhere) clears the
                            // flag, so multi-char indent runs get fully consumed.
                            var len = 0
                            while (i + len < segment.length &&
                                classification[concatPos + i + len] == null &&
                                isIndentChar(segment[i + len])
                            ) {
                                len++
                            }
                            i += len
                        }
                        else -> {
                            indentOwnedByIde = false
                            commands += WriteTextCommand(c.toString(), pause(), editor)
                            i++
                        }
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
            // Pause has no editor side-effects, so it must not clobber `indentOwnedByIde` —
            // a `\n` followed by `{{pause}}` followed by source indent should still skip the
            // indent (the IDE just auto-indented before the pause).
            "pause" -> commands += PauseCommand(rest.trim().toLongOrNull() ?: 0L)
            // {{br}} — suppress the very next character if (and only if) it's `\n`. Used to
            // keep the source script readable on multiple lines while having the animator type
            // the surrounding text as a single line. No-op when the next char isn't `\n` or
            // when there is no next char. Only the line break itself is consumed; any indent
            // that follows is processed normally by the next appendSegment (and gets dropped
            // by the indentOwnedByIde rule only if a real Enter just preceded — which `{{br}}`
            // explicitly avoided).
            "br" -> {
                val nextSrcIdx = m.range.last + 1
                if (nextSrcIdx < text.length && text[nextSrcIdx] == '\n') {
                    consumedAfterTemplate = 1
                }
            }
            "reformat" -> {
                indentOwnedByIde = false
                commands += ReformatCommand(editor)
            }
            // "carret" is the misspelling-tolerant alias — the canonical name is "caret".
            "caret", "carret" -> {
                // Format: caret:DIRECTION:N — emit N single-step commands so each press takes a
                // tick. Unknown direction or non-positive count is silently dropped.
                val firstColon = rest.indexOf(':')
                if (firstColon > 0) {
                    val dirStr = rest.substring(0, firstColon).trim().lowercase()
                    val countStr = rest.substring(firstColon + 1).trim()
                    val count = countStr.toIntOrNull() ?: 0
                    val direction = parseCaretDirection(dirStr)
                    if (direction != null && count > 0) {
                        indentOwnedByIde = false
                        repeat(count) {
                            commands += CaretMoveByDirectionCommand(direction, pause(), editor)
                        }
                    }
                }
            }
            "import" -> {
                // Accepted forms (positional, all parts optional after the keyword):
                //   {{import}}                       — auto, 0 delay, smart pick
                //   {{import:300}}                   — auto, 300 ms popup, smart pick
                //   {{import:Namespace}}             — explicit, 0 delay
                //   {{import:300:Namespace}}         — explicit, 300 ms delay
                //   {{import:300::2}}                — auto, 300 ms popup, second popup item
                //   {{import:300:Namespace:2}}       — explicit (option index ignored)
                //
                // The first segment is treated as a delay only when it's purely digits, so
                // `Foo.Bar` (no leading number) is interpreted as a namespace, not a delay.
                val firstColon = rest.indexOf(':')
                val (delayMs, ns, optIdx) = if (firstColon < 0) {
                    val first = rest.trim()
                    val asInt = first.toIntOrNull()
                    if (asInt != null) Triple(asInt.toLong(), null, 0)
                    else Triple(0L, first.ifBlank { null }, 0)
                } else {
                    val first = rest.substring(0, firstColon).trim()
                    val afterFirst = rest.substring(firstColon + 1)
                    val asInt = first.toIntOrNull()
                    if (asInt == null) {
                        // Whole rest is the namespace (preserves dotted/colon-bearing names).
                        Triple(0L, rest.trim().ifBlank { null }, 0)
                    } else {
                        val secondColon = afterFirst.indexOf(':')
                        val nsRaw = if (secondColon < 0) afterFirst else afterFirst.substring(0, secondColon)
                        val afterSecond = if (secondColon < 0) "" else afterFirst.substring(secondColon + 1)
                        val opt = afterSecond.trim().toIntOrNull()?.coerceAtLeast(1) ?: 0
                        Triple(asInt.toLong(), nsRaw.trim().ifBlank { null }, opt)
                    }
                }
                indentOwnedByIde = false
                commands += ImportCommand(
                    editor = editor,
                    namespace = ns,
                    visibleDelayMs = delayMs.coerceAtLeast(0L),
                    optionIndex = optIdx,
                    // Match the typewriter's base typing pace for the down-arrow animation —
                    // each key step in the popup feels like a typed character.
                    stepDelayMs = delay.toInt(),
                    pauseAfter = pause(),
                )
            }
            // Per-character backspace: N individual presses, each with a click sound and the
            // standard jittered pause. Goes through the IDE's backspace action so language smart-
            // backspace (Python dedent, Rider whitespace fixups, …) fires per press.
            "backspace" -> {
                val n = rest.trim().toIntOrNull() ?: 0
                if (n > 0) {
                    indentOwnedByIde = false
                    repeat(n) {
                        commands += BackspaceCommand(pause(), editor)
                    }
                }
            }
            // Press-and-hold backspace: characters disappear at typing pace (one per tick), but
            // only one click sound at the start — modelling a held key, not N tapped presses.
            "backspace-hold" -> {
                val n = rest.trim().toIntOrNull() ?: 0
                if (n > 0) {
                    indentOwnedByIde = false
                    commands += BackspaceHoldCommand(
                        count = n,
                        stepDelay = delay,
                        jitter = jitter,
                        editor = editor,
                        pauseAfter = pause(),
                    )
                }
            }
            // Walk the caret (arrow-key steps, sound per press) to right after `target`. Optional
            // `anchor` disambiguates: search for `anchor` first, then for `target` after it.
            // Splits `rest` on the *first* colon — so target/anchor can't themselves contain
            // colons. Always searches from offset 0; whitespace inside is preserved as typed.
            "goto" -> {
                indentOwnedByIde = false
                val firstArgColon = rest.indexOf(':')
                val target: String
                val anchor: String?
                if (firstArgColon < 0) {
                    target = rest
                    anchor = null
                } else {
                    target = rest.substring(0, firstArgColon)
                    anchor = rest.substring(firstArgColon + 1).ifEmpty { null }
                }
                if (target.isNotEmpty()) {
                    commands += GotoCommand(
                        target = target,
                        anchor = anchor,
                        stepDelay = delay,
                        jitter = jitter,
                        editor = editor,
                        pauseAfter = pause(),
                    )
                }
            }
            // Type a snippet/live-template abbreviation char-by-char, hold for [delay] ms so the
            // viewer sees the prefix sitting at the caret, then press Tab to expand. NAME is the
            // abbreviation registered in the IDE's live templates (e.g. `ctor` in Rider C#).
            // Formats:
            //   {{snip:NAME}}        — uses SNIP_DEFAULT_DELAY_MS before Tab
            //   {{snip:NAME:DELAY}}  — explicit DELAY in milliseconds
            "snip" -> {
                indentOwnedByIde = false
                val firstColon = rest.indexOf(':')
                val abbrev: String
                val delayBeforeTab: Long
                if (firstColon < 0) {
                    abbrev = rest.trim()
                    delayBeforeTab = SNIP_DEFAULT_DELAY_MS
                } else {
                    abbrev = rest.substring(0, firstColon).trim()
                    delayBeforeTab = rest.substring(firstColon + 1).trim().toLongOrNull()
                        ?.coerceAtLeast(0L) ?: SNIP_DEFAULT_DELAY_MS
                }
                if (abbrev.isNotEmpty()) {
                    for (ch in abbrev) {
                        commands += WriteTextCommand(ch.toString(), pause(), editor)
                    }
                    commands += PressTabCommand(delayBeforeTab, pause(), editor)
                }
            }
            // Simulate a single named key press. Each press plays a click sound and uses the
            // standard typing-pace pause. Unknown keys silently no-op (consistent with the rest
            // of the macro set).
            //
            // Routing differs by key:
            //   - `tab` goes through the editor's action handler (smart-tab). For snippet expansion
            //     that needs the keymap dispatcher, use `{{snip:...}}`.
            //   - `esc` runs `EscapeCommand`, which combines lookup-hide, AutoPopupController
            //     cancel, and an `IdeEventQueue` Esc post — best-effort dismiss of any open popup.
            //   - `enter` is posted through `IdeEventQueue` to *whatever currently has focus*. When
            //     a popup is open (e.g. after `{{key:alt+enter}}`) the keystroke selects the
            //     highlighted item; when focus is on the editor, the keymap routes Enter to the
            //     editor-enter action so smart-enter still fires. Routing via the editor's action
            //     handler directly here would bypass any popup and just insert a newline.
            //   - `alt+enter` is posted through `IdeEventQueue` so `IdeKeyEventDispatcher` routes
            //     it via the keymap to ShowIntentionActions — calling the action handler directly
            //     wouldn't engage that dispatcher and Rider's protocol-backed popup wouldn't show.
            //   - `up` / `down` / `left` / `right` are also `IdeEventQueue` posts, but targeted at
            //     whatever currently has focus (rather than the editor) — meant for nudging an
            //     intentions/completion popup that opened above the editor.
            "key" -> {
                indentOwnedByIde = false
                when (rest.trim().lowercase()) {
                    "tab" -> commands += TabCommand(pause(), editor)
                    "enter" -> commands += KeyPressCommand(
                        keyCode = KeyEvent.VK_ENTER,
                        keyChar = '\n',
                        forceEditorFocus = false,
                        pauseAfter = pause(),
                        editor = editor,
                    )
                    "alt+enter", "alt-enter" -> commands += KeyPressCommand(
                        keyCode = KeyEvent.VK_ENTER,
                        modifiers = KeyEvent.ALT_DOWN_MASK,
                        keyChar = '\n',
                        forceEditorFocus = true,
                        pauseAfter = pause(),
                        editor = editor,
                    )
                    "up" -> commands += KeyPressCommand(
                        keyCode = KeyEvent.VK_UP,
                        forceEditorFocus = false,
                        pauseAfter = pause(),
                        editor = editor,
                    )
                    "down" -> commands += KeyPressCommand(
                        keyCode = KeyEvent.VK_DOWN,
                        forceEditorFocus = false,
                        pauseAfter = pause(),
                        editor = editor,
                    )
                    "left" -> commands += KeyPressCommand(
                        keyCode = KeyEvent.VK_LEFT,
                        forceEditorFocus = false,
                        pauseAfter = pause(),
                        editor = editor,
                    )
                    "right" -> commands += KeyPressCommand(
                        keyCode = KeyEvent.VK_RIGHT,
                        forceEditorFocus = false,
                        pauseAfter = pause(),
                        editor = editor,
                    )
                    "esc", "escape" -> commands += EscapeCommand(pause(), editor)
                }
            }
            "complete" -> {
                indentOwnedByIde = false
                // Formats:
                //   complete:N:Word          — global completionDelay
                //   complete:N:DELAY:Word    — DELAY (ms, all-digits) overrides for this template
                //
                // Type N chars of Word at the normal typing pace, trigger the IDE auto-popup,
                // sleep DELAY ms while the popup is visible, then drop the rest of Word in a
                // single insert.
                val secondColon = rest.indexOf(':')
                if (secondColon > 0) {
                    val n = rest.substring(0, secondColon).trim().toIntOrNull() ?: 0
                    val afterN = rest.substring(secondColon + 1)
                    // Optional inline-delay segment: leading digits followed by `:`. Anchored
                    // to the start so a Word that simply contains digits and colons (e.g.
                    // `foo:bar`) isn't misinterpreted; only `<digits>:` at position 0 counts.
                    val delayMatch = Regex("^(\\d+):").find(afterN)
                    val templateDelay: Long
                    val word: String
                    if (delayMatch != null) {
                        templateDelay = delayMatch.groupValues[1].toLong()
                        word = afterN.substring(delayMatch.range.last + 1)
                    } else {
                        templateDelay = completionDelay
                        word = afterN
                    }
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
                        while (idx < text.length && text[idx] != '\n' && text[idx].isWhitespace() &&
                            !text.regionMatches(idx, openingSequence, 0, openingSequence.length)
                        ) {
                            sb.append(text[idx])
                            idx++
                        }
                        val wsLen = sb.length
                        // Don't absorb the boundary char if it's the start of the next template's
                        // opening marker — doing so makes `lastEnd` jump past `m.range.first` of
                        // the next match, and the next iteration's `text.substring(lastEnd, ...)`
                        // throws StringIndexOutOfBoundsException on the EDT, freezing the dialog.
                        if (wsLen > 0 && idx < text.length && !text[idx].isWhitespace() &&
                            !text.regionMatches(idx, openingSequence, 0, openingSequence.length)
                        ) {
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
                        // One WriteTextCommand per prefix char so the typing paces like normal
                        // user input (each char goes through TypedAction with its own jittered
                        // pause). A single multi-char insert would dump the whole prefix in a
                        // tick.
                        for (ch in prefix) {
                            commands += WriteTextCommand(ch.toString(), pause(), editor)
                        }
                        commands += TriggerAutocompleteCommand(templateDelay, editor)
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

private fun parseCaretDirection(s: String): CaretDirection? = when (s) {
    "up" -> CaretDirection.UP
    "down" -> CaretDirection.DOWN
    "left" -> CaretDirection.LEFT
    "right" -> CaretDirection.RIGHT
    else -> null
}

private fun isIndentChar(c: Char): Boolean = c == ' ' || c == '\t'

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
 * Single-pass classifier that produces a per-index classification map for two kinds of pairs:
 *
 * - **Bracket pairs** (`{}`, `()`, `[]`) outside string literals — opener becomes
 *   [Classification.AutoPair] (with leading/trailing whitespace captured), inner whitespace
 *   becomes [Classification.ConsumeOnly] / [Classification.SkipChar] for the auto-pair sequencer,
 *   closer becomes [Classification.SkipChar].
 * - **String pairs** (`"…"`, `'…'`) — opener becomes [Classification.AutoPair] (empty
 *   leading/trailing), closer becomes [Classification.SkipChar]. The IDE auto-pairs the closing
 *   quote when the opener is typed; the source's matching quote is then skipped over.
 *
 * Strings are detected with a tiny state machine that honors `\X` escape sequences and bails on
 * a literal newline (most C-style strings don't span lines). Brackets inside a string are left
 * unclassified — they're typed as plain characters, letting the IDE decide whether to auto-pair
 * them in that lexical context (it usually doesn't, except for `{}` inside C# `$"…"`
 * interpolations, where the IDE's own auto-pair handles closing correctly).
 *
 * Everything else is unclassified (`null`) and treated as a regular character.
 */
private fun classify(content: String): Map<Int, Classification> {
    val pairs = mutableMapOf<Int, Int>()
    val quoteOpens = mutableSetOf<Int>()
    val stack = ArrayDeque<Pair<Char, Int>>()
    var i = 0
    var stringQuote: Char? = null
    var stringStart = -1

    while (i < content.length) {
        val c = content[i]
        if (stringQuote != null) {
            when {
                c == '\\' && i + 1 < content.length -> {
                    // `\X` escape — consume both chars without classifying.
                    i += 2
                }
                c == stringQuote -> {
                    pairs[stringStart] = i
                    quoteOpens += stringStart
                    stringQuote = null
                    stringStart = -1
                    i++
                }
                c == '\n' -> {
                    // Unterminated string fragment; drop the open without recording a pair, then
                    // let the outer loop see the `\n` on the next iteration.
                    stringQuote = null
                    stringStart = -1
                }
                else -> i++
            }
        } else {
            when {
                c == '"' || c == '\'' -> {
                    stringQuote = c
                    stringStart = i
                    i++
                }
                c in OPEN_TO_CLOSE -> {
                    stack.addLast(c to i)
                    i++
                }
                c in CLOSE_TO_OPEN -> {
                    val top = stack.lastOrNull()
                    if (top != null && top.first == CLOSE_TO_OPEN[c]) {
                        pairs[top.second] = i
                        stack.removeLast()
                    }
                    i++
                }
                else -> i++
            }
        }
    }

    val result = mutableMapOf<Int, Classification>()
    for ((openIdx, closeIdx) in pairs) {
        if (openIdx in quoteOpens) {
            // Quote pair: opener triggers IDE auto-pair, closer is skipped over. The body chars
            // type plainly — no leading/trailing whitespace dance.
            result[openIdx] = Classification.AutoPair(
                open = content[openIdx],
                leading = "",
                trailing = "",
                close = content[closeIdx],
            )
            result[closeIdx] = Classification.SkipChar
            continue
        }

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
        for (j in (openIdx + 1) until leadingEnd) result[j] = Classification.ConsumeOnly
        for (j in trailingStart until closeIdx) result[j] = Classification.SkipChar
        result[closeIdx] = Classification.SkipChar
    }
    return result
}
