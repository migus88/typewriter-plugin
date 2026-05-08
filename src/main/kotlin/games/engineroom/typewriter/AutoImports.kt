package games.engineroom.typewriter

import com.intellij.codeInsight.CodeInsightSettings

/**
 * Temporarily flips IDE settings that would otherwise inject auto-imports while the typewriter is
 * running. Returns a restore handle the caller invokes when the run finishes (or is cancelled).
 *
 * Currently toggles [CodeInsightSettings.ADD_UNAMBIGIOUS_IMPORTS_ON_THE_FLY] — the platform-side
 * daemon that adds imports as soon as a typed name resolves to a single candidate. Rider /
 * ReSharper has additional auto-import surfaces (auto-add on completion acceptance, ReSharper's
 * own daemon hints) that aren't reachable from a generic IntelliJ Platform plugin. Users running
 * Rider may still need to turn off **Settings → Editor → Code Editing → Quick fixes & context
 * actions → Add directives to make code resolve** for the cleanest demo behavior.
 *
 * The restore handle is idempotent — calling it twice is a no-op. That matters because the
 * scheduler's `onDone` callback fires both on natural completion and on cancellation; double-fire
 * paths must not corrupt the user's settings.
 */
object AutoImports {

    fun suppress(): RestoreHandle {
        val settings = CodeInsightSettings.getInstance()
        // IntelliJ Platform's typo, not ours: the field is `ADD_UNAMBIGIOUS_IMPORTS_ON_THE_FLY`
        // (missing the second "U"). Preserved across IDE versions for backwards compatibility
        // of persisted settings — don't "correct" it.
        val saved = settings.ADD_UNAMBIGIOUS_IMPORTS_ON_THE_FLY
        settings.ADD_UNAMBIGIOUS_IMPORTS_ON_THE_FLY = false
        return RestoreHandle(saved)
    }

    class RestoreHandle internal constructor(private val savedAddUnambiguous: Boolean) {
        private var restored = false

        fun restore() {
            if (restored) return
            restored = true
            CodeInsightSettings.getInstance().ADD_UNAMBIGIOUS_IMPORTS_ON_THE_FLY = savedAddUnambiguous
        }
    }
}
