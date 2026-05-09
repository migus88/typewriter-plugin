<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# typewriter-plugin Changelog

## Unreleased

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
