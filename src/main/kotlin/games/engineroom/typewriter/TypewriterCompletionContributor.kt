package games.engineroom.typewriter

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext

/**
 * Tag the LightVirtualFile backing a TypeWriterDialog tab with this key so the contributor below
 * fires only inside our editors — registering it for `language="any"` would otherwise pollute the
 * IDE's completion in every other file.
 */
internal val TYPEWRITER_DIALOG_FILE_KEY: Key<Boolean> = Key.create("typewriter.dialog.file")

/**
 * Schedule the IDE's completion auto-popup ([AutoPopupController]) when the user types either the
 * closing character of the configured opening marker (so macro suggestions appear the moment they
 * open `` `{ ``) or the second consecutive identifier character (so word / keyword completion
 * behaves like the IDE editors).
 *
 * [TypewriterCompletionContributor] decides what the popup actually contains; this listener just
 * decides *when* to schedule it. The single-char filter (`newLength == 1 && oldLength == 0`)
 * excludes programmatic mutations — Enrich/Clear macros, plain-Enter inserts, macro-list inserts,
 * and lookup-accept replacements are all multi-char or have non-zero `oldLength`.
 *
 * [openingSequence] is a supplier so callers whose marker can change at runtime
 * (the typewriter dialog) read the live value, not a snapshot taken at install time.
 */
internal fun installTypewriterAutoPopupTrigger(
    editor: Editor,
    project: Project,
    parentDisposable: Disposable,
    openingSequence: () -> String,
) {
    editor.document.addDocumentListener(object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            if (event.newLength != 1 || event.oldLength != 0) return

            val typed = event.newFragment[0]
            val docText = event.document.charsSequence
            val caretAfter = event.offset + 1

            val open = openingSequence()
            val openLast = open.lastOrNull()
            val justClosedOpener = openLast != null &&
                typed == openLast &&
                caretAfter >= open.length &&
                matchesAt(docText, open, caretAfter - open.length)

            val prev = if (event.offset > 0) docText[event.offset - 1] else ' '
            val isSecondIdentChar = isIdentifierChar(typed) && isIdentifierChar(prev)

            if (justClosedOpener || isSecondIdentChar) {
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
                }
            }
        }

        private fun matchesAt(text: CharSequence, marker: String, offset: Int): Boolean {
            if (offset < 0 || offset + marker.length > text.length) return false
            for (j in marker.indices) {
                if (text[offset + j] != marker[j]) return false
            }
            return true
        }

        private fun isIdentifierChar(c: Char): Boolean =
            c.isLetterOrDigit() || c == '_'
    }, parentDisposable)
}

/**
 * Completion contributor for TypeWriter dialog editors:
 *
 * - **Macro completion** when the caret sits between an open macro marker and its (yet-untyped)
 *   close — suggests every built-in macro pattern (e.g. `pause:1000`, `complete:3:Word`) plus
 *   every user-defined macro. Accepting an entry inserts the macro body and, if missing, the
 *   closing marker.
 * - **Word completion** otherwise — gathers unique identifier-like words from the active
 *   document plus the language's built-in keyword preset and offers them. Language plugins
 *   (Rider/ReSharper for C#, Kotlin/Java in IDEA, …) gate their own contributors to project-
 *   resolved files, so they don't fire inside the dialog's `LightVirtualFile`-backed editor —
 *   we ship our own keyword list per language to fill that gap.
 *
 * Project-scope completion (class names, references) isn't provided — the dialog's
 * LightVirtualFile isn't part of the project's PSI graph, and the typewriter use case doesn't
 * need it.
 */
