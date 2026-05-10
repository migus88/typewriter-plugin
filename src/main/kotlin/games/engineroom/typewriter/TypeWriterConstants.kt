package games.engineroom.typewriter

object TypeWriterConstants {
    const val defaultDelay = 90
    const val defaultJitter = 60
    const val defaultCompletionDelay = 150
    const val defaultPreExecutionPause = 1000
    /**
     * Backtick-wrapped braces — distinguishes macros from literal `{`/`}` in source code, so a
     * script can contain real braces without accidentally triggering the template parser.
     */
    const val defaultOpeningSequence = "`{"
    const val defaultClosingSequence = "}`"
    /** Default macro highlight color (purple-magenta — visible in both light and dark themes). */
    const val defaultMacroColor: Int = 0x9F26B0
    /** Default macro-argument highlight color (teal — distinct from the structural color). */
    const val defaultMacroArgColor: Int = 0x4EC9B0
}
