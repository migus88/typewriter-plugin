<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# typewriter-plugin Changelog

## Unreleased

## 0.9.0

### Added

- **Custom macros.** A new **Custom macros…** popup (button under the macro list) manages user-defined macros. Each entry has a name, an optional ordered list of parameters, and a content body — typing `{{name}}` (or `{{name:arg1:arg2}}` for parameterised macros) into a script substitutes the body before the typing pipeline runs. Parameters are referenced inside the content as `$paramName$` (live-template style); missing args resolve to empty, extras are ignored. Expansion is recursive with cycle protection so a macro can reference other macros (built-in or custom) without looping. Custom names are validated against built-in collisions, whitespace, and `:`; parameter names must be identifier-like.
- Custom macros surface in the main macro list under the built-ins, separated by a divider line and rendered in italic. Double-click (or <kbd>Enter</kbd>) inserts the call shape `{{name:Param1:Param2}}` at the active tab's caret; if a selection is active, it replaces the first parameter slot.

## 0.8.0

### Added

- `{{snip:NAME}}` and `{{snip:NAME:DELAY}}` macros — type a live-template abbreviation char-by-char, hold for `DELAY` ms (200 ms default) so the viewer sees the prefix sit at the caret, then press Tab to expand the IDE snippet. Tab is dispatched through the keymap so whichever expansion handler the language ships with — IntelliJ's, ReSharper's, etc. — picks it up exactly as for a real keypress.
- `{{key:tab}}` and `{{key:enter}}` macros — simulate single Tab / Enter keypresses (with click sound). Tab routes through the editor-tab action handler (focus-independent, inserts indent); Enter routes through the smart-enter handler (auto-indent, brace splitting). For snippet expansion that needs the keymap dispatcher, use `{{snip:...}}`.

### Changed

