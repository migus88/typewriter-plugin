package com.github.asm0dey.typewriterplugin.commands

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor

class ReformatCommand(private val editor: Editor) : Command {
    override fun run() {
        val project = editor.project ?: return
        WriteCommandAction.runWriteCommandAction(project) {
            val actionManager = ActionManager.getInstance()
            val reformatAction = actionManager.getAction("ReformatCode") ?: return@runWriteCommandAction
            actionManager.tryToExecute(reformatAction, null, null, null, true)
        }
    }
}
