package com.github.asm0dey.typewriterplugin.commands

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType

/**
 * Insert [text] at the caret in one document edit and advance the caret past it. The whole
 * insert counts as a single typing tick — used to make line-leading indentation type as one
 * keystroke instead of one-per-space.
 *
 * **No `scheduleAutoPopup` here.** Calling it after every keystroke caused the popup to
 * flicker — opens, the next char's insert closes it (via lookup's prefix-tracking), the next
 * schedule reopens it, etc. Popup show/hide is now driven only from `TriggerAutocompleteCommand`
 * at explicit `{{complete}}` sites. Bringing popup-during-typing back will require routing
 * letter chars through `TypedAction` (which goes through the IDE's typed-handler chain and
 * keeps the lookup alive natively); revisit if the user needs it.
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
        }
        Thread.sleep(pauseAfter.toLong())
    }
}
