package games.engineroom.typewriter.commands

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import games.engineroom.typewriter.KeyboardSoundService

/**
 * Press Tab via the IDE's editor-tab action handler. Same path as a real Tab keystroke that
 * doesn't have a higher-priority keymap binding active — typically inserts a tab/spaces or
 * advances indentation, depending on language and code style.
 *
 * Used by `{{key:tab}}`. **Does not** trigger live-template expansion: in IntelliJ that's bound to
 * a separate action (`ExpandLiveTemplateByTab`) that the keymap dispatcher routes to when an
 * abbreviation prefix sits at the caret. Snippet expansion goes through [PressTabCommand], which
 * posts a real key event so the keymap dispatcher gets a chance to pick that other action.
 */
class TabCommand(
    private val pauseAfter: Int,
    private val editor: Editor,
) : Command {
    override fun run() {
        KeyboardSoundService.get().playKey()
        ApplicationManager.getApplication().invokeAndWait {
            val handler = EditorActionManager.getInstance()
                .getActionHandler(IdeActions.ACTION_EDITOR_TAB)
            val caret = editor.caretModel.primaryCaret
            val dataContext = DataManager.getInstance().getDataContext(editor.contentComponent)
            handler.execute(editor, caret, dataContext)
            editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
        }
        Thread.sleep(pauseAfter.toLong())
    }
}
