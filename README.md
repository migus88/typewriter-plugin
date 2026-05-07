# ![](src/main/resources/META-INF/pluginIcon.svg) TypeWriter for JetBrains IDEs

A plugin for recording screencasts of code being written. Author your script in a real code editor, hit a shortcut, and the IDE types the script for you — character by character, with configurable speed, jitter, and IntelliSense imitation.

> This is a fork of [asm0dey/typewriter-plugin][upstream] — see [Differences from the original](#differences-from-the-original) below for what's been added, changed, and removed.

[upstream]: https://github.com/asm0dey/typewriter-plugin

<!-- Plugin description -->

TypeWriter automates a single thing: it makes the IDE *type* a piece of text into the active editor at a configurable, human-feeling speed. Useful for screencasts, demos, and anything else where you'd otherwise be typing the same code in front of a camera.

**Open the dialog with <kbd>Cmd</kbd>+<kbd>Shift</kbd>+<kbd>W</kbd>** (or <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>W</kbd> on Windows/Linux). Author your script in a real code editor with syntax highlighting and completion for any installed language, then click **Start typing** — the IDE will type your script into whatever editor is in focus.

The dialog is non-modal, so you can park it on a second screen and watch the typing happen in your code editor. **Tabs** at the top let you keep multiple scripts ready at once — click **New tab** to add one, double-click a tab name to rename it, click the × on a tab to close it. Each tab has its own text and language; delay, markers, and other settings are shared.

Two run modes, controlled by the **Keep window open after starting** checkbox:
- **Off** (default): the window closes when typing starts; focus is on the target editor.
- **On**: the window stays open during the run, inputs freeze, the **Stop** button is available, and focus returns to the target editor when the run finishes.

All tabs and settings are remembered across IDE restarts.

**Inline template commands** can be embedded in your script:
- `{{pause:1000}}` — pause typing for 1000 ms.
- `{{reformat}}` — reformat the code at the caret.
- `{{complete:N:Word}}` — imitates IntelliSense: types `N` characters of `Word`, surfaces the IDE's completion popup, waits for the configured **Completion delay**, and finishes the word in one chunk.

The dialog has a **Templates** list at the top — double-click any entry to insert it at the active tab's caret. The opening/closing markers (default `{{` / `}}`) are configurable in the dialog.

Identifier characters (letters, digits, `_`) are typed through the IDE's `TypedAction` chain, so the auto-completion popup tracks the typed prefix natively — just like real-user typing. Brackets are auto-paired by the IDE; the planner cooperates with that to keep matching `{}` / `()` / `[]` pairs aligned with the source you authored.

<!-- Plugin description end -->

## Differences from the original

This fork is a substantial rewrite of [asm0dey/typewriter-plugin][upstream]. Both produce the same end result — autotyped code in the IDE — but the user experience and internals are quite different.

### What's new

| | Original | This fork |
|---|---|---|
| **Shortcut** | <kbd>Ctrl/Cmd</kbd>+<kbd>Shift</kbd>+<kbd>T</kbd>, <kbd>W</kbd> (chord) | <kbd>Cmd</kbd>+<kbd>Shift</kbd>+<kbd>W</kbd> (single press) |
| **Authoring input** | Plain `JTextArea` | Full `EditorTextField` — syntax highlighting, completion, brace matching, soft-wrap, folding, line numbers |
| **Multiple scripts** | One textarea + named "snippets" with their own keyboard shortcuts | **Tabs**: each tab keeps its own text and language; rename, close, add via toolbar |
| **Persistence** | Snippets only | Everything — tabs, text, language per tab, delay, jitter, markers, completion delay, "keep open" setting |
| **Dialog modality** | Modal | **Non-modal** — keep it open on a second screen while you watch the typing |
| **Stop control** | None | **Stop button** during a run, plus UI freeze on the dialog while typing is in progress |
| **Templates info** | Documented in the README | **Built-in panel** in the dialog with double-click insert |
| **`{{complete}}` template** | — | New: imitates IntelliSense (type `N` chars → popup → finish the word) |
| **Identifier popup** | Doesn't fire (raw `document.insertString`) | Fires natively (single chars routed through `TypedAction`) |
| **Bracket / quote auto-pair** | Custom `}`-after-newline workaround | IDE's native auto-pair, with the planner cooperating instead of competing |
| **Indentation as one keystroke** | One char per space | Line-leading indent runs collapse into a single tick |
| **`{{complete}}` whitespace bug** | — | Tail absorbs the next ws + first non-ws char so Rider's C# typing-assist can't shuffle whitespace mid-typing |
| **Default template markers** | `<…>` | `{{…}}` |
| **Default delay / jitter** | 100 ms / 0 ms | 50 ms / 30 ms |
| **Configurable completion delay** | — | Yes (default 500 ms) |
| **Build target** | Generic IntelliJ Platform | Local Rider 2025.3.1 install by default; falls back to download for CI |

### What was removed

The original's **per-snippet keyboard shortcuts** feature (saved snippets each bound to their own user-chosen shortcut) is gone — the use case is covered by tabs, which are simpler to manage and don't require shortcut-conflict resolution. If you relied on that feature, the fork's tabs are the closest replacement: keep multiple scripts in tabs, switch with mouse or arrow keys.

### Why

The original is a great minimal tool. The fork extends it for screencast workflows where you'd want to:
- Have several variations of a script lined up and pick one mid-recording (→ tabs).
- Author the script with proper syntax highlighting and completion (→ `EditorTextField`).
- Watch the typing happen on a second monitor without the dialog covering the editor (→ non-modal).
- Show the IDE's IntelliSense popping up during typing as a real user would experience it (→ `TypedAction` routing + `{{complete}}` template).
- Stop a run mid-way without losing the state of the dialog (→ Stop button + UI freeze).

The trade-off is more complexity in the dialog and the planner. If you want a small, focused tool, the upstream is excellent.

## Template syntax

```text
{{pause:1000}}                       — pause for 1000 ms
{{reformat}}                         — reformat at the caret
{{complete:3:Greeting}}              — type "Gre", show popup, wait, complete "eting"
```

The opening (`{{`) and closing (`}}`) markers are configurable in the dialog if your script needs to contain them literally.

## Installation

- **From the JetBrains Marketplace**: <kbd>Settings/Preferences</kbd> → <kbd>Plugins</kbd> → <kbd>Marketplace</kbd> → search for "typewriter-plugin" → <kbd>Install</kbd>.
- **Manually**: download the [latest release][releases] and install via <kbd>Settings/Preferences</kbd> → <kbd>Plugins</kbd> → <kbd>⚙️</kbd> → <kbd>Install plugin from disk…</kbd>.

[releases]: https://github.com/migus88/typewriter-plugin/releases/latest

## Building from source

```bash
./gradlew runIde            # launch a sandbox IDE with the plugin installed
./gradlew buildPlugin       # produce a .zip in build/distributions/
./gradlew check             # tests + Kover coverage
./gradlew runPluginVerifier # validate against recommended IDEs
```

The build is configured for **Rider 2025.3.1** by default, using a local install at `/Applications/Rider.app/Contents`. To target a different IDE, edit `gradle.properties`:

```
platformType = RD          # or IU for IDEA Ultimate, IC for Community, etc.
platformVersion = 2025.3.1
localPlatformPath = /Applications/Rider.app/Contents   # blank to download
```

CI runners don't have a local install, so they automatically fall back to the download path.

## Architecture notes

For a deeper tour of the planner, command primitives, and the gotchas they exist to work around, see [`CLAUDE.md`](CLAUDE.md).

## Credits

- Original plugin: [asm0dey/typewriter-plugin][upstream] by [@asm0dey](https://github.com/asm0dey).
- Fork: [migus88/typewriter-plugin](https://github.com/migus88/typewriter-plugin).
- Built on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
