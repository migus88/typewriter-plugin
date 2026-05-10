package games.engineroom.typewriter.commands

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.psi.PsiDocumentManager
import games.engineroom.typewriter.KeyboardSoundService
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent

/**
 * Post a synthetic key press + release through [IdeEventQueue]. The keystroke flows through
 * `IdeKeyEventDispatcher` — the layer that consults the keymap and dispatches to the bound
 * action — so modifier-bearing combos like Alt+Enter route to the IDE's `ShowIntentionActions`
 * instead of being delivered raw to whatever component has focus.
 *
 * [forceEditorFocus] picks the focus target:
 * - **true** for keystrokes that target the editor (Alt+Enter). Focus is moved back to the editor
 *   first so the keymap dispatcher resolves the binding against the editor's context. Without
 *   this, when the typewriter dialog has stolen focus the popup either fails to open or opens
 *   against the wrong component.
 * - **false** for keystrokes that nudge whatever popup is currently open (arrow keys driving the
 *   intentions list after `{{key:alt+enter}}`). Forcing editor focus here would dismiss the popup
 *   and send the arrow into the editor instead.
 */
class KeyPressCommand(
    private val keyCode: Int,
    private val modifiers: Int = 0,
    private val keyChar: Char = KeyEvent.CHAR_UNDEFINED,
    private val forceEditorFocus: Boolean,
    private val pauseAfter: Int,
    private val editor: Editor,
) : Command {
    override fun run() {
        if (keyCode == KeyEvent.VK_ENTER) {
            KeyboardSoundService.get().playEnter()
        } else {
            KeyboardSoundService.get().playKey()
        }
        // ModalityState.any() so the runnable fires even when a modal dialog is open
        // (e.g. Rider's "Generate" / "Implement missing members"). Default modality from a
        // background thread is non-modal, which holds the runnable until every modal closes —
        // the symptom is "sound plays, no key dispatched, typewriter wedges until the user
        // dismisses the dialog manually". Posting AWT key events is safe under any modality.
        ApplicationManager.getApplication().invokeAndWait({
            if (forceEditorFocus) {
                val project = editor.project
                if (project != null) {
                    // Same prep that ImportCommand does before its Alt+Enter dispatch:
                    // commit pending typing so the daemon sees the final source, and dismiss the
                    // auto-completion popup so it doesn't intercept the keystroke. Without the
                    // hide, the lookup owns focus while identifier characters were being typed
                    // and Alt+Enter ends up consumed by it instead of routing through the IDE's
                    // keymap dispatcher to ShowIntentionActions.
                    PsiDocumentManager.getInstance(project).commitDocument(editor.document)
                    LookupManager.getActiveLookup(editor)?.hideLookup(true)
                    IdeFocusManager.getInstance(project).requestFocus(editor.contentComponent, true)
                }
            }
            val target = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                ?: editor.contentComponent
            val now = System.currentTimeMillis()
            IdeEventQueue.getInstance().postEvent(
                KeyEvent(
                    target, KeyEvent.KEY_PRESSED, now, modifiers,
                    keyCode, keyChar, KeyEvent.KEY_LOCATION_STANDARD,
                ),
            )
            IdeEventQueue.getInstance().postEvent(
                KeyEvent(
                    target, KeyEvent.KEY_RELEASED, now + 1, modifiers,
                    keyCode, keyChar, KeyEvent.KEY_LOCATION_STANDARD,
                ),
            )
        }, ModalityState.any())
        Thread.sleep(pauseAfter.toLong())
    }
}
