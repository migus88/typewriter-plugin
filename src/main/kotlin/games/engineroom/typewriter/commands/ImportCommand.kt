package games.engineroom.typewriter.commands

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.ide.IdeEventQueue
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.psi.PsiDocumentManager
import games.engineroom.typewriter.KeyboardSoundService
import java.awt.Component
import java.awt.Container
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.event.KeyEvent
import javax.swing.JList
import javax.swing.SwingUtilities

/**
 * Add a missing import.
 *
 * - **Auto** ([namespace] is null) — triggers the IDE's native intentions popup (the same one
 *   Alt+Enter shows), waits [visibleDelayMs] so the viewer can read the suggestions, then drives
 *   the popup's list directly: pre-selects [optionIndex] and accepts. Letting Rider/ReSharper
 *   populate the list itself is the only reliable way to surface ReSharper's "Import type 'X'"
 *   entries — the IntelliJ-Platform-side `ShowIntentionsPass.getActionsToShow` doesn't see them
 *   in Rider's C# context.
 *
 * - **Explicit** ([namespace] non-null) — inserts a language-appropriate import statement at the
 *   top of the file, after any existing import block. Bypasses the daemon entirely.
 *
 * [optionIndex] is 1-based and only meaningful in auto mode:
 * - `0` (or unspecified) → accept the popup's first item (the IDE's natural top suggestion).
 * - `>= 1` → positional pick: the Nth row in the popup, regardless of kind. Counts every leaf —
 *   Rider's "Import type 'A'", "Import type 'B'", "Import type 'C'" each count as their own row.
 */
