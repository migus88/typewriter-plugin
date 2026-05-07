package com.github.asm0dey.typewriterplugin.commands

import com.intellij.codeInsight.AutoPopupController
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor

/**
 * Schedule the IDE's auto-completion popup at the current caret position, then sleep for
 * [pauseAfter] ms so the popup has time to render and stay visible before the planner moves on
 * (typically to a [WriteTextCommand] that drops the rest of the word in one chunk, simulating
 * accepting the suggestion).
 */
class TriggerAutocompleteCommand(
    private val pauseAfter: Long,
    private val editor: Editor,
) : Command {
    override fun run() {
        val project = editor.project
        if (project != null) {
            ApplicationManager.getApplication().invokeLater {
                AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
            }
        }
        Thread.sleep(pauseAfter)
    }
}
