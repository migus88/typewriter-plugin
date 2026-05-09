package games.engineroom.typewriter.commands

import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.wm.IdeFocusManager
import games.engineroom.typewriter.KeyboardSoundService
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent

/**
 * Press Tab. Used by `{{snip:NAME}}` (after typing the abbreviation, to expand the live template)
 * and by `{{key:tab}}` (raw Tab press). [delayBefore] is the pause inserted *before* the click —
 * for snippet expansion this is what gives the viewer a chance to see the full abbreviation
 * sitting at the caret before it gets replaced.
 *
 * Routed through [IdeEventQueue.postEvent] rather than [com.intellij.openapi.editor.actionSystem.EditorActionManager]
 * so the keystroke flows through `IdeKeyEventDispatcher` — the layer that consults the keymap and
 * picks the right Tab-bound action for the current context (live template expansion when an
 * abbreviation is at the caret, plain indent otherwise). Invoking the `EditorTab` action handler
 * directly bypasses that priority ordering and lands on the plain-indent path; in Rider, where
 * ReSharper owns the live-template stack, the expansion handler isn't even registered as an
 * `EditorActionHandler` for `EditorTab`. Posting a real Tab keystroke is the only reliable bridge.
 */
class PressTabCommand(
    private val delayBefore: Long,
    private val pauseAfter: Int,
    private val editor: Editor,
) : Command {
    override fun run() {
        if (delayBefore > 0) Thread.sleep(delayBefore)
        KeyboardSoundService.get().playKey()
        ApplicationManager.getApplication().invokeAndWait {
            val project = editor.project
            if (project != null) {
                IdeFocusManager.getInstance(project).requestFocus(editor.contentComponent, true)
            }
            val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                ?: editor.contentComponent
            val now = System.currentTimeMillis()
            val pressed = KeyEvent(
                focusOwner, KeyEvent.KEY_PRESSED, now, 0,
                KeyEvent.VK_TAB, '\t', KeyEvent.KEY_LOCATION_STANDARD,
            )
            val released = KeyEvent(
                focusOwner, KeyEvent.KEY_RELEASED, now + 1, 0,
                KeyEvent.VK_TAB, '\t', KeyEvent.KEY_LOCATION_STANDARD,
            )
            IdeEventQueue.getInstance().postEvent(pressed)
            IdeEventQueue.getInstance().postEvent(released)
        }
        Thread.sleep(pauseAfter.toLong())
    }
}
