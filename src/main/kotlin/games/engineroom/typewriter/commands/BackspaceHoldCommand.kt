package games.engineroom.typewriter.commands

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import games.engineroom.typewriter.KeyboardSoundService

/**
 * Imitates a press-and-hold of Backspace: deletes [count] characters before the caret in a single
 * document mutation, with one keyboard click sound and one jittered pause. For per-character
 * deletion (one tick + one sound per press), use [BackspaceCommand] instead.
 *
 * Goes through `Document.deleteString` rather than the IDE's backspace action so the deletion is
 * one atomic edit — no intermediate caret positions, no per-step language-plugin work.
 */
class BackspaceHoldCommand(
    private val count: Int,
    private val pauseAfter: Int,
    private val editor: Editor,
) : Command {
    override fun run() {
        if (count <= 0) {
            Thread.sleep(pauseAfter.toLong())
            return
        }
        KeyboardSoundService.get().playKey()
        WriteCommandAction.runWriteCommandAction(editor.project) {
            val caret = editor.caretModel.primaryCaret
            val end = caret.offset
            val start = (end - count).coerceAtLeast(0)
            if (end > start) {
                editor.document.deleteString(start, end)
                caret.moveToOffset(start)
                editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
            }
        }
        Thread.sleep(pauseAfter.toLong())
    }
}
