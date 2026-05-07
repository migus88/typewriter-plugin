package com.github.asm0dey.typewriterplugin.commands

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import kotlin.random.Random

class WriteCharCommand(
    private val char: Char,
    private val pauseAfter: Int,
    private val editor: Editor,
) : Command {
    private val document = editor.document
    private val caret = editor.caretModel.primaryCaret

    companion object {
        fun fromText(editor: Editor, content: String, pauseBetweenCharacters: Int, jitter: Int) = sequence {
            for (c in content) {
                yield(
                    WriteCharCommand(
                        c,
                        pauseBetweenCharacters + Random.nextInt(-jitter, jitter + 1),
                        editor,
                    )
                )
            }
        }
    }

    override fun run() {
        WriteCommandAction.runWriteCommandAction(editor.project) {
            val offset = caret.offset
            document.insertString(offset, char.toString())
            caret.moveToOffset(offset + 1)
        }
        Thread.sleep(pauseAfter.toLong())
    }
}
