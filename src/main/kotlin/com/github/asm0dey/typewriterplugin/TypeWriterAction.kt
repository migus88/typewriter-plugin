package com.github.asm0dey.typewriterplugin

import com.github.asm0dey.typewriterplugin.commands.Command
import com.github.asm0dey.typewriterplugin.commands.PauseCommand
import com.github.asm0dey.typewriterplugin.commands.ReformatCommand
import com.github.asm0dey.typewriterplugin.commands.WriteCharCommand
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAwareAction

class TypeWriterAction : DumbAwareAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        TypeWriterDialog(project).show()
    }
}

fun executeTyping(
    editor: Editor,
    text: String,
    openingSequence: String,
    closingSequence: String,
    delay: Long,
    jitter: Int,
    scheduler: TypewriterExecutorService,
    onDone: () -> Unit,
) {
    val escapedOpeningSequence = Regex.escape(openingSequence)
    val escapedClosingSequence = Regex.escape(closingSequence)
    val matches = """$escapedOpeningSequence(.*?)$escapedClosingSequence""".toRegex().findAll(text).iterator()
    val commands = mutableListOf<Command>()
    var cur: MatchResult? = null
    while (true) {
        val next = if (matches.hasNext()) matches.next() else null
        val content = text.substring(cur?.range?.last?.plus(1) ?: 0, next?.range?.first ?: text.length)
        commands += WriteCharCommand.fromText(editor, content, delay.toInt(), jitter)
        if (next != null) {
            val (command, value) = next
                .value
                .substringAfter(openingSequence)
                .substringBeforeLast(closingSequence)
                .trim()
                .split(':')
                .map(String::trim)
            when (command) {
                "pause" -> commands += PauseCommand(value.toLong())
                "reformat" -> commands += ReformatCommand(editor)
            }
        }
        cur = next
        if (cur == null) break
    }
    scheduler.start(commands, onDone)
}
