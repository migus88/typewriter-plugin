package games.engineroom.typewriter

/**
 * Single source of truth for the built-in macro syntax. Drives both the dialog's macro list
 * (via [TypeWriterDialog]) and the completion contributor's macro suggestions (via
 * [TypewriterCompletionContributor]).
 *
 * - [pattern] uses `{O}` and `{C}` placeholders for the user-configurable opening / closing
 *   markers. Keep them in for templates so renderers can swap in whatever the user has set.
 * - [placeholder] is the substring inside [pattern] that gets replaced by selected editor text
 *   when a macro is inserted while a selection is active. `null` means the macro has no
 *   placeholder and the selection is just overwritten with the rendered syntax.
 */
internal enum class MacroKind(
    val pattern: String,
    val descriptionKey: String,
    val placeholder: String? = null,
) {
    PAUSE("{O}pause:1000{C}", "macro.pause.description"),
    REFORMAT("{O}reformat{C}", "macro.reformat.description"),
    BR("{O}br{C}", "macro.br.description"),
    COMPLETE("{O}complete:3:Word{C}", "macro.complete.description", placeholder = "Word"),
    COMPLETE_DELAY(
        "{O}complete:3:500:Word{C}",
        "macro.complete.delay.description",
        placeholder = "Word",
    ),
    IMPORT_AUTO("{O}import:300{C}", "macro.import.auto.description"),
    IMPORT_OPTION("{O}import:300::2{C}", "macro.import.option.description"),
    CARET("{O}caret:up:3{C}", "macro.caret.description"),
    BACKSPACE("{O}backspace:5{C}", "macro.backspace.description"),
    BACKSPACE_HOLD("{O}backspace-hold:5{C}", "macro.backspace.hold.description"),
    GOTO("{O}goto:String{C}", "macro.goto.description", placeholder = "String"),
    GOTO_ANCHOR(
        "{O}goto:Target:Anchor{C}",
        "macro.goto.anchor.description",
        placeholder = "Target",
    ),
    SNIP("{O}snip:ctor{C}", "macro.snip.description", placeholder = "ctor"),
    SNIP_DELAY("{O}snip:ctor:500{C}", "macro.snip.delay.description", placeholder = "ctor"),
    KEY("{O}key:enter{C}", "macro.key.description"),
    ;

    /** Pattern with the `{O}`/`{C}` markers stripped — what the macro looks like inside markers. */
    val body: String = pattern.removePrefix("{O}").removeSuffix("{C}")
}
