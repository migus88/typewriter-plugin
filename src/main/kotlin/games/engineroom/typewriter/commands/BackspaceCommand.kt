package games.engineroom.typewriter.commands

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import games.engineroom.typewriter.KeyboardSoundService

/**
 * Press Backspace once via the IDE's editor-backspace action handler. Triggers the same code path
 * as a real Backspace keystroke — including language-aware smart backspace (Python dedent,
 * Rider's typing-assist whitespace fixups, etc.).
 *
 * Multi-step backspacing is built by emitting N of these commands so each press takes its own
 * tick (one keyboard click sound, one jittered pause). For "press-and-hold" semantics — a single
 * fast burst with one sound — use [BackspaceHoldCommand] instead.
 */
class BackspaceCommand(
    private val pauseAfter: Int,
    private val editor: Editor,
) : Command {
    override fun run() {
        KeyboardSoundService.get().playKey()
        ApplicationManager.getApplication().invokeAndWait {
            val handler = EditorActionManager.getInstance()
                .getActionHandler(IdeActions.ACTION_EDITOR_BACKSPACE)
            val caret = editor.caretModel.primaryCaret
            val dataContext = DataManager.getInstance().getDataContext(editor.contentComponent)
            handler.execute(editor, caret, dataContext)
            editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
        }
        Thread.sleep(pauseAfter.toLong())
    }
}