- Replaced the `JBTabbedPane`-based tab UI with a custom horizontal-scrolling tab strip. Single row, dedicated horizontal scroll bar at the bottom of the strip when tabs overflow (no chevron-popup), always-visible close × on every tab. The **+** button moved to the top toolbar next to the settings gear.
- Pressing Enter inside the dialog's editor field no longer fires the language smart-enter — it inserts a plain newline that preserves the current line's leading whitespace. Avoids language plugins (e.g. C#) auto-adding 4 spaces of indent on every line break in the script editor.

## 0.7.0

### Added

- `{{backspace:N}}` macro — N individual Backspace presses through the IDE's backspace action handler (so language smart-backspace fires), each with a click sound + jittered pause.
- `{{backspace-hold:N}}` macro — press-and-hold imitation: characters disappear one at a time at typing pace, but only one click sound plays (at the start) — modelling a held key rather than N tapped presses.
- `{{goto:String}}` and `{{goto:Target:Anchor}}` macros — walk the caret (arrow-key steps with a click sound + typing-pace pause per press) from offset 0 to right after the matched string. Horizontal travel uses Alt+Arrow word-skip jumps when the next word boundary doesn't overshoot the target, falling back to single-char arrows for the final approach. The two-arg form locates `Anchor` first and then searches for `Target` after it, so a generic target can be disambiguated.

## 0.6.1

### Fixed

- UI freeze when two `{{complete}}` macros sat next to each other (e.g. `{{complete:3:public}} {{complete:3:static}}`). The tail-absorber used to grab the next non-whitespace char unconditionally; when that char was the `{` of an immediately-following `{{` marker, parsing crashed on the EDT and the dialog stayed greyed out with no session to stop. The absorber now stops at the next macro marker.

### Added

- `Pre-execution pause` setting (default 1000 ms) — held at the start of every typing run so the editor caret has time to focus before typing begins.

### Changed

- Wrapped the typing-start path in error handling: any planning error now restores the UI and shows an error dialog instead of locking the user out.

## 0.6.0

### Added

- Macros list in a left column next to the editor (replaces the old top "Templates" row). Description shows on hover; double-click or Enter inserts at the caret. Macros are now called *macros* throughout.
- Selection-aware insertion: with text selected in the script, double-clicking a macro that has a placeholder (e.g. `Word`, `Namespace`) replaces the selection with the macro and substitutes the selected text into the placeholder.
- Macro highlighting in the script editor — bold paint on the markers + name in a primary colour, with each colon-separated argument painted in a configurable secondary colour.
- Settings popup (gear button on the language row) hosting delay, jitter, macro markers, completion delay, macro colour, and macro args colour. Persists across restarts.
- Single-instance dialog: re-invoking the action focuses the existing window instead of opening a second one.

### Changed

- `Unenrich` is now `Clear macros` and strips every macro (not just `{{complete}}`).
- "+" to add a tab is overlaid on the tab strip's right edge.
- "Keep window open" lives next to Start at the bottom-left; the Start button toggles to Stop while typing is in progress; dedicated Stop and Close buttons removed.
- Macros list sits in a bordered, scrollable column with Enrich/Clear macros header buttons sized to match the tab strip.

## 0.5.0

### Added

- `{{import:N::K}}` template — drives the IDE's native Alt+Enter intentions popup. Waits for the daemon to flag the unresolved symbol (polls the document's markup model rather than sleeping a fixed amount), dispatches Alt+Enter, navigates into Rider's "Import type…" submenu when present, then animates Down-arrow keystrokes at typewriter pace to reach the K-th option before pressing Enter. The `N` is the read window in milliseconds the popup stays visible before the cursor starts moving.
- `{{import:N:Namespace}}` — explicit form. Inserts a language-appropriate import line (`using` for C#, `import` for Java/Kotlin/Python/JS/TS, `#include` for C/C++, `require` for Ruby, `use` for PHP) at the top of the file, after any existing imports.
- `{{caret:up|down|left|right:N}}` template — moves the caret N steps in the chosen direction, one step per typewriter tick. The misspelling `{{carret:…:N}}` is accepted as an alias.
- "Suppress IDE auto-import during typing" toggle in the dialog (default on). Flips `CodeInsightSettings.ADD_UNAMBIGIOUS_IMPORTS_ON_THE_FLY` off for the duration of the run and restores it afterwards, so `{{import}}` controls when usings are added instead of the IDE sneaking them in mid-typing.

### Changed

- The auto-import flow temporarily hides any active auto-completion popup before dispatching Alt+Enter so the intentions popup isn't competing with the completion lookup visually.

## 0.4.0

### Added

- Enrich… / Unenrich toolbar buttons that wrap matching keywords in the active tab with `{{complete:N:Word}}` (and strip them back out).
- Per-language enrichment presets shipped for Kotlin, Java, C#, Python, JavaScript, TypeScript, C++, C, PHP, Ruby, and Go. User-added keywords sit alongside the built-ins; built-ins can be disabled but not removed.
- Per-keyword min/max bounds for the random `N` rolled at each occurrence, with three frequency modes (**All**, **Heavy**, **Light**).
- Enrichment configuration (presets, per-keyword settings, last-used mode) survives IDE restart.
- Vertical and horizontal scrollbars on the script editor — long scripts no longer push the dialog off-screen.

## 0.3.0

Forked under **Engine Room Games**, rebuilt around screencast workflows.

### Added

- `Cmd`/`Ctrl`+`Shift`+`W` single-press shortcut.
- Multi-tab dialog: each tab carries its own text and language; rename, close, reorder.
- Real code editor in the dialog (`EditorTextField`) with syntax highlighting, completion, brace matching, and folding.
- Non-modal dialog so it can live on a second screen during a take.
- **Stop** button and UI freeze while a run is in progress.
- Templates info panel — double-click to insert at the active tab's caret.
- New `{{complete:N:Word}}` template that imitates IntelliSense (type N chars, surface the popup, finish the word).
- IntelliSense popup stays alive natively during identifier typing (single chars routed through `TypedAction`).
- Configurable completion delay.
- All settings and tabs persist across IDE restarts.

### Changed

- Default template markers from `<…>` to `{{…}}`.
- Default delay from 100 ms to 50 ms; default jitter from 0 ms to 30 ms.
- Bracket auto-pair now cooperates with the IDE's native auto-pair instead of competing with it.

### Removed

- Per-snippet keyboard shortcuts (covered by tabs).

## 0.1.2 - 2025-04-09

Drops compatibility with IDEA 22, Adds compatibility with IDEA 51

## 0.1.1 - 2023-11-15

### Fixed

- Fixed an issue where deleting a snippet didn't remove its shortcut from the IDE

### Changed

- Made the shortcut input intercept shortcuts and output them instead of being a simple text input
- Prevented immediate execution of snippets when the OK button is clicked

## 0.1.0 - 2025-03-15

### Changed

- Changed the default  shortcut to Ctrl+Shift+T,W: two keys, but does not interfere with native shortcuts

## 0.0.6

### Added

- Adds an icon

## 0.0.5

### Added

- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
