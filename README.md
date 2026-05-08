# ![](src/main/resources/META-INF/pluginIcon.svg) TypeWriter for JetBrains IDEs

A plugin for recording screencasts of code being written. You write the script, the IDE types it for you — character by character, at a configurable speed.

This is a fork of [asm0dey/typewriter-plugin][upstream], rebuilt around screencast workflows.

[upstream]: https://github.com/asm0dey/typewriter-plugin

<!-- Plugin description -->

TypeWriter makes the IDE *type* a piece of text into the active editor at a configurable, human-feeling speed. Useful for screencasts, demos, and anything where you'd otherwise be typing in front of a camera.

Hit <kbd>Cmd</kbd>+<kbd>Shift</kbd>+<kbd>W</kbd> (or <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>W</kbd> on Windows/Linux) to open the dialog. Author your script in a real code editor — syntax highlighting and completion for any installed language — then click **Start typing**. The IDE will type the script into whatever editor is in focus.

The dialog is non-modal, so you can park it on a second screen while you watch the typing happen. **Tabs** at the top let you keep multiple scripts ready at once: click **New tab** to add one, double-click a tab to rename, click the × to close. Each tab has its own text and language.

Two run modes via the **Keep window open after starting** checkbox:

- **Off** (default) — the window closes when typing starts; focus is on the editor.
- **On** — the window stays open with inputs frozen and a **Stop** button available.

Tabs and settings persist across IDE restarts.

**Inline templates** can be embedded in your script:

| Template | What it does |
|---|---|
| `{{pause:1000}}` | Pause typing for 1000 ms |
| `{{reformat}}` | Reformat the code at the caret |
| `{{complete:3:Greeting}}` | Imitates IntelliSense — types `Gre`, surfaces the popup, waits, then drops `eting` in one chunk |
| `{{import:5000::3}}` | Drives the IDE's Alt+Enter intentions popup. Waits for the daemon to flag the unresolved symbol, opens the popup, navigates into Rider's "Import type…" submenu, animates the highlight down to the 3rd option (each step paced like a typed character), and presses Enter. The `5000` is the read window in ms before the cursor starts moving |
| `{{import:5000:UnityEngine.Color}}` | Explicit form — inserts a language-appropriate import line (`using` / `import` / `#include` / `require` / `use`) at the top of the file, after any existing imports |
| `{{caret:up:3}}` | Move the caret 3 steps in the chosen direction (`up`, `down`, `left`, `right`) at typewriter pace |

The dialog has a **Templates** list at the top — double-click any entry to insert it at the active tab's caret. The opening/closing markers (default `{{` / `}}`) are configurable.

There's also an **Enrich…** button that wraps matching language keywords in your script with `{{complete}}` templates automatically — useful when you want a screencast to feel like real typing without authoring every completion beat by hand. Built-in keyword presets ship for Kotlin, Java, C#, Python, JS, TS, C++, C, PHP, Ruby, and Go; you can add custom keywords per language and tune per-keyword min/max prefix lengths. Three frequency modes (**All / Heavy / Light**) control how often a match gets wrapped.

<!-- Plugin description end -->

## What this fork changes

A short list of the things you'd notice first:

- **Single-press shortcut.** <kbd>Cmd</kbd>+<kbd>Shift</kbd>+<kbd>W</kbd> instead of the original's two-key chord.
- **A real code editor for authoring**, not a plain text area. Syntax highlighting, completion, brace matching — all the things you'd expect from a JetBrains IDE.
- **Tabs** instead of the original's named "snippets". Each tab keeps its own text and language; rename and reorder as you like.
- **Non-modal dialog.** Move it to a second screen and let the typing play out in your code editor.
- **Stop button** when a run is in progress, with the dialog frozen so you don't accidentally edit your script mid-take.
- **Native IntelliSense during typing** — the completion popup behaves like a real user is typing, and there's a `{{complete}}` template that performs the "type a few chars, accept the suggestion" gesture for you.
- **Persistent everything.** Open the dialog tomorrow and your tabs and settings are still there.

A few things were removed: the original's per-snippet keyboard shortcuts are gone, since tabs cover the same ground without the shortcut-conflict bookkeeping.

For a deeper dive into how it works under the hood (and the gotchas it dances around), see [`CLAUDE.md`](CLAUDE.md).

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

## Credits

Built on top of [asm0dey/typewriter-plugin][upstream] by [@asm0dey](https://github.com/asm0dey), and on the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template).