class TypewriterCompletionContributor : CompletionContributor() {
    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet,
                ) {
                    val virtualFile = parameters.originalFile.virtualFile ?: return
                    if (virtualFile.getUserData(TYPEWRITER_DIALOG_FILE_KEY) != true) return

                    val settings = service<TypeWriterSettings>()
                    val open = settings.openingSequence
                    val close = settings.closingSequence

                    val caret = parameters.offset
                    val text = parameters.editor.document.charsSequence

                    val openerOffset = if (open.isNotEmpty() && close.isNotEmpty())
                        unclosedOpener(text, open, close, caret) else -1

                    if (openerOffset >= 0) {
                        val macroPrefix = text.subSequence(openerOffset + open.length, caret).toString()
                        // Once a colon appears the user has committed to a macro name and is
                        // typing arguments — suggesting other names there is just noise.
                        if (':' in macroPrefix) return
                        addMacroCompletions(result.withPrefixMatcher(macroPrefix), settings, close)
                    } else {
                        addWordCompletions(parameters, result)
                    }
                }
            },
        )
    }

    /**
     * Walks [text] from offset 0 up to [beforeOffset], tracking the offset of the most recent
     * opening marker that hasn't been closed yet. Returns -1 when not inside an open macro.
     */
    private fun unclosedOpener(
        text: CharSequence,
        open: String,
        close: String,
        beforeOffset: Int,
    ): Int {
        var lastOpen = -1
        var i = 0
        while (i < beforeOffset) {
            if (matchesAt(text, open, i)) {
                lastOpen = i
                i += open.length
                continue
            }
            if (matchesAt(text, close, i)) {
                lastOpen = -1
                i += close.length
                continue
            }
            i++
        }
        return lastOpen
    }

    private fun matchesAt(text: CharSequence, marker: String, offset: Int): Boolean {
        if (offset + marker.length > text.length) return false
        for (j in marker.indices) {
            if (text[offset + j] != marker[j]) return false
        }
        return true
    }

    private fun addMacroCompletions(
        result: CompletionResultSet,
        settings: TypeWriterSettings,
        closingSequence: String,
    ) {
        val handler = MacroInsertHandler(closingSequence)

        for (kind in MacroKind.entries) {
            result.addElement(
                LookupElementBuilder.create(kind.body)
                    .withIcon(AllIcons.Nodes.Function)
                    .withTypeText("Built-in", true)
                    .withTailText("  ${tailDescription(TypeWriterBundle.message(kind.descriptionKey))}", true)
                    .withInsertHandler(handler),
            )
        }

        for (custom in settings.customMacros) {
            val body = if (custom.parameters.isEmpty()) custom.name
            else "${custom.name}:${custom.parameters.joinToString(":")}"
            val builder = LookupElementBuilder.create(body)
                .withIcon(AllIcons.Nodes.Function)
                .withTypeText("Custom", true)
                .withInsertHandler(handler)
            val tail = tailDescription(custom.description)
            result.addElement(if (tail.isEmpty()) builder else builder.withTailText("  $tail", true))
        }
    }

    /**
     * Render a macro description as a single-line lookup tail. Built-in descriptions ship with
     * `<b>` markup intended for the inline list cell — the lookup popup doesn't render HTML, so
     * we strip the tags. Multi-line user descriptions collapse to a single line so they don't
     * blow up the popup row height.
     */
    private fun tailDescription(raw: String): String =
        raw.replace(Regex("<[^>]*>"), "").replace(Regex("\\s+"), " ").trim()

    /**
     * Inserts the closing marker right after the body (when not already present) and parks the
     * caret past it. The "already present" check covers the case where the user typed both
     * markers up front (e.g. `` `{}` ``) before triggering completion in the middle.
     */
    private class MacroInsertHandler(private val closingSequence: String) : InsertHandler<LookupElement> {
        override fun handleInsert(ctx: InsertionContext, item: LookupElement) {
            val doc = ctx.document
            val end = ctx.tailOffset
            val docText = doc.charsSequence
            val alreadyClosed = end + closingSequence.length <= docText.length &&
                (0 until closingSequence.length).all { docText[end + it] == closingSequence[it] }
            if (!alreadyClosed) {
                doc.insertString(end, closingSequence)
            }
            ctx.editor.caretModel.moveToOffset(end + closingSequence.length)
        }
    }

    /**
     * Suggest the language's built-in keywords plus unique identifier-like words from the
     * document buffer. Skips short fragments and the word the caret currently sits inside.
     * Two-char minimum prefix matches the UX choice — auto-popup fires after the second
     * identifier char, so by the time we run there's a real prefix to filter against.
     */
    private fun addWordCompletions(
        parameters: CompletionParameters,
        result: CompletionResultSet,
    ) {
        val text = parameters.editor.document.charsSequence
        val caret = parameters.offset

        var prefixStart = caret
        while (prefixStart > 0 && text[prefixStart - 1].isIdentifierChar()) prefixStart--
        val prefix = text.subSequence(prefixStart, caret).toString()
        if (prefix.length < 2) return

        val typedResult = result.withPrefixMatcher(prefix)
        val seen = HashSet<String>()
        seen += prefix

        val fileTypeName = parameters.originalFile.virtualFile?.fileType?.name.orEmpty()
        val canonical = EnrichmentPresets.canonicalLanguageName(fileTypeName)
        if (canonical != null) {
            for (keyword in EnrichmentPresets.builtins[canonical].orEmpty()) {
                if (!seen.add(keyword)) continue
                typedResult.addElement(
                    LookupElementBuilder.create(keyword)
                        .withIcon(AllIcons.Nodes.Function)
                        .withTypeText(canonical, true)
                        .bold(),
                )
            }
        }

        val regex = Regex("[A-Za-z_][A-Za-z0-9_]*")
        for (match in regex.findAll(text)) {
            if (caret in match.range.first..(match.range.last + 1)) continue
            val word = match.value
            if (word.length < 3) continue
            if (!seen.add(word)) continue
            typedResult.addElement(
                LookupElementBuilder.create(word)
                    .withIcon(AllIcons.Nodes.Constant)
                    .withTypeText("Word", true),
            )
        }
    }

    private fun Char.isIdentifierChar(): Boolean =
        isLetterOrDigit() || this == '_'
}
