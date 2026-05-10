# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Keeping README.md in sync

The user-facing description of this plugin lives in [`README.md`](README.md). The block between `<!-- Plugin description -->` and `<!-- Plugin description end -->` is also published as the JetBrains Marketplace listing — `buildPlugin` fails if those markers go missing.

**Every new feature, behaviour change, macro, setting, or shortcut must be reflected in `README.md` as part of the same change.** Concretely:

- New macros → add a row to the **Inline macros** table with at least one example.
- New settings → add an entry under **Settings** describing the knob and its range/units.
- New run-mode behaviour, run-time IDE side effects, or environmental requirements → update **Run modes** or **What it does to your IDE during a run**.
- Renamed / removed features → remove the stale README mention (don't leave dangling references).
- Shortcut / action changes → update the line in the description block that documents the keymap.

If a change is purely internal (refactor, rewording in code comments, test-only) and the README's user-facing copy is unchanged by it, you can skip the README edit — but say so explicitly when reporting the change. Default to "yes, update the README" when in doubt.

## Bumping the version on every commit

Every commit must bump the patch component of `pluginVersion` in [`gradle.properties`](gradle.properties) — i.e. the `X` in `0.0.X`. Increment by one (`0.9.0` → `0.9.1` → `0.9.2`, …) regardless of whether the change is a feature, fix, or pure refactor. Do this as part of the same commit, not as a follow-up. Minor/major bumps (`0.9.x` → `0.10.0`) are reserved for the user to call out explicitly.

## Project

JetBrains IDE plugin (IntelliJ Platform) that auto-types text into the editor at a configurable speed — used for screencasts/demos. Supports inline template commands (`` `{pause:1000}` ``, `` `{reformat}` ``, `` `{complete:N:Word}` ``, `` `{import:N::K}` ``, `` `{import:N:Namespace}` ``, `` `{caret:DIR:N}` ``). Default markers are `` `{ `` / `` }` `` (backtick-wrapped braces, so literal `{`/`}` in the source code don't collide with the template parser).

- Plugin ID: `games.engine-room.typewriter`
- Kotlin package: `games.engineroom.typewriter`
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

1. Strips template markers (default `` `{…}` ``) out of the input to produce a **code-only** view, then runs a stack-based bracket matcher over it (`classify`).
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

Templates are inline directives in the source text, wrapped in the configured opening/closing markers (default `` `{ `` / `` }` ``). Parser splits on the *first* colon to get the command name; everything after is the args body, parsed per-command:

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

### `{{import}}` — driving Rider's Alt+Enter popup

`ImportCommand` has two modes, picked by whether `namespace` is null:

- **Auto** (`namespace == null`) — drives the IDE's actual Alt+Enter intentions popup. The flow:
  1. Hide any active auto-completion `Lookup`. The completion popup is otherwise alive while the typewriter types identifiers, and (a) it occludes the intentions popup visually, (b) it offers a `LookupList` JList that our popup-discovery would mistake for the intentions list. Hide it before dispatching.
  2. **Wait for the daemon** to flag a diagnostic at the caret. We don't sleep a fixed amount — we explicitly call `DaemonCodeAnalyzer.restart(virtualFile)` to bypass ReSharper's debounce, then poll `DocumentMarkupModel.forDocument(...).allHighlighters` every 100ms looking for a `HighlightInfo.fromRangeHighlighter(...)` with `severity ≥ WARNING` whose range contains the caret offset. Up to a 3-second budget; on timeout we proceed anyway. **This is the difference between Rider's general "Create type / Refactor / …" popup (image 2 in PR discussion) and the focused import-only popup (image 3).** Without the wait, ReSharper hasn't told the popup what's wrong with the symbol, so Rider falls back to the general view.
  3. **Dispatch Alt+Enter via `IdeEventQueue.postEvent`**, *not* `component.dispatchEvent`. The latter delivers the event directly to the focused component, bypassing `IdeKeyEventDispatcher` (the layer that consults the keymap and routes Alt+Enter to `ShowIntentionActions`). We were stuck on this for a while — `ActionManager.tryToExecute` and `component.dispatchEvent` both produced no popup. Posting through `IdeEventQueue` runs the event through the IDE's full pipeline, exactly as a real keystroke would.
  4. **Find the popup.** Searches the focused window's `JList` first (after navigating into a submenu, the submenu owns focus), then any visible AWT window. Skips the typewriter dialog (`MyDialog` window class) and the auto-completion popup (`LookupList` list class) explicitly.
  5. **Navigate into "Import type…" submenu** when present. Rider's full intentions popup has "Import type…" as a parent item with a submenu marker (`▶`). The submenu (e.g. `System.Drawing.Color`, `UnityEngine.Color`, `…`) is what the user actually wants. We scan list items via reflection (`getText`, `getActionText`, `getValue`, `getName`, falling back to `toString()`), find the index whose text starts with "Import type", animate Down arrows to reach it, then post a `Right` keystroke to expand. After a 250ms render wait, we re-find the (now-focused) submenu list.
  6. **Reading window** — `Thread.sleep(visibleDelayMs)` while the popup is on screen so the viewer reads the options.
  7. **Animate selection** — post `Down` arrow keystrokes one at a time via `IdeEventQueue.postEvent`, with `stepDelayMs` (= the typewriter's base `delay`) between each. The viewer sees the highlight crawl down to the chosen index.
  8. **Post Enter** to accept. Restore focus to the editor (the popup steals focus when shown).

  **Auto-import suppression.** `TypeWriterDialog` toggles `CodeInsightSettings.ADD_UNAMBIGIOUS_IMPORTS_ON_THE_FLY` (typo preserved by IntelliJ for backwards compat) off for the duration of a typing run. Without that, Rider's daemon would auto-add the using as soon as the symbol resolves uniquely, so `{{import}}` would either find no diagnostic (already resolved) or apply the wrong using (whichever ReSharper picked first). The toggle's `RestoreHandle` is idempotent and fires from the scheduler's `onDone` callback, which runs on both natural completion and cancellation.

- **Explicit** (`namespace != null`) — bypasses the daemon and popup entirely. Inserts a language-appropriate import statement (`using` / `import` / `#include` / `require` / `use`) at the document offset returned by `findImportInsertionOffset(text)` — that walker scans the file's prelude looking for a contiguous run of import-style declarations and lands the new line right after, falling back to offset 0 when there are none.

  Format: `{{import[:delayMs[:Namespace[:optionIndex]]]}}`. The first segment is treated as a delay only when it's purely digits, so `Foo.Bar` (no leading number) is interpreted as a namespace. Empty namespace (e.g. `{{import:5000::3}}`) means auto mode with delay + option index.

### `{{caret:DIR:N}}` — directional caret movement

`CaretMoveByDirectionCommand` does one `caret.moveCaretRelatively(...)` step per command. The parser emits N copies so each press takes its own typewriter tick (and the standard delay/jitter pacing applies between presses, same way `complete` and the bracket-pair sequencer work). The action accepts both `caret` and `carret` so the typo is forgivable.

### `{{backspace:N}}` and `{{backspace-hold:N}}` — deletion

Two macros, two routing rules:

- `{{backspace:N}}` parses to **N** copies of `BackspaceCommand`, each going through `IdeActions.ACTION_EDITOR_BACKSPACE` so language smart-backspace fires (Python dedent, Rider whitespace fixups, etc.). One click sound + jittered pause per press — paced like real-user typing.
- `{{backspace-hold:N}}` parses to **one** `BackspaceHoldCommand` that loops N per-character `Document.deleteString(end - 1, end)` calls (each inside its own `WriteCommandAction`) at the typewriter's pace, but plays **one** click sound at the start — modelling a single held key rather than N tapped presses. Bypasses the action handler so language smart-backspace doesn't fire mid-burst.

`BackspaceHoldCommand` stops early if the caret reaches offset 0 before N chars have been removed. Both commands set `indentOwnedByIde = false` since they explicitly mutate the document/caret.

### `{{goto:TARGET}}` and `{{goto:TARGET:ANCHOR}}` — caret walk to a string

`GotoCommand` is a **single command that internally loops** rather than a sequence of pre-planned arrow-key commands. The reason is timing: the search resolves against the *destination editor*'s document at execution time (the script may have already typed text into it), so the path can't be computed at planning time.

Search rule:
- No anchor → first occurrence of `target` from offset 0 wins.
- With anchor → find `anchor` first from offset 0, then search for `target` starting where `anchor` ends. The anchor is purely a search-bound disambiguator; only `target`'s end position matters for the landing offset.

Stepping rule (recomputed each tick from the live caret offset):
1. `currentLine < targetLine` → press Down.
2. `currentLine > targetLine` → press Up.
3. Same line, `currentCol < targetCol` → **Alt+Right** (word-skip) when the next word boundary on the current line lands at-or-before the target; otherwise plain Right.
4. Same line, `currentCol > targetCol` → **Alt+Left** when the previous word boundary lands at-or-after the target; otherwise plain Left.

Word-skip jumps go through `Caret.moveToOffset(predictedBoundary)` rather than the IDE's word-navigation actions, so the landing point is fully predictable — we don't have to detect overshoot from the IDE's own boundary heuristics. The boundary predictor approximates common alt+arrow behaviour: skip a run of non-word chars, then a run of word chars (where "word" = `Char.isLetterOrDigit() || c == '_'`). Movement is bounded to the current logical line so word-skip can't accidentally cross into another line.

Each step plays a click sound and uses the typewriter's `delay` + `jitter` for pacing — so the caret crawl feels like the same hand that's typing the script. The loop has a `(documentLength + 64)`-step safety budget so a logic bug can't burn unbounded time. By construction, `targetCol ≤ targetLine.length` (the offset comes from a substring match within the document), so convergence is well-defined and the budget should never be exhausted in practice.

Args parsing splits `rest` on the **first** colon — `target` is everything before, `anchor` is everything after (or null). Neither part may itself contain `:`. Failed lookups silently no-op (after the post-pause), matching the rest of the macro set.

The dialog itself surfaces the available templates in a **Templates** `JBList` at the top (above the language combo and tabs). Each entry is a `TemplateEntry(kind)` where `kind` is the `TemplateKind` enum (`PAUSE`, `REFORMAT`, `COMPLETE`, `IMPORT_AUTO`, `IMPORT_NS`, `IMPORT_OPTION`, `CARET`). The list's cell renderer rebuilds the syntax string on every paint by reading the live `openingSequence`/`closingSequence` properties. Double-click or Enter calls `insertTemplate(entry)`, which writes the rendered syntax at the active tab's caret inside a `WriteCommandAction` and re-focuses the editor field.

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
games.engineroom.typewriter
├── TypeWriterAction                  # Action entry; opens the (non-modal) dialog. Hosts the template parser (executeTyping)
├── TypeWriterDialog                  # Modeless dialog: tabs + language picker + delay/jitter/markers + keep-open + suppress-auto-import
├── TypeWriterSettings                # @Service(APP) PersistentStateComponent — survives IDE restart
├── TypewriterExecutorService         # @Service(APP) single-threaded scheduler, Disposable
├── AutoImports                       # Toggle CodeInsightSettings.ADD_UNAMBIGIOUS_IMPORTS_ON_THE_FLY around a session
├── Enrichment                        # Per-language keyword presets + enrich/unenrich text transforms
├── EnrichDialog                      # Modal popup configuring the enrichment keywords + frequency mode
├── TypeWriterBundle / TypeWriterConstants
└── commands/
    ├── Command                            # marker interface : Runnable
    ├── WriteTextCommand                   # TypedAction (single char) or insertString (multi-char) + caret.moveToOffset + scrollToCaret
    ├── MoveCaretCommand                   # caret.moveToOffset(+delta) + scrollToCaret
    ├── CaretMoveByDirectionCommand        # caret.moveCaretRelatively(dir) — one step per command
    ├── TriggerAutocompleteCommand         # AutoPopupController.scheduleAutoPopup + sleep + dismiss
    ├── ImportCommand                      # Auto: drives Rider's Alt+Enter popup with arrow-key animation. Explicit: inserts a language-appropriate import line
    ├── PauseCommand                       # Thread.sleep
    └── ReformatCommand                    # invokes the "ReformatCode" action
```

### Things to watch for when modifying

- `TypewriterExecutorService` is a single-threaded scheduler — commands run sequentially. Don't replace it with a pool unless you also redesign how `WriteTextCommand` interacts with the document.
- `WriteTextCommand` captures the target `Editor` in its constructor — the dialog resolves it from `FileEditorManager.selectedTextEditor` at the moment "Start" is clicked, not when the action fires. If the user focuses elsewhere mid-typing, behavior is undefined; that's an acceptable limitation given the demo use case.
- Template parsing is regex-based on the user-supplied opening/closing sequences. Always feed them through `Regex.escape` before composing the pattern (already done).
- The IDE auto-pair setting (`CodeInsightSettings.AUTOINSERT_PAIR_BRACKET`) must be **on** for the bracket-pair planning math to be right — `emitAutoPair` doesn't type the closer itself, it relies on the IDE adding it. If a user disables auto-pair globally, brackets in their typed-out script will be missing closers. Force-enabling it for the duration of a session (the way the old `TypingSession` did) is an option if this becomes a problem.
- Use the IntelliJ Platform Gradle Plugin 2.x APIs (`intellijPlatform { ... }` block), not the older `intellij { ... }` DSL. Version catalog lives in `gradle/libs.versions.toml`.
- The auto-import path's keystroke trickery (Alt+Enter dispatch + popup discovery + Right-to-submenu + Down-arrow animation) went through several dead ends before settling on the current shape. Don't simplify it back to one of those without re-testing in Rider C#:
  - `ShowIntentionsPass.getActionsToShow(editor, file)` returns an empty list for Rider C# — ReSharper's intentions go through their own protocol surface and don't appear in IntelliJ's `IntentionsInfo`. So our own popup, mirroring that list, is empty in Rider.
  - `ActionManager.tryToExecute("ShowIntentionActions", …)` and `component.dispatchEvent(KeyEvent)` both fail to show the popup. Only `IdeEventQueue.postEvent(…)` engages `IdeKeyEventDispatcher` and triggers the action.
  - Without a daemon-ready wait before dispatch, Rider shows the *full* intentions popup (Create type / Refactor / …) instead of the focused import-only one. Polling `DocumentMarkupModel.forDocument(…).allHighlighters` for an error-or-warning highlight at the caret is the signal that the daemon has flagged the symbol; once we see one, the popup will be the right one.
  - The auto-completion popup (`LookupList` JList) is alive while the typewriter types identifiers and competes with the intentions popup. Hide the active `Lookup` before dispatching, and skip `LookupList` explicitly when scanning for the popup to drive.
