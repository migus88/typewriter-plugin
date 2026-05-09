package games.engineroom.typewriter

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Color
import java.awt.Font

/**
 * Layers macro highlighting over the editor's syntax colours. Each macro span gets two passes:
 *
 * - **Primary** (`{{`, the macro name, the closing `}}`, and the `:` separators) — bold, painted
 *   in [primaryColorProvider]. Drawn on the lower of the two layers and covers the whole span.
 * - **Secondary** (each colon-separated argument *after* the name) — bold, painted in
 *   [secondaryColorProvider]. Drawn on a higher layer so the secondary colour wins inside arg
 *   ranges.
 *
 * The set of highlighters is rebuilt on every document change — macros are short-lived edits and
 * there are usually only a handful in a script, so wholesale re-marking is simpler than diffing.
 */
class MacroHighlighter(
    private val editor: Editor,
    parentDisposable: Disposable,
    private val openingProvider: () -> String,
    private val closingProvider: () -> String,
    private val primaryColorProvider: () -> Color,
    private val secondaryColorProvider: () -> Color,
) : DocumentListener {

    private val highlighters = mutableListOf<RangeHighlighter>()

    init {
        editor.document.addDocumentListener(this, parentDisposable)
        refresh()
    }

    override fun documentChanged(event: DocumentEvent) {
        refresh()
    }

    fun refresh() {
        val markup = editor.markupModel
        for (h in highlighters) markup.removeHighlighter(h)
        highlighters.clear()

        val open = openingProvider()
        val close = closingProvider()
        if (open.isEmpty() || close.isEmpty()) return

        val primary = TextAttributes(primaryColorProvider(), null, null, EffectType.BOXED, Font.BOLD)
        val secondary = TextAttributes(secondaryColorProvider(), null, null, null, Font.BOLD)

        val text = editor.document.charsSequence.toString()
        val regex = Regex("${Regex.escape(open)}(?:(?!${Regex.escape(close)}).)*${Regex.escape(close)}")
        for (match in regex.findAll(text)) {
            val mStart = match.range.first
            val mEnd = match.range.last + 1
            // Primary covers the whole span (including the markers, name, and colon separators).
            highlighters += markup.addRangeHighlighter(
                mStart,
                mEnd,
                MACRO_LAYER,
                primary,
                HighlighterTargetArea.EXACT_RANGE,
            )

            // Secondary repaints each colon-separated argument after the macro name.
            val innerStart = mStart + open.length
            val innerEnd = mEnd - close.length
            if (innerEnd <= innerStart) continue
            val inner = text.substring(innerStart, innerEnd)
            val colon = inner.indexOf(':')
            if (colon < 0) continue
            // Walk colon-separated arg segments after the macro name. Empty segments (e.g. the
            // middle of `import:300::2`) are skipped — there's nothing to repaint.
            var cursor = colon + 1
            while (cursor <= inner.length) {
                val nextColon = inner.indexOf(':', cursor).let { if (it < 0) inner.length else it }
                if (nextColon > cursor) {
                    highlighters += markup.addRangeHighlighter(
                        innerStart + cursor,
                        innerStart + nextColon,
                        ARG_LAYER,
                        secondary,
                        HighlighterTargetArea.EXACT_RANGE,
                    )
                }
                cursor = nextColon + 1
            }
        }
    }

    companion object {
        // Above the language highlighter so macros override syntax colours inside marker spans.
        private const val MACRO_LAYER = HighlighterLayer.SYNTAX + 100
        // One step higher so the secondary (argument) colour wins inside arg ranges.
        private const val ARG_LAYER = MACRO_LAYER + 1
    }
}
