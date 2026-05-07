package com.github.asm0dey.typewriterplugin.commands

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CodeCompletionHandlerBase
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor

/**
 * Show the IDE's completion popup *synchronously* via [CodeCompletionHandlerBase] (the same
 * code path the explicit `Code Completion` action takes), sleep for [pauseAfter] ms with the
 * popup visible, then dismiss it before returning.
 *
 * Why not [AutoPopupController.scheduleAutoPopup]: that respects the IDE's "auto-popup typing
 * delay" (typically 1500 ms), which is usually longer than [pauseAfter]. With it, the popup
 * doesn't actually appear before our sleep ends, so our `hideLookup` finds no active lookup
 * and is a no-op — and then the popup *does* appear later, while the planner is mid-way
 * through typing the rest of the word, and the lookup intercepts spaces (treating them as
 * accept-and-consume completion characters) and shifts the caret around. The synchronous
 * handler avoids that race entirely. We keep `scheduleAutoPopup` as a fallback in case the
 * sync invocation throws (unusual editor states, missing `PsiFile`, etc.).
 */
class TriggerAutocompleteCommand(
    private val pauseAfter: Long,
    private val editor: Editor,
) : Command {
    override fun run() {
        val project = editor.project ?: return
        ApplicationManager.getApplication().invokeAndWait {
            try {
                CodeCompletionHandlerBase(CompletionType.BASIC).invokeCompletion(project, editor)
            } catch (_: Throwable) {
                AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
            }
        }
        Thread.sleep(pauseAfter)
        ApplicationManager.getApplication().invokeAndWait {
            (LookupManager.getActiveLookup(editor) as? LookupImpl)?.hideLookup(true)
        }
    }
}
