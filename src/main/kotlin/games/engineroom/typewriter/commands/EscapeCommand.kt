package games.engineroom.typewriter.commands

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.wm.IdeFocusManager
import games.engineroom.typewriter.KeyboardSoundService
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent

/**
 * Press Esc — best-effort dismiss of any active editor popup (completion lookup, parameter info,
 * intentions list, hint).
 *
 * Tries multiple paths because a single one isn't reliable in Rider:
 *  1. [AutoPopupController.cancelAllRequests] — cancel pending auto-popup show requests so a
 *     popup that was scheduled to appear doesn't pop up *after* our dismiss.
 *  2. Hide any active [com.intellij.codeInsight.lookup.Lookup] directly. Covers the standard
 *     completion popup when it's a `LookupImpl`.
 *  3. Force focus to the editor and post a synthetic Esc keystroke through [IdeEventQueue]. This
 *     drives the same key-dispatch pipeline that a real Esc press hits, so any popup/hint
 *     registered against the Esc keymap binding gets dismissed by its own handler.
 *
 * The synthetic key event uses the actual Esc keyChar (``) rather than `CHAR_UNDEFINED` —
 * some popup KeyListener implementations key off the char value rather than the keyCode, so
 * being specific is safer.
 */
class EscapeCommand(
    private val pauseAfter: Int,
    private val editor: Editor,
) : Command {
    override fun run() {
        KeyboardSoundService.get().playKey()
        // ModalityState.any() so Esc still dispatches when a modal dialog is open — default
        // modality from a background thread is non-modal and would hold this runnable until
        // every modal closes (defeating the whole point of pressing Esc to dismiss them).
        ApplicationManager.getApplication().invokeAndWait({
            val project = editor.project
            if (project != null) {
                AutoPopupController.getInstance(project).cancelAllRequests()
            }
            LookupManager.getActiveLookup(editor)?.hideLookup(true)
            if (project != null) {
                IdeFocusManager.getInstance(project).requestFocus(editor.contentComponent, true)
            }
            val target = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                ?: editor.contentComponent
            val now = System.currentTimeMillis()
            IdeEventQueue.getInstance().postEvent(
                KeyEvent(
                    target, KeyEvent.KEY_PRESSED, now, 0,
                    KeyEvent.VK_ESCAPE, '', KeyEvent.KEY_LOCATION_STANDARD,
                ),
            )
            IdeEventQueue.getInstance().postEvent(
                KeyEvent(
                    target, KeyEvent.KEY_RELEASED, now + 1, 0,
                    KeyEvent.VK_ESCAPE, '', KeyEvent.KEY_LOCATION_STANDARD,
                ),
            )
        }, ModalityState.any())
        Thread.sleep(pauseAfter.toLong())
    }
}
