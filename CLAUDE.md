# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

JetBrains IDE plugin (IntelliJ Platform) that auto-types text into the editor at a configurable speed — used for screencasts/demos. Supports inline template commands (`{{pause:1000}}`, `{{reformat}}`, `{{complete:N:Word}}`).

- Plugin ID: `com.github.asm0dey.typewriterplugin`
- Default development target: Rider 2025.3.1 (build 253) via local install at `/Applications/Rider.app/Contents`. The build also works against any IntelliJ Platform IDE — see "Targeting another IDE" below.
- Kotlin 2.3 / JVM toolchain 21
- Build system: Gradle (Kotlin DSL) + IntelliJ Platform Gradle Plugin 2.x

## Common commands

```bash
./gradlew buildPlugin         # Build the distributable .zip (build/distributions/)
./gradlew runIde              # Launch a sandbox IDE (Rider by default) with the plugin installed
./gradlew check               # Run tests + Kover XML coverage report (onCheck = true)
./gradlew test                # Tests only
./gradlew test --tests "*TypeWriterPluginTest.testDummy"   # Run a single test
./gradlew verifyPlugin        # Validate plugin.xml + manifest
./gradlew runPluginVerifier   # Run JetBrains Plugin Verifier against recommended IDEs
./gradlew patchChangelog      # Move "Unreleased" notes to the current version section
./gradlew publishPlugin       # Publish to JetBrains Marketplace (CI uses env vars)
```

Publishing/signing is driven by env vars: `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`, `PUBLISH_TOKEN`. Don't set these locally.

The plugin's `<!-- Plugin description -->` block in `README.md` is the source of truth for the marketplace description — `buildPlugin` will fail the build if those markers are missing.

### Targeting another IDE

`build.gradle.kts` chooses the platform via this block in the `intellijPlatform { ... }` configuration:

```kotlin
val localPath = providers.gradleProperty("localPlatformPath").orNull
if (!localPath.isNullOrBlank() && file(localPath).exists()) {
    local(localPath)
} else {
    create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))
}
```

So:
- Local-installed IDE wins when `localPlatformPath` resolves to an existing directory. CI runners don't have `/Applications/...`, so they fall through to the download path automatically — no CI changes needed when changing the local path.
- To download a different platform, change `platformType` + `platformVersion` in `gradle.properties` (e.g. `IU` + `2024.3` for IDEA Ultimate). Stop the daemon (`./gradlew --stop`) after editing — config cache holds onto stale values.

## Architecture

### Typing pipeline (the core flow)

`executeTyping` in `TypeWriterAction.kt` is the single entry point. It:

1. Strips template markers (default `{{…}}`) out of the input to produce a **code-only** view, then runs a stack-based bracket matcher over it (`classify`).
2. For every correctly-nested `{}`, `()`, `[]` pair, the matcher classifies each source position:
   - **opener** → `AutoPair` carrying the body's leading and trailing whitespace
   - **leading whitespace** between opener and body → `ConsumeOnly` (the auto-pair sequence already inserts these; the source-walker emits no command for them)
   - **trailing whitespace** + the **closer** → `SkipChar` (caret steps over what's already there)
3. Walks the original text. The walker handles three kinds of "chunking":
   - **AutoPair openers** expand into a *sequence* of commands (see below).
   - **Consecutive `SkipChar` positions** collapse into a single `MoveCaretCommand(count)`.
   - **Line-start whitespace runs** in unclassified body chars collapse into a single `WriteTextCommand` — that's the "indentation as one keystroke" rule.
4. For each template match, appends a `PauseCommand` or `ReformatCommand`.
5. Hands the list to `TypewriterExecutorService.start(commands, onDone)` as one session. `Thread.sleep` inside each command provides the inter-character pacing.

### Auto-pair as a sequence

When the walker hits an opener, `emitAutoPair` lays the structure down left-to-right with one command — and one paused tick — per piece:

1. `WriteTextCommand(opener)` — typed via TypedAction (single-char route). **The IDE auto-pairs the closer here.** Doc grows by 2 characters (opener + auto-paired closer); caret lands between them.
2. One `WriteTextCommand` per chunk of the body's leading whitespace. `chunkWhitespace` keeps each `\n` together with the indent that follows it — so `"\n    "` is *one* tick, `"\n\n    "` is two (`"\n"` then `"\n    "`), and a pure indent with no preceding newline is its own chunk. Each chunk inserts at the caret and pushes the auto-paired closer rightward.
3. One `WriteTextCommand` per chunk of trailing whitespace, in source order. Same idea — pushes the closer further right.
4. `MoveCaretCommand(-trailing.length)` jumps the caret back from past-trailing to the body slot. **No explicit closer is typed** — the IDE's auto-paired one is already there, and the source's matching closer is classified `SkipChar` and will advance the caret past it when reached.

By the time step 4 finishes, the document holds the entire `opener + leading + trailing + closer` structure in its final shape, the caret is on the body line at the right column, and every step took its own delay-jitter pause. The body chars that follow type into the slot via TypedAction (so the auto-popup stays alive during identifier typing).

The pairs map is computed against the template-stripped concat, so brackets split across template markers (`class X {\n{{pause:1000}}\n}`) still pair up.

Caveat: the matcher is naive char-level — it doesn't understand strings or comments. A `{` inside `"json: { foo }"` will get auto-paired, which is wrong. Teach the matcher to skip ranges between matched quote/comment delimiters if that becomes an issue.

### Template commands

Templates are inline directives in the source text, wrapped in the configured opening/closing markers (default `{{` / `}}`). Parser splits on the *first* colon to get the command name; everything after is the args body, parsed per-command:

- `{{pause:N}}` — `PauseCommand(N)` (milliseconds).
- `{{reformat}}` — `ReformatCommand`.
- `{{complete:N:Word}}` — auto-completion imitation. Splits the args body again on the first `:` so `Word` can contain anything (including more colons). Expands to: `WriteTextCommand(prefix)` for the first `N` chars, then `TriggerAutocompleteCommand(completionDelay)` to schedule the popup + sleep + dismiss, then `WriteTextCommand(tail)` for the remainder — visually the same shape as a user typing a few chars and accepting a suggestion.

  **Four gotchas burned into this code:**
  1. The raw body is *not* trimmed before splitting. Trimming would eat trailing whitespace inside the marker (e.g. `{{complete:3:private }}` is the user asking for `"private "`). Only `name`, the `pause` value, and the `complete:N` argument are trimmed individually.
  2. `TriggerAutocompleteCommand` *dismisses* the lookup at the end of its sleep. Leaving the lookup alive made the next `WriteTextCommand`'s leading space disappear — IntelliJ's lookup treats space as an accept-and-consume completion character, eating the typed character whole.
  3. **Don't call `CodeCompletionHandlerBase.invokeCompletion` here.** It inserts a dummy identifier into the document during prefix analysis and rolls it back; the round-trip kicks Rider's typing-assist / ReSharper formatter into shuffling whitespace around the typed word. Use the lighter `AutoPopupController.scheduleAutoPopup` instead — it doesn't touch the document.
  4. **Tail absorbs whitespace + first non-whitespace char from the next segment.** Without this, when the user writes `{{complete:3:private}} readonly...`, the doc transiently lands on `<indent>private ` (keyword + trailing space) between the tail-write and the next-char-write. Rider's C# typing-assist sees that exact state, "fixes" it by moving the trailing space onto the leading indent — symptom: `private readonly` rendering as ` privatereadonly` (one space added at the start of the line, the internal space gone). Including the next non-WS char in the tail's single insert (`"vate r"` instead of `"vate"` then `" "`) skips that intermediate state. The planner advances `consumedAfterTemplate` chars past the `}}` so the absorbed chars aren't re-typed by the next `appendSegment`.

`WriteTextCommand` doesn't schedule auto-popup directly. Instead, single-char inserts route through `TypedAction` (see "Command primitives" below), which fires the IDE's TypedHandler chain — and *that's* what keeps the auto-completion popup alive natively, the way it does for real-user typing. An earlier iteration called `scheduleAutoPopup` from `WriteTextCommand` after every keystroke and caused the popup to flicker (each schedule resets the alarm; the next insert closes the popup via the lookup's prefix-tracker; the next schedule reopens it; repeat). Don't bring that back.

