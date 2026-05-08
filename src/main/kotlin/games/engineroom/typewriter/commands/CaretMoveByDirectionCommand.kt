package games.engineroom.typewriter.commands

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType

enum class CaretDirection { UP, DOWN, LEFT, RIGHT }

/**
 * Move the caret one step in [direction] — equivalent to pressing the matching arrow key once.
 * Multi-step movement is built by emitting N of these commands so each step takes its own tick
 * and the standard delay/jitter pacing applies between presses.
 */
class CaretMoveByDirectionCommand(
    private val direction: CaretDirection,
    private val pauseAfter: Int,
    private val editor: Editor,
) : Command {

    override fun run() {
        ApplicationManager.getApplication().invokeAndWait {
            val caret = editor.caretModel.primaryCaret
            when (direction) {
                CaretDirection.UP -> caret.moveCaretRelatively(0, -1, false, false)
                CaretDirection.DOWN -> caret.moveCaretRelatively(0, 1, false, false)
                CaretDirection.LEFT -> caret.moveCaretRelatively(-1, 0, false, false)
                CaretDirection.RIGHT -> caret.moveCaretRelatively(1, 0, false, false)
            }
            editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
        }
        Thread.sleep(pauseAfter.toLong())
    }
}
