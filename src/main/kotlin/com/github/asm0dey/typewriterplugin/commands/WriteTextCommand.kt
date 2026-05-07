package com.github.asm0dey.typewriterplugin.commands

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType

/**
 * Insert [text] at the caret in one document edit and advance the caret past it. The whole
 * insert counts as a single typing tick — used to make line-leading indentation type as one
 * keystroke instead of one-per-space.
 *
 * Each tick may schedule the IDE's auto-popup so the IntelliSense suggestion window stays
 * responsive during typing. We only call [AutoPopupController.scheduleAutoPopup] when no
 * lookup is currently active — calling it while a popup is already up resets the alarm and
 * causes the popup to flicker (close-and-reopen) on every keystroke.
 */
class WriteTextCommand(
    private val text: String,
    private val pauseAfter: Int,
    private val editor: Editor,
) : Command {
    private val document = editor.document
    private val caret = editor.caretModel.primaryCaret

    override fun run() {
        WriteCommandAction.runWriteCommandAction(editor.project) {
            val offset = caret.offset
            document.insertString(offset, text)
            caret.moveToOffset(offset + text.length)
            editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
            val project = editor.project
            if (project != null && LookupManager.getActiveLookup(editor) == null) {
                AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
            }
        }
        Thread.sleep(pauseAfter.toLong())
    }
}
