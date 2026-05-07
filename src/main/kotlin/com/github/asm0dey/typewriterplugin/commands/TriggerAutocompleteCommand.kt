package com.github.asm0dey.typewriterplugin.commands

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor

/**
 * Pause for [pauseAfter] ms — long enough that the auto-popup the WriteTextCommands have been
 * scheduling has time to render — then dismiss any active lookup so subsequent typing isn't
 * intercepted.
 *
 * **No `CodeCompletionHandlerBase.invokeCompletion` call here, on purpose.** That handler
 * inserts a dummy identifier into the document during prefix analysis and rolls it back; the
 * round-trip kicks Rider's typing-assist / ReSharper formatter listeners in a way that
 * shuffles whitespace around the typed word. Whatever popup the user wants visible is now
 * surfaced by the per-character `scheduleAutoPopup` in [WriteTextCommand], not by an explicit
 * synchronous invocation here.
 */
class TriggerAutocompleteCommand(
    private val pauseAfter: Long,
    private val editor: Editor,
) : Command {
    override fun run() {
        Thread.sleep(pauseAfter)
        ApplicationManager.getApplication().invokeAndWait {
            (LookupManager.getActiveLookup(editor) as? LookupImpl)?.hideLookup(true)
        }
    }
}
