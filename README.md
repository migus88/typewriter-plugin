# ![](src/main/resources/META-INF/pluginIcon.svg) TypeWriter for JetBrains IDEs

A plugin for recording screencasts of code being written. You write the script, the IDE types it for you — character by character, at a configurable speed.

> Originally forked from [asm0dey/typewriter-plugin][upstream] by [@asm0dey](https://github.com/asm0dey), this plugin has since been completely rewritten — the screencast workflow, the macro system, the dialog, the typing engine, and the persistence layer are all new. Almost no code from the upstream survives.

[upstream]: https://github.com/asm0dey/typewriter-plugin

<!-- Plugin description -->

TypeWriter makes the IDE *type* a piece of text into the active editor at a configurable, human-feeling speed. Useful for screencasts, demos, and anything where you'd otherwise be typing in front of a camera.

Hit <kbd>Cmd</kbd>+<kbd>Shift</kbd>+<kbd>W</kbd> (or <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>W</kbd> on Windows/Linux) to open the dialog. Author your script in a real code editor — syntax highlighting, completion, brace matching, indent guides, and folding for any installed language — then click **Start typing**. The IDE will type the script into whatever editor is focused.

## Authoring

- **Real code editor**, backed by a `LightVirtualFile` so the script gets the same PSI-driven services your normal editor does.
- **Tabs.** Keep multiple scripts ready at once. The tab strip is a single row that scrolls horizontally when there are more tabs than fit — a scroll bar appears under the strip rather than collapsing into a dropdown. Click the **+** at the right end of the tabs row to add a tab, double-click a tab to rename, click the **×** on a tab to close it (always visible — no hover-to-reveal). Each tab has its own text and language. Tabs and their contents persist across IDE restarts.
- **Per-tab language picker.** Switching languages re-spins the underlying `LightVirtualFile` so the highlighter follows along without losing your text.
- **Non-modal dialog.** Park it on a second screen while typing plays in your code editor. The active editor is captured at "Start" time, not when the action fires, so you can re-focus the IDE first.
- **Macro syntax highlighting** layers a configurable colour over `` `{ … }` `` macro spans inside your scripts, with a separate colour for the colon-separated arguments after the macro name. Both colours are live-editable in **Settings**.
- **Auto-completion.** Type the opening marker (`` `{ `` by default) and a popup lists every built-in macro and your custom ones — each row carries the macro's short description in the tail text — pick one with Enter to drop the body in and close the marker. Word completion fires automatically after the second identifier character: the language's built-in keyword preset (Kotlin, Java, C#, Python, JS, TS, C++, C, PHP, Ruby, Go) plus unique tokens scraped from the active tab. Project-scope symbols aren't suggested — the script lives in a stand-alone `LightVirtualFile`, so language plugins (Rider/ReSharper, IDEA's Java contributor, etc.) don't fire and we ship our own keyword list to fill the gap. Ctrl+Space still works for manual completion.
- **Keyboard click sounds.** Each typed character plays a sampled keystroke with a touch of pitch jitter; space and enter get their own (louder) samples. Mixed in software through a single always-open audio line so fast typing never starves on macOS.

## Run modes

A **Keep window open after starting** checkbox toggles between two modes:

- **Off** (default) — the dialog closes when typing starts; focus is on the editor. There's no Stop button (the dialog is gone).
- **On** — the dialog stays open with all inputs frozen, and the Start button becomes a **Stop** button. Closing or cancelling the dialog also stops an in-flight run.

In both modes, focus is moved to the IDE editor at the start of the run so the caret blinks during typing, and (in Keep-Open) stays on the editor when the run ends — so the dialog doesn't reclaim focus over the freshly-typed code.

## Inline macros

Macros are inline directives wrapped in configurable markers (default `` `{ `` and `` }` ``). The dialog lists them in a panel on the left — double-click (or press <kbd>Enter</kbd>) to insert the syntax at the active tab's caret. If you have a selection, the macro's placeholder (e.g. `Word`, `Namespace`) is replaced by the selected text on insert, so highlighting a token and double-clicking **complete** wraps it directly.

| Macro | What it does |
|---|---|
| `` `{pause:1000}` `` | Pause typing for 1000 ms. |
| `` `{reformat}` `` | Run the IDE's "Reformat Code" action at the caret. |
| `` `{br}` `` | Suppress the very next character if it's a line break. Lets you keep a script readable on multiple lines while the animator types the text as one continuous line. No-op when the next character isn't `\n`. |
| `` `{complete:3:Word}` `` | Imitates IntelliSense — types `Wor` at typing pace, surfaces the auto-completion popup, waits the global "completion delay", then drops the rest of `Word` in one chunk. |
| `` `{complete:3:500:Word}` `` | Same as above, but with a per-template completion delay (500 ms) overriding the global setting. |
| `` `{import:300}` `` | Drives the IDE's Alt+Enter intentions popup. Hides any active completion popup, restarts the daemon to bypass ReSharper's debounce, polls until the symbol at the caret is flagged with at least a warning, dispatches Alt+Enter through the IDE's full event pipeline, finds and navigates into Rider's "Import type…" submenu, then waits 300 ms (your reading window) before pressing Enter on the highlighted option. |
| `` `{import:300::3}` `` | Same as auto mode, but animates the highlight down to the 3rd option in the submenu — each Down arrow paced like a typed character — before pressing Enter. |
| `` `{import:UnityEngine.Color}` `` | Explicit form: bypasses the daemon and popup entirely. Inserts a language-appropriate import line (`using` / `import` / `#include` / `require` / `use`) at the top of the file, after any existing imports. |
| `` `{import:300:UnityEngine.Color}` `` | Same explicit form with a 300 ms delay before the insertion. |
| `` `{caret:up:3}` `` | Move the caret 3 steps in the chosen direction (`up`, `down`, `left`, `right`) at typewriter pace — one tick per step. |
| `` `{backspace:5}` `` | Press Backspace 5 times. Each press routes through the IDE's backspace action (so smart-backspace fires) with one click sound + jittered pause per press. |
| `` `{backspace-hold:5}` `` | Imitates press-and-hold: 5 characters disappear one at a time at typing pace, but only one click sound is played (at the start) — modelling a held key, not 5 tapped presses. |
| `` `{goto:private static}` `` | Walks the caret (arrow-key steps, click sound + typing-pace pause per step) from offset 0 forward until it lands right after the first occurrence of `private static`. Not a teleport — the viewer sees the caret crawl. Horizontal travel uses Alt+Arrow (word-skip) jumps when the next word boundary doesn't overshoot the target, falling back to single-char arrows for the final approach. |
| `` `{goto:private static:private static class}` `` | Same as above, but disambiguates: searches the document for `private static class` first and then for `private static` *after* that anchor — so a generic target string lands on the occurrence you actually meant. Splitting is on the first colon, so neither part may contain `:`. |
| `` `{snip:ctor}` `` | Type the live-template abbreviation `ctor` at typing pace, hold for 200 ms so the viewer sees the prefix sitting at the caret, then press Tab to expand the IDE snippet. Works with any registered live template (Rider/ReSharper templates, IntelliJ live templates, language-specific snippets). The Tab keystroke is dispatched through the full IDE event pipeline so whatever expansion handler the language ships with — IntelliJ's, ReSharper's, etc. — picks it up exactly as it would for a real keypress. |
| `` `{snip:ctor:500}` `` | Same as above, but with an explicit 500 ms hold before Tab — overrides the 200 ms default. |
| `` `{key:tab}` `` | Press Tab once at the caret (with the keyboard click sound). Goes through the IDE's editor-tab action handler — typically inserts a tab/spaces or advances indentation, the same as a real Tab key with no higher-priority keymap binding active. **Does not** trigger snippet expansion (use `` `{snip:...}` `` for that — it routes through the keymap dispatcher so live-template handlers can win). |
| `` `{key:enter}` `` | Press Enter once at the caret (with sound). Goes through the IDE's smart-enter handler, so language-aware behaviors fire (auto-indent, brace splitting, etc.). |
| `` `{key:alt+enter}` `` | Press Alt+Enter to open the IDE's intentions popup. Routed through `IdeEventQueue` so the keymap dispatcher fires `ShowIntentionActions`. Editor focus is forced before the dispatch so the popup opens against the right context. Pair with `` `{pause:...}` ``, `` `{key:up}` ``/`` `{key:down}` ``, and `` `{key:enter}` `` to drive the popup; for the focused import-only flow with submenu navigation use `` `{import}` `` instead. |
| `` `{key:up}` ``, `` `{key:down}` ``, `` `{key:left}` ``, `` `{key:right}` `` | Press an arrow key once. Targets whatever currently has focus rather than forcing the editor — meant for nudging an open popup (e.g. the intentions list opened by `` `{key:alt+enter}` ``). For moving the editor's caret instead, use `` `{caret:up:N}` `` etc. |
| `` `{key:esc}` `` | Press Esc once. Routes through the editor's `EditorEscape` action handler — same chain a real Esc would hit, so it dismisses an active completion popup, hint, or intentions list, falling through to clearing multi-caret selection if there's nothing to dismiss. |

The marker tokens (default `` `{ `` / `` }` ``) are configurable in **Settings** and update live in the macro list and the macro highlighter.

## Custom macros

Click **Edit** at the bottom of the macro list to open a popup that manages your own user-defined macros. Each entry has a **name**, an optional ordered list of **parameters**, an optional **description**, and a **content** body — when the script later contains `` `{name}` `` (or `` `{name:arg1:arg2}` `` for parameterised macros), it gets substituted with that content before the typing pipeline runs. The description (when set) appears under the macro in the dialog's macro list.

- A custom macro named `prop` becomes `` `{prop}` `` in your scripts. Custom macros appear at the bottom of the macro list, separated from the built-ins by a divider line and rendered in italic; double-click (or press <kbd>Enter</kbd>) to insert the syntax at the active tab's caret.
- **Parameters** are positional. Define them as a comma-separated list (e.g. `name, type`) and reference each one in the content as `$name$` (live-template style). Calling `` `{prop:foo:int}` `` then substitutes `$name$` → `foo`, `$type$` → `int`. Missing arguments resolve to empty strings; extras are ignored. Parameter names must be identifier-like (letters, digits, underscore; must start with a letter or underscore) and unique within a macro. Custom macros without parameters are called as `` `{name}` `` and skip substitution entirely.
- Content is plain text and can itself contain other macros — built-in (`` `{pause:500}` ``, `` `{complete:3:Word}` ``, …) or other custom names. Expansion is recursive with cycle protection, so a custom macro that references itself (or two macros that reference each other) won't loop forever.
- The macro **name** can't collide with built-in macros (`pause`, `reformat`, `complete`, `import`, `caret`, `backspace`, `backspace-hold`, `goto`, `snip`, `key`, `br`), can't contain whitespace or `:`, and must be unique.
- Definitions persist across IDE restarts.

## Enrichment

Click **Enrich…** to wrap matching language keywords in your script with `` `{complete}` `` macros automatically — useful when you want a screencast to feel like real typing without authoring every completion beat by hand.

- Built-in keyword presets ship for Kotlin, Java, C#, Python, JavaScript, TypeScript, C++, C, PHP, Ruby, and Go.
- Add custom keywords per language; tune per-keyword minimum and maximum typed-prefix lengths (the typed prefix is randomised inside that range each run).
- Three frequency modes — **All**, **Heavy** (~60% of matches), **Light** (~20%) — control how often a match gets wrapped.
- Per-language presets (including disabled built-ins and custom keywords) persist across IDE restarts.

**Clear macros** strips every macro out of the active tab's script, restoring the plain text — handy for re-running enrichment with different settings.

## Settings

The gear button next to the language picker opens a settings dialog with cross-tab knobs:

- **Typing delay** (1 – 2000 ms) — base pause between characters.
- **Jitter** (0 – 2000 ms) — random ± noise applied to each delay so timing isn't robotic.
- **Macro markers** — opening and closing token pair (default `` `{ `` and `` }` ``).
- **Completion delay** — how long the auto-completion popup stays visible inside `` `{complete}` ``.
- **Pre-execution pause** — added before the first keystroke, so you can hit Start and switch to the editor without losing the opening characters.
- **Macro colour** and **Argument colour** — separate colours for the macro span and its colon-separated arguments inside the dialog's editors.

## What it does to your IDE during a run

- **Auto-import suppression.** While typing runs, `CodeInsightSettings.ADD_UNAMBIGIOUS_IMPORTS_ON_THE_FLY` is forced off and restored when the session ends. Without this, Rider's daemon would auto-add `using`s as soon as a symbol resolved uniquely, defeating the `` `{import}` `` macro.
- **Native IntelliSense.** Single-character inserts route through the IDE's `TypedAction`, which fires the full TypedHandler chain — so the auto-completion popup behaves exactly like a real user is typing, brackets and quotes auto-pair as you'd expect, and language plugins (Rider's C# typing-assist, ReSharper, etc.) see their normal events.
- **Structural auto-pairing.** When the planner sees a matched `{}`, `()`, `[]`, or quoted string in the source, it types the opener (the IDE auto-pairs the closer), then lays the body's leading and trailing whitespace down chunk-by-chunk. The closer ends up on its proper line at the moment the opener is typed, keeping the syntax tree balanced and the highlighter and daemon awake throughout.
- **Indent ownership.** When the IDE auto-indents after a newline, the script's redundant indent characters are silently dropped. Same at the start of a run if the caret is parked on an already-indented blank line — typing the script's leading indent on top would double it.
- **Caret visibility.** Every text or caret command scrolls the caret into view, so the typing stays on screen even if it pushes past the viewport.

<!-- Plugin description end -->

## Installation

- **From the JetBrains Marketplace**: <kbd>Settings</kbd> → <kbd>Plugins</kbd> → <kbd>Marketplace</kbd> → search for "typewriter-plugin" → <kbd>Install</kbd>.
- **From a release zip**: download the [latest release][releases] and use <kbd>Settings</kbd> → <kbd>Plugins</kbd> → <kbd>⚙️</kbd> → <kbd>Install plugin from disk…</kbd>.

[releases]: https://github.com/migus88/typewriter-plugin/releases/latest

## Building from source

```bash
./gradlew buildPlugin    # produces build/distributions/typewriter-plugin-*.zip
./gradlew runIde         # sandbox IDE with the plugin installed
```

The build defaults to **Rider 2025.3.1** via a local install at `/Applications/Rider.app/Contents`. To target something else, edit `gradle.properties`:

```
platformType = RD          # IU for IDEA Ultimate, IC for Community, etc.
platformVersion = 2025.3.1
localPlatformPath =        # blank → download from JetBrains
```

CI runners fall back to the download path automatically.

For a deeper dive into how it works under the hood (and the gotchas it dances around), see [`CLAUDE.md`](CLAUDE.md).

## Credits

Originally forked from [asm0dey/typewriter-plugin][upstream] by [@asm0dey](https://github.com/asm0dey). Built on the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template).
