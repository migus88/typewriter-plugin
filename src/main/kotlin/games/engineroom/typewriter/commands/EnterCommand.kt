package games.engineroom.typewriter.commands

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import games.engineroom.typewriter.KeyboardSoundService

/**
 * Press Enter via the IDE's editor-enter action handler. Triggers the same code path as a real
 * Enter keystroke: line break + language-aware auto-indent (and any smart-enter behavior the
 * language plugin layers on top — Rider C# moves the closing `}` to its own line, Python lines
 * indent under a colon, etc.).
 *
 * Used for unclassified `\n` in the source so the planner doesn't have to type indentation
 * explicitly. Paired with the walker's "skip line-start indent" rule: the IDE emits whatever
 * indent it thinks is right and the source's own indent characters are discarded.
 */
class EnterCommand(
    private val pauseAfter: Int,
    private val editor: Editor,
) : Command {
    override fun run() {
        KeyboardSoundService.get().playEnter()
        ApplicationManager.getApplication().invokeAndWait {
            val handler = EditorActionManager.getInstance()
                .getActionHandler(IdeActions.ACTION_EDITOR_ENTER)
            val caret = editor.caretModel.primaryCaret
            val dataContext = DataManager.getInstance().getDataContext(editor.contentComponent)
            handler.execute(editor, caret, dataContext)
            editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
        }
        Thread.sleep(pauseAfter.toLong())
    }
}
