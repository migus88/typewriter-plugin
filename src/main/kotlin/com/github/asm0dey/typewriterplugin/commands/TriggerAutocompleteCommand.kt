package com.github.asm0dey.typewriterplugin.commands

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor

/**
 * Schedule the IDE's auto-completion popup at the current caret position, sleep for [pauseAfter]
 * ms so the popup has time to render, then dismiss it before returning. Dismissing matters: if
 * we leave the lookup alive while subsequent [WriteTextCommand]s insert characters, the lookup
 * treats space (and other "completion characters") as accept-and-consume, eating the spaces we
 * meant to type after the completed word.
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