`completionDelay` is a global setting (`TypeWriterSettings.completionDelay`, exposed in the dialog). Per-`{{complete}}` config is just the `N` argument.

The dialog itself surfaces the available templates in a **Templates** `JBList` at the top (above the language combo and tabs). Each entry is a `TemplateEntry(kind)` where `kind` is the `TemplateKind` enum (`PAUSE`, `REFORMAT`, `COMPLETE`). The list's cell renderer rebuilds the syntax string on every paint by reading the live `openingSequence`/`closingSequence` properties. Double-click or Enter calls `insertTemplate(entry)`, which writes the rendered syntax at the active tab's caret inside a `WriteCommandAction` and re-focuses the editor field.

  **bindText gotcha:** the kotlin UI DSL's `bindText(::property)` only flushes the field's value to the bound property on `panel.apply()` — i.e., on OK. Reading `openingSequence` mid-edit gives you the *original* value, not what the user just typed. The dialog therefore captures `openingField` / `closingField` references and installs a `DocumentListener` that mirrors each keystroke into both properties **and** repaints `templateList`. Without this, the templates list and the inserted syntax both go stale.

### Focus and caret visibility

The dialog is a separate window from the IDE. `Component.requestFocusInWindow()` only moves focus *within* a window — useless for going from the dialog back to the editor. Use `IdeFocusManager.getInstance(project).requestFocus(component, /*forced=*/true)` instead.

Two places we explicitly do this:
1. **Start of a `keepOpen=true` run** — we transfer focus to the target editor so its caret blinks during typing. Without this the editor is unfocused and the caret renders statically (or off-screen if not scrolled into view), making it hard to see.
2. **End of a `keepOpen=true` run** — we *keep* focus on the editor (`onTypingDone`). Otherwise the dialog naturally reclaims focus when re-enabled, which the user does not want when they're watching their newly-typed code.

`WriteTextCommand` and `MoveCaretCommand` both call `editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)` after they touch the caret, so the caret stays on screen even if the typed-in line moves below the viewport.

### Why structural auto-pair?

Matched brackets land in the document at the same instant *along with the surrounding whitespace from the source*. So if the source is

```
class X
{
    body
}
```

the moment the user's `{` is typed, the document already contains `{\n    \n}` with the caret on the indented blank line — the closer is on its proper line from the start, not "riding along" with the body until a later newline pushes it down.

This also keeps the syntax tree balanced at every moment of the run, which is what allows the IDE's highlighter (lexer-based) and the daemon code analyzer (semantic) to keep up — typing into an open `{` with no closer leaves the parser in an "incomplete code" state and many language plugins delay analysis until the syntax recovers, which manifests as un-highlighted text mid-run.

The pre-scan ignores template markers — so if your script is `class X {\n{{pause:1000}}\n}`, the `{` and `}` still pair up across the pause. Bracket-matching that fails (mismatched / unbalanced input) leaves the offending characters unclassified and they fall through to plain typing.

Caveat: the matcher is naive char-level — it doesn't understand strings or comments. A `{` inside `"json: { foo }"` will get auto-paired, which is wrong. If that becomes an issue, the next step is teaching the matcher to skip ranges between matched quote/comment delimiters.

### TypewriterExecutorService — sessions, stop, and `onDone`

App-level `@Service`. Wraps a single-threaded `ScheduledExecutorService`. Exposes:

