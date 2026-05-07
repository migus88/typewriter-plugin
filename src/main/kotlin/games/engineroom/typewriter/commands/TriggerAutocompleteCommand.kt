package games.engineroom.typewriter.commands

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor

/**
 * Schedule the IDE's auto-popup, sleep for [pauseAfter] ms so the popup has time to render,
 * then dismiss whatever lookup is up before returning so subsequent typing isn't intercepted.
 *
 * **No `CodeCompletionHandlerBase.invokeCompletion` here.** That handler inserts a dummy
 * identifier into the document during prefix analysis and rolls it back; the round-trip kicks
 * Rider's typing-assist / ReSharper formatter into shuffling whitespace around the typed word.
 * Use the lighter `AutoPopupController.scheduleAutoPopup` instead, which doesn't touch the
 * document — but be aware its show is gated by the IDE's auto-popup typing delay, so [pauseAfter]
 * needs to be long enough (the dialog's "completion delay" setting) for the popup to actually
 * become visible.
 */
class TriggerAutocompleteCommand(
    private val pauseAfter: Long,
    private val editor: Editor,
) : Command {
    override fun run() {
        val project = editor.project ?: return
        ApplicationManager.getApplication().invokeLater {
            AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        }
        Thread.sleep(pauseAfter)
        ApplicationManager.getApplication().invokeAndWait {
            (LookupManager.getActiveLookup(editor) as? LookupImpl)?.hideLookup(true)
        }
    }
}
