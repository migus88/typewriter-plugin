package games.engineroom.typewriter.commands

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.util.Key

enum class ScrollTarget { TOP, BOTTOM, CENTER }

/**
 * Per-editor user data key holding the active auto-scroll target, or `null` when auto-scroll is
 * off. Set by [SetAutoScrollCommand]; consulted by [applyAutoScrollIfActive] from any command
 * that inserts a `\n`. Lives on the editor (rather than a global service) so concurrent runs in
 * different editors don't trample each other.
 */
val SCROLL_AUTO_KEY: Key<ScrollTarget> = Key.create("typewriter.scroll.auto")

/**
 * Apply the editor's current auto-scroll setting (if any) at the current caret position. No-op
 * when auto-scroll is off. Safe to call from off-EDT — wraps the scroll in `invokeAndWait`.
 *
 * Called from [EnterCommand] and [WriteTextCommand]'s multi-char path so the viewport snaps back
 * to the user's chosen position after every line break.
 */
fun applyAutoScrollIfActive(editor: Editor) {
    val target = editor.getUserData(SCROLL_AUTO_KEY) ?: return
    ApplicationManager.getApplication().invokeAndWait {
        scrollEditor(editor, target)
    }
}

internal fun scrollEditor(editor: Editor, target: ScrollTarget) {
    val scrolling = editor.scrollingModel
    when (target) {
        ScrollTarget.TOP -> scrolling.scrollTo(LogicalPosition(0, 0), ScrollType.MAKE_VISIBLE)
        ScrollTarget.BOTTOM -> {
            val lastLine = (editor.document.lineCount - 1).coerceAtLeast(0)
            scrolling.scrollTo(LogicalPosition(lastLine, 0), ScrollType.MAKE_VISIBLE)
        }
        ScrollTarget.CENTER -> {
            val pos = editor.caretModel.primaryCaret.logicalPosition
            scrolling.scrollTo(pos, ScrollType.CENTER)
        }
    }
}

/**
 * One-shot scroll the viewport without moving the caret.
 *
 * - [ScrollTarget.TOP]    — scroll the document to its first line.
 * - [ScrollTarget.BOTTOM] — scroll the document to its last line.
 * - [ScrollTarget.CENTER] — scroll so the caret's current line is centered in the viewport.
 */
class ScrollCommand(
    private val target: ScrollTarget,
    private val pauseAfter: Int,
    private val editor: Editor,
) : Command {

    override fun run() {
        ApplicationManager.getApplication().invokeAndWait {
            scrollEditor(editor, target)
        }
        Thread.sleep(pauseAfter.toLong())
    }
}

/**
 * Arm or disarm auto-scroll on the editor. When [target] is non-null, every subsequent `\n`
 * insertion (via [EnterCommand] or [WriteTextCommand]'s multi-char path) re-scrolls the viewport
 * to that target. When [target] is `null`, auto-scroll turns off and newlines stop driving the
 * viewport.
 *
 * Doesn't immediately scroll on its own — flipping the switch is what the user asked for; the
 * first scroll fires on the next typed line break.
 */
class SetAutoScrollCommand(
    private val target: ScrollTarget?,
    private val pauseAfter: Int,
    private val editor: Editor,
) : Command {

    override fun run() {
        editor.putUserData(SCROLL_AUTO_KEY, target)
        Thread.sleep(pauseAfter.toLong())
    }
}