class ImportCommand(
    private val editor: Editor,
    private val namespace: String?,
    /** Time the popup is shown before the down-arrow animation starts. */
    private val visibleDelayMs: Long,
    /** 1-based positional index in the popup; 0 means "accept the first item". */
    private val optionIndex: Int,
    /** Per-keystroke delay during the down-arrow animation through the popup. */
    private val stepDelayMs: Int,
    private val pauseAfter: Int,
) : Command {

    override fun run() {
        val project = editor.project ?: run {
            if (visibleDelayMs > 0) Thread.sleep(visibleDelayMs)
            Thread.sleep(pauseAfter.toLong())
            return
        }

        try {
            if (namespace.isNullOrBlank()) {
                applyAutoImport(project)
            } else {
                if (visibleDelayMs > 0) Thread.sleep(visibleDelayMs)
                applyExplicitImport(project, namespace.trim())
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return
        } catch (t: Throwable) {
            LOG.warn("Import command failed; honoring visible delay anyway", t)
            if (visibleDelayMs > 0) {
                runCatching { Thread.sleep(visibleDelayMs) }
            }
        }

        Thread.sleep(pauseAfter.toLong())
    }

    /**
     * Auto-import via the IDE's native intentions popup.
     *
     * Flow:
     * 1. Hide any active auto-completion lookup so it doesn't sit on top of the intentions popup.
     * 2. Dispatch Alt+Enter so Rider builds and shows its popup.
     * 3. Wait briefly for it to render, then find the popup's JList.
     * 4. If the list contains an "Import type…" entry (Rider's parent menu — image 2 in the
     *    user's screenshot), navigate to it and press Right to expand the import-only submenu
     *    (image 3 — `System.Drawing.Color`, `UnityEngine.Color`, …). If there's no such entry,
     *    drive the original popup.
     * 5. Sleep [visibleDelayMs] so the viewer reads the popup.
     * 6. Animate: post Down arrow keystrokes one at a time with [stepDelayMs] between, so the
     *    viewer sees the highlight crawl down to the chosen index.
     * 7. Post Enter to accept.
     */
    private fun applyAutoImport(project: Project) {
        ApplicationManager.getApplication().invokeAndWait {
            PsiDocumentManager.getInstance(project).commitDocument(editor.document)
            // The auto-completion popup is alive whenever the typewriter is typing identifiers.
            // It would compete with the intentions popup visually and offer a JList we'd
            // otherwise pick by mistake.
            LookupManager.getActiveLookup(editor)?.hideLookup(true)
        }

        // Wait for ReSharper's daemon to flag the just-typed symbol before dispatching Alt+Enter.
        // Without this, Rider's popup is the general "create type / refactor / …" view rather
        // than the focused import-only popup. We don't sleep a fixed amount — instead poll the
        // document's markup model for an error/warning highlight at the caret. As soon as one
        // appears, ReSharper has the diagnostic that drives the import-only popup. Falls back
        // to proceeding after the timeout if no diagnostic shows up (e.g., the symbol resolves,
        // or it's flagged at a severity below WARNING).
        waitForDiagnosticAtCaret(project)

        val triggered = dispatchAltEnter(project)
        if (!triggered) {
            LOG.warn("Alt+Enter dispatch reported failure")
            if (visibleDelayMs > 0) Thread.sleep(visibleDelayMs)
            return
        }

        // Brief settle time so Rider's protocol-backed popup has time to render before we try
        // to find it. Without this, findIntentionsList sometimes misses the popup that's about
        // to appear in the next EDT tick.
        Thread.sleep(POPUP_RENDER_MS)

        val initialList = findIntentionsList()
        if (initialList == null) {
            LOG.warn("No intentions popup appeared after Alt+Enter")
            if (visibleDelayMs > 0) Thread.sleep(visibleDelayMs)
            restoreEditorFocus(project)
            return
        }
        LOG.info("Initial popup list: class=${initialList.javaClass.simpleName} items=${initialList.model.size} selected=${initialList.selectedIndex}")
        logListItems("initial", initialList)

        // Try to expand the "Import type…" submenu — that's Rider's import-only popup.
        val importTypeIdx = findItemIndexByText(initialList) { text ->
            text.contains("Import type", ignoreCase = true)
        }
        val workingList: JList<*> = if (importTypeIdx >= 0) {
            LOG.info("Found 'Import type' at index $importTypeIdx; navigating into submenu")
            navigateInPopup(initialList.selectedIndex.coerceAtLeast(0), importTypeIdx)
            KeyboardSoundService.get().playKey()
            postKeyToFocused(KeyEvent.VK_RIGHT)
            Thread.sleep(POPUP_RENDER_MS)
            val submenu = findIntentionsList()
            if (submenu != null && submenu !== initialList) {
                LOG.info("Submenu opened: items=${submenu.model.size}")
                logListItems("submenu", submenu)
                submenu
            } else {
                LOG.warn("Right did not open a submenu; driving original popup")
                initialList
            }
        } else {
            LOG.info("No 'Import type' entry; driving popup as-is")
            initialList
        }

        // Reading window — viewer sees the popup before the cursor starts moving.
        if (visibleDelayMs > 0) Thread.sleep(visibleDelayMs)

        // Animate selection: post Down arrows at typewriter pace.
        val itemCount = workingList.model.size
        if (itemCount == 0) {
            LOG.info("Popup has no items")
            restoreEditorFocus(project)
            return
        }
        val targetIdx = (if (optionIndex >= 1) optionIndex - 1 else 0).coerceIn(0, itemCount - 1)
        val currentIdx = workingList.selectedIndex.coerceAtLeast(0)
        navigateInPopup(currentIdx, targetIdx)

        // Confirm.
        KeyboardSoundService.get().playEnter()
        postKeyToFocused(KeyEvent.VK_ENTER)

        restoreEditorFocus(project)
    }

    /**
     * Poll for an error or warning highlight at the caret. Kicks the daemon explicitly first so
     * we don't wait through ReSharper's debounce, then samples the document's markup every
     * [DAEMON_POLL_INTERVAL_MS] until a diagnostic appears or the [DAEMON_WAIT_TIMEOUT_MS]
     * budget runs out. Returns true if we observed a diagnostic, false on timeout — the caller
     * proceeds either way; a false here just means Alt+Enter will run against whatever Rider
     * has cached, which may produce the wrong popup.
     */
    private fun waitForDiagnosticAtCaret(project: Project): Boolean {
        ApplicationManager.getApplication().invokeAndWait {
            val docManager = PsiDocumentManager.getInstance(project)
            docManager.commitDocument(editor.document)
            val psiFile = docManager.getPsiFile(editor.document) ?: return@invokeAndWait
            psiFile.virtualFile?.let { vf ->
                DaemonCodeAnalyzer.getInstance(project).restart(vf)
            }
        }

        val started = System.currentTimeMillis()
        val deadline = started + DAEMON_WAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(DAEMON_POLL_INTERVAL_MS)
            if (hasDiagnosticAtCaret(project)) {
                LOG.info("Diagnostic appeared at caret after ${System.currentTimeMillis() - started} ms")
                return true
            }
        }
        LOG.info("No diagnostic at caret within ${DAEMON_WAIT_TIMEOUT_MS} ms; proceeding anyway")
        return false
    }

    private fun hasDiagnosticAtCaret(project: Project): Boolean {
        var found = false
        ApplicationManager.getApplication().invokeAndWait {
            val markupModel = DocumentMarkupModel.forDocument(editor.document, project, false)
                ?: return@invokeAndWait
            val caretOffset = editor.caretModel.offset
            for (highlighter in markupModel.allHighlighters) {
                val info = HighlightInfo.fromRangeHighlighter(highlighter) ?: continue
                if ((info.severity == HighlightSeverity.ERROR ||
                        info.severity == HighlightSeverity.WARNING) &&
                    caretOffset in info.startOffset..info.endOffset) {
                    found = true
                    return@invokeAndWait
                }
            }
        }
        return found
    }

    private fun restoreEditorFocus(project: Project) {
        ApplicationManager.getApplication().invokeAndWait {
            IdeFocusManager.getInstance(project).requestFocus(editor.contentComponent, true)
        }
    }

    /** Animate Down/Up keystrokes from [from] to [to] with [stepDelayMs] between each press. */
    private fun navigateInPopup(from: Int, to: Int) {
        val diff = to - from
        if (diff == 0) return
        val keyCode = if (diff > 0) KeyEvent.VK_DOWN else KeyEvent.VK_UP
        val steps = kotlin.math.abs(diff)
        repeat(steps) {
            KeyboardSoundService.get().playKey()
            postKeyToFocused(keyCode)
            Thread.sleep(stepDelayMs.toLong().coerceAtLeast(1L))
        }
    }

    /** Post a synthetic key press + release through [IdeEventQueue] targeted at the focus owner. */
    private fun postKeyToFocused(keyCode: Int) {
        ApplicationManager.getApplication().invokeAndWait {
            val target = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                ?: editor.contentComponent
            val now = System.currentTimeMillis()
            IdeEventQueue.getInstance().postEvent(
                KeyEvent(target, KeyEvent.KEY_PRESSED, now, 0, keyCode, KeyEvent.CHAR_UNDEFINED),
            )
            IdeEventQueue.getInstance().postEvent(
                KeyEvent(target, KeyEvent.KEY_RELEASED, now + 1, 0, keyCode, KeyEvent.CHAR_UNDEFINED),
            )
        }
    }

    /**
     * Locate the JList for whatever intentions/import popup is currently visible. Prefers the
     * focused window so that, after navigating into a submenu (which takes focus), we drive the
     * submenu's list rather than the parent. Skips the typewriter dialog and the auto-completion
     * lookup popup explicitly.
     */
    private fun findIntentionsList(): JList<*>? {
        var found: JList<*>? = null
        ApplicationManager.getApplication().invokeAndWait {
            val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
            val focusedWindow = focusOwner?.let { SwingUtilities.getWindowAncestor(it) }
            if (focusedWindow != null && !isTypewriterWindow(focusedWindow)) {
                val list = findJList(focusedWindow)
                if (list != null && !isCompletionLookupList(list)) {
                    found = list
                    return@invokeAndWait
                }
            }
            for (w in Window.getWindows()) {
                if (!w.isVisible) continue
                if (isTypewriterWindow(w)) continue
                val list = findJList(w)
                if (list != null && !isCompletionLookupList(list)) {
                    found = list
                    return@invokeAndWait
                }
            }
        }
        return found
    }

    private fun isTypewriterWindow(w: Window): Boolean {
        // The Kotlin UI DSL's DialogWrapper backs windows with `MyDialog`. Our typewriter dialog
        // is one — skip it so its template-list JBList isn't mistaken for an intentions popup.
        if (w.javaClass.simpleName == "MyDialog") return true
        return false
    }

    /**
     * Walk the popup's list model looking for an item whose displayed text matches [predicate].
     * Falls back to `toString()` when reflection-friendly text accessors aren't available.
     */
    private fun findItemIndexByText(list: JList<*>, predicate: (String) -> Boolean): Int {
        var index = -1
        ApplicationManager.getApplication().invokeAndWait {
            val model = list.model
            for (i in 0 until model.size) {
                val item = model.getElementAt(i) ?: continue
                val text = extractItemText(item)
                if (text != null && predicate(text)) {
                    index = i
                    break
                }
            }
        }
        return index
    }

    /** Try to extract the displayed text of a popup-list item via common JetBrains accessors. */
    private fun extractItemText(item: Any): String? {
        for (methodName in TEXT_ACCESSORS) {
            try {
                val m = item.javaClass.getMethod(methodName)
                val result = m.invoke(item)
                when (result) {
                    is String -> return result
                    null -> {} // try next
                    else -> return result.toString()
                }
            } catch (_: NoSuchMethodException) {
                // try next
            } catch (_: Exception) {
                // ignore — fall back to toString
            }
        }
        return runCatching { item.toString() }.getOrNull()
    }

    /** Diagnostic: log every item in [list]. Helps when matching text doesn't find what we expect. */
    private fun logListItems(label: String, list: JList<*>) {
        val model = list.model
        for (i in 0 until model.size) {
            val item = model.getElementAt(i)
            val text = item?.let { extractItemText(it) } ?: "<null>"
            LOG.info("  $label[$i]: $text (class=${item?.javaClass?.simpleName})")
        }
    }

    /**
     * Trigger Alt+Enter as if the user pressed it. Routes through [IdeEventQueue.postEvent] so
     * the keystroke flows through `IdeKeyEventDispatcher` — that's the layer that consults the
     * keymap and dispatches to the bound action. Calling `component.dispatchEvent` directly
     * (which we tried first) bypasses the keymap entirely; the editor receives the keystroke
     * but has no notion that it should map to ShowIntentionActions, so nothing happens.
     */
    private fun dispatchAltEnter(project: Project): Boolean {
        var ok = false
        ApplicationManager.getApplication().invokeAndWait {
            IdeFocusManager.getInstance(project).requestFocus(editor.contentComponent, true)
            val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                ?: editor.contentComponent
            LOG.info("Pre-dispatch focus owner: ${focusOwner.javaClass.simpleName}")

            val now = System.currentTimeMillis()
            // Source is the actual focus owner so the IDE's dispatcher delivers it correctly.
            val pressed = KeyEvent(
                focusOwner, KeyEvent.KEY_PRESSED, now, KeyEvent.ALT_DOWN_MASK,
                KeyEvent.VK_ENTER, '\n', KeyEvent.KEY_LOCATION_STANDARD,
            )
            val released = KeyEvent(
                focusOwner, KeyEvent.KEY_RELEASED, now + 1, KeyEvent.ALT_DOWN_MASK,
                KeyEvent.VK_ENTER, '\n', KeyEvent.KEY_LOCATION_STANDARD,
            )
            // postEvent queues the event onto the IDE's event queue. It's processed after we
            // exit this invokeAndWait — the EDT runs the IDE's keymap dispatcher, which fires
            // ShowIntentionActions, which (in Rider) sends the request to ReSharper's backend.
            IdeEventQueue.getInstance().postEvent(pressed)
            IdeEventQueue.getInstance().postEvent(released)
            ok = true
            LOG.info("Posted Alt+Enter via IdeEventQueue")
        }
        return ok
    }

    private fun applyExplicitImport(project: Project, ns: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            val document = editor.document
            val docManager = PsiDocumentManager.getInstance(project)
            docManager.commitDocument(document)
            val psiFile = docManager.getPsiFile(document)
            val statement = renderImportStatement(ns, psiFile?.fileType?.name).orEmpty()
            if (statement.isEmpty()) return@runWriteCommandAction

            val insertOffset = findImportInsertionOffset(document.text)
            val toInsert = statement + "\n"
            val caret = editor.caretModel.primaryCaret
            val originalCaret = caret.offset
            document.insertString(insertOffset, toInsert)
            if (originalCaret >= insertOffset) {
                caret.moveToOffset(originalCaret + toInsert.length)
            }
            editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
        }
    }

    private fun renderImportStatement(ns: String, fileTypeName: String?): String? {
        val key = (fileTypeName ?: "").lowercase()
        return when {
            key.contains("c#") || key.contains("csharp") -> "using $ns;"
            key == "java" || key == "kotlin" -> "import $ns"
            key == "python" -> "import $ns"
            key.contains("typescript") || key.contains("javascript") -> "import $ns;"
            key == "go" || key == "golang" -> "import \"$ns\""
            key == "ruby" || key == "rb" -> "require '$ns'"
            key == "php" -> "use $ns;"
            key.contains("c++") || key == "c" || key == "cpp" -> "#include <$ns>"
            else -> "using $ns;"
        }
    }

    private fun findImportInsertionOffset(text: String): Int {
        if (text.isEmpty()) return 0
        var lastImportEndOffset = 0
        var offset = 0
        var sawImport = false
        for (line in text.split('\n')) {
            val trimmed = line.trim()
            val lineEnd = offset + line.length
            val nextLineStart = lineEnd + 1
            when {
                trimmed.isEmpty() -> {
                    if (!sawImport) {
                        offset = nextLineStart
                        continue
                    }
                }
                IMPORT_PREFIXES.any { trimmed.startsWith(it) } -> {
                    sawImport = true
                    lastImportEndOffset = nextLineStart
                }
                trimmed.startsWith("namespace ") || trimmed.startsWith("package ") -> {
                    // Imports may sit before *or* after the namespace decl. Treat the namespace
                    // line as part of the prelude.
                }
                else -> break
            }
            offset = nextLineStart
            if (offset >= text.length) break
        }
        return if (sawImport) lastImportEndOffset else 0
    }

    private fun hasJList(c: Component?): Boolean = findJList(c) != null

    private fun findJList(c: Component?): JList<*>? {
        if (c is JList<*>) return c
        if (c is Container) {
            for (child in c.components) {
                findJList(child)?.let { return it }
            }
        }
        return null
    }

    /**
     * The auto-completion popup's list class is `LookupList` (an inner class of LookupImpl).
     * We never want to drive it from the import command — the user typed `{{import}}` because
     * they want the intentions popup, not whatever was hovering in the completion popup.
     * Class-name match keeps us out of an internal-API dependency on `LookupImpl.LookupList`.
     */
    private fun isCompletionLookupList(list: JList<*>): Boolean =
        list.javaClass.simpleName == "LookupList"

    companion object {
        private val IMPORT_PREFIXES = listOf("using ", "import ", "#include", "require ", "use ")

        /**
         * Maximum time we'll poll the daemon for a diagnostic at the caret before giving up
         * and dispatching Alt+Enter anyway. 3 seconds is generous — ReSharper normally surfaces
         * a diagnostic within 300–700 ms after a typing burst.
         */
        private const val DAEMON_WAIT_TIMEOUT_MS = 3_000L

        /** Interval between markup-model checks while waiting for a diagnostic. */
        private const val DAEMON_POLL_INTERVAL_MS = 100L

        /**
         * How long to wait for Rider's intentions popup to render after Alt+Enter is dispatched
         * (and again after Right is pressed to expand a submenu). The protocol-backed popup
         * round-trip in Rider takes ~50–200 ms; 250 ms is a comfortable margin without being
         * perceptible in a screencast.
         */
        private const val POPUP_RENDER_MS = 250L

        /**
         * Methods we'll try, in order, when extracting an item's display text out of a popup's
         * list-model entry. Different popup steps wrap the value in different shapes (e.g.
         * `IntentionActionWithTextCaching` exposes `getText`, others use `getValue`).
         */
        private val TEXT_ACCESSORS = listOf("getText", "getActionText", "getValue", "getName")

        private val LOG = logger<ImportCommand>()
    }
}
