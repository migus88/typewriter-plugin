package games.engineroom.typewriter.commands

import com.intellij.ide.DataManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.actionSystem.TypedAction
import games.engineroom.typewriter.KeyboardSoundService

/**
 * Insert [text] at the caret in one tick.
 *
 * Routing rule:
 * - Single character that isn't `\n` → `TypedAction.actionPerformed(...)`. Goes through the
 *   IDE's full TypedHandler chain, so the auto-completion popup tracks the prefix natively
 *   the way it does for real-user typing, the IDE auto-pairs `{`, `(`, `[`, `"`, `'`, etc.,
 *   and language plugins (Rider's C# typing-assist, ReSharper, …) see typing events instead
 *   of opaque document mutations.
 * - Multi-character text (line-start indent, absorbed-tail blocks from `{{complete}}`) and
 *   `\n` → direct `Document.insertString`. Multi-char chunks aren't representable as a
 *   single keystroke, and routing `\n` through the editor's Enter action would smart-expand
 *   indentation in conflict with the source's explicit indent.
 *
 * **The IDE auto-pairs brackets when we type the opener.** The planner is aware of this:
 * for matched bracket pairs, it emits opener → leading-whitespace chunks → trailing-whitespace
 * chunks → caret-move-back, but **does not type the closer itself**. The IDE's auto-paired
 * closer fills that role; the source's matching closer is classified as `SkipChar` and just
 * advances the caret past the auto-paired character.
 */
class WriteTextCommand(
    private val text: String,
    private val pauseAfter: Int,
    private val editor: Editor,
) : Command {
    private val document = editor.document
    private val caret = editor.caretModel.primaryCaret

    override fun run() {
        if (text.length == 1 && text[0] != '\n') {
            val ch = text[0]
            if (ch == ' ') KeyboardSoundService.get().playSpace()
            else KeyboardSoundService.get().playKey()
            ApplicationManager.getApplication().invokeAndWait {
                val dataContext = DataManager.getInstance().getDataContext(editor.contentComponent)
                TypedAction.getInstance().actionPerformed(editor, ch, dataContext)
                editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
            }
        } else {
            val containsNewline = text.contains('\n')
            if (containsNewline) KeyboardSoundService.get().playEnter()
            WriteCommandAction.runWriteCommandAction(editor.project) {
                val offset = caret.offset
                document.insertString(offset, text)
                caret.moveToOffset(offset + text.length)
                editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
            }
            if (containsNewline) applyAutoScrollIfActive(editor)
        }
        Thread.sleep(pauseAfter.toLong())
    }
}
