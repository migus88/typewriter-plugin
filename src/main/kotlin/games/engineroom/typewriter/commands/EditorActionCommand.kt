package games.engineroom.typewriter.commands

import com.intellij.ide.DataManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import games.engineroom.typewriter.KeyboardSoundService

/**
 * Run a named editor action through [EditorActionManager] — same path as a real keystroke that's
 * bound to that action in the IDE keymap. Plays one click sound and pauses [pauseAfter] ms after,
 * so multi-step movements built from N copies of this command pace like real-user typing.
 *
 * Used for selection and smart-home/end macros: `EditorLeftWithSelection`,
 * `EditorRightWithSelection`, `EditorLineStartWithSelection`, `EditorLineEndWithSelection`,
 * `EditorLineStart`, `EditorLineEnd`. Going through the action handler (rather than mutating the
 * caret/selection directly) lets language plugins layer their own behaviour on top — e.g. Rider's
 * smart-Home toggling between the first non-whitespace column and column 0 on repeated presses.
 */
class EditorActionCommand(
    private val actionId: String,
    private val pauseAfter: Int,
    private val editor: Editor,
) : Command {
    override fun run() {
        KeyboardSoundService.get().playKey()
        ApplicationManager.getApplication().invokeAndWait {
            val handler = EditorActionManager.getInstance().getActionHandler(actionId)
            val caret = editor.caretModel.primaryCaret
            val dataContext = DataManager.getInstance().getDataContext(editor.contentComponent)
            handler.execute(editor, caret, dataContext)
            editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
        }
        Thread.sleep(pauseAfter.toLong())
    }
}