- `start(commands, onDone)` — schedules every command plus a final "complete" task. Refuses to start if a session is already running (the dialog disables Start while running, so this shouldn't happen).
- `stop()` — cancels every scheduled future with `cancel(true)`. The currently-running command unblocks via `InterruptedException` from `Thread.sleep`. Cancelled queued tasks never run.
- `isRunning` — used by the dialog to gate UI state.

`onDone` fires on the EDT exactly once per session — whether the session completed naturally (final task ran) or was cancelled (`stop` triggered the same completion path). The `AtomicBoolean` `completed` guards against double-fire.

The dialog uses `onDone` to thaw its inputs (or close, if `keepOpen` is off).

### Command primitives

Two primitives:

- `WriteTextCommand(text, …)`: routes single-char inserts through `TypedAction.actionPerformed(editor, char, dataContext)` and multi-char inserts (or `\n`) through `Document.insertString`. The TypedAction route fires the IDE's full TypedHandler chain — keeping the auto-completion popup alive natively, letting the IDE auto-pair brackets/quotes, and giving language plugins (Rider's C# typing-assist, ReSharper, etc.) their expected typing events. The insertString route handles `\n` (so we don't smart-expand indentation in conflict with the source's explicit indent) and multi-char chunks (line-start indent runs, absorbed-tail blocks from `{{complete}}`).
- `MoveCaretCommand(delta, …)`: `caret.moveToOffset(caret.offset + delta)` on the EDT, no document change. Positive delta steps over auto-paired closers / trailing whitespace; negative delta jumps back to the body slot after an auto-pair.

`Thread.sleep` for the post-tick pause is outside the write action / EDT call.

**The IDE auto-pairs brackets and quotes** when we type the opener via TypedAction. The planner cooperates with this — `emitAutoPair` types the opener and the leading/trailing whitespace chunks, but **does not type the closer itself**. The auto-paired closer fills that role; the source's matching closer is classified `SkipChar` and just advances the caret past the auto-paired character. Move-back delta is therefore `-trailing.length` (was `-(trailing.length + 1)` back when the planner typed its own closer).

Past iterations of these primitives went through several reverts (`document.insertString` for everything, then back, then per-char TypedAction with various flavors) — the trade-off is summarized in the commit history. Don't change the routing rule without explicit user request, and re-test the full set of bug scenarios (`{{complete}}` + space, multi-line `{}` blocks at indent, popup flicker) before committing changes here.

### TypeWriterDialog

UI is built with the IntelliJ Kotlin UI DSL (`panel { row { ... } }`). It is **non-modal** (`IdeModalityType.MODELESS`, `isModal = false`) — the user can keep it open while clicking around the IDE, and the active editor is resolved at "Start" time, not at action-trigger time.

**Tabs.** A `JBTabbedPane` hosts one `EditorTextField` per tab. Each tab has its own `TabState` (name, `FileType`, editor field). Tab headers are custom `JPanel`s with a label + close (`AllIcons.Actions.Close`) icon. A toolbar above the tabbed pane has the language combo (which follows the active tab) and a `New tab` button. Tab management:
- `addNewTab()` appends a `TabState`, registers a new tab + custom header, selects it.
- `closeTab(state)` removes the tab; refuses if it's the last one.
- `nextTabName()` finds the highest `Tab N` integer in current names and adds one.
- `beginInlineRename(header, label, state)` swaps the tab's `JLabel` for a `JTextField` on double-click. Enter / focus loss commits, Escape cancels. Both the new name and the `JTabbedPane` title are updated, and persistence on dispose picks up the change.

**Tab selection gotcha:** when a JTabbedPane has a custom tab component (our `JPanel` with label + close button), `JTabbedPane`'s built-in click-to-select listener doesn't fire on clicks that land on the children — Swing dispatches the click straight to the child. Without an explicit listener, clicking the tab *name* fails to switch tabs even though clicking the gap around the name works. The `selectListener` `MouseAdapter` is attached to both the panel and the label; on a single left-click it programmatically sets `tabbedPane.selectedIndex` to this tab's index.

Per-tab data persists via `TabData` (`name`, `text`, `fileTypeName`). The dialog rebuilds tabs from `settings.tabs` on construction; `persistSettings()` flushes them on dispose / OK.

**Editor backing.** Each tab's `EditorTextField` is backed by a `LightVirtualFile` so PSI is available — that's what enables completion, brace matching, formatting, indent guides, and the rest of the "real editor" experience inside the dialog. Changing the language combo creates a fresh `LightVirtualFile` of the new `FileType` and calls `setNewDocumentAndFileType` so the highlighter switches without losing the user's text. The `suppressLanguageListener` flag prevents reentrancy when the combo's selection changes due to a tab switch (rather than user action).

**Run modes.** `doOKAction` branches on the persisted `keepOpen` flag:
- **`keepOpen = false`** — calls `super.doOKAction()` to close the dialog *before* `executeTyping`. Focus returns to the IDE editor; typing then plays into it. There's no Stop button (the dialog is gone), and `onDone = {}` (nothing to thaw).
- **`keepOpen = true`** — does *not* close the dialog. Calls `setUiEnabled(false)` to freeze every input, then runs typing with `onDone = ::onTypingDone`. When the run finishes (or `Stop` cancels it), `onTypingDone` re-enables the UI and explicitly calls `IdeFocusManager.getInstance(project).requestFocus(targetEditor.contentComponent, true)` so focus stays on the typed-into editor — not the dialog. (`Component.requestFocusInWindow()` only moves focus *within* a window; the dialog and IDE editor are separate windows, so it's useless here.)

`setUiEnabled(false)` walks the `dialogPanel` tree disabling every component, then calls `setViewer(true)` on every tab's `EditorTextField` so the code areas become read-only (plain `isEnabled = false` doesn't prevent typing into an `EditorTextField`). Start/Stop actions are toggled so only one is enabled at a time.

`doCancelAction` and `dispose()` both call `scheduler.stop()` — closing the window stops any in-flight typing. `dispose()` also makes a best-effort save of the current state.

### TypeWriterSettings

Application-level `PersistentStateComponent` (`@Service(APP)`, stored in `typewriter.xml`). Holds:
- `tabs: MutableList<TabData>` and `activeTabIndex: Int` — per-tab state.
- `delay`, `jitter`, `openingSequence`, `closingSequence`, `keepOpen`, `completionDelay` — shared across tabs.
- `text` and `fileTypeName` — *legacy* fields. They stay on the class so old XML loads cleanly; `loadState` folds any non-empty legacy content into a single seed `TabData` and clears the originals.

**Serialization gotcha:** the `tabs` field is annotated `@get:XCollection(style = XCollection.Style.v2)`. Without that, IntelliJ's `XmlSerializer` doesn't reliably round-trip a `MutableList<CustomClass>` — the list writes out, but the next session's `loadState` reads it back empty, and tabs silently revert. `TabData` itself uses `@Tag("tab")` for the element name and `@Attribute` on the short string fields, so a saved XML looks like `<tab name="..." fileType="..."><option name="text" value="..."/></tab>`.

### Module layout

```
com.github.asm0dey.typewriterplugin
├── TypeWriterAction              # Action entry; opens the (non-modal) dialog
├── TypeWriterDialog              # Modeless dialog: EditorTextField + language picker + delay/jitter/markers + "keep open"
├── TypeWriterSettings            # @Service(APP) PersistentStateComponent — survives IDE restart
├── TypewriterExecutorService     # @Service(APP) single-threaded scheduler, Disposable
├── TypeWriterBundle / TypeWriterConstants
└── commands/
    ├── Command                       # marker interface : Runnable
    ├── WriteTextCommand              # insertString(text) + caret.moveToOffset + scrollToCaret
    ├── MoveCaretCommand              # caret.moveToOffset(+delta) + scrollToCaret
    ├── TriggerAutocompleteCommand    # AutoPopupController.scheduleAutoPopup + sleep
    ├── PauseCommand                  # Thread.sleep
    └── ReformatCommand               # invokes the "ReformatCode" action
```

### Things to watch for when modifying

- `TypewriterExecutorService` is a single-threaded scheduler — commands run sequentially. Don't replace it with a pool unless you also redesign how `WriteTextCommand` interacts with the document.
- `WriteTextCommand` captures the target `Editor` in its constructor — the dialog resolves it from `FileEditorManager.selectedTextEditor` at the moment "Start" is clicked, not when the action fires. If the user focuses elsewhere mid-typing, behavior is undefined; that's an acceptable limitation given the demo use case.
- Template parsing is regex-based on the user-supplied opening/closing sequences. Always feed them through `Regex.escape` before composing the pattern (already done).
- The IDE auto-pair setting (`CodeInsightSettings.AUTOINSERT_PAIR_BRACKET`) must be **on** for the bracket-pair planning math to be right — `emitAutoPair` doesn't type the closer itself, it relies on the IDE adding it. If a user disables auto-pair globally, brackets in their typed-out script will be missing closers. Force-enabling it for the duration of a session (the way the old `TypingSession` did) is an option if this becomes a problem.
- Use the IntelliJ Platform Gradle Plugin 2.x APIs (`intellijPlatform { ... }` block), not the older `intellij { ... }` DSL. Version catalog lives in `gradle/libs.versions.toml`.
