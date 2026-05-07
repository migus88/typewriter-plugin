package com.github.asm0dey.typewriterplugin.commands

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor

/**
 * Move the caret by [delta] positions (positive = forward, negative = back). Used both to step
 * over already-inserted trailing whitespace + closer (forward), and to jump back to the body
 * slot after an auto-pair finishes laying down its full structure (negative).
 */
class MoveCaretCommand(
    private val delta: Int,
    private val pauseAfter: Int,
    private val editor: Editor,
) : Command {
    private val caret = editor.caretModel.primaryCaret

    override fun run() {
        ApplicationManager.getApplication().invokeAndWait {
            caret.moveToOffset((caret.offset + delta).coerceAtLeast(0))
        }
        Thread.sleep(pauseAfter.toLong())
    }
}
