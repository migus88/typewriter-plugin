# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

JetBrains IDE plugin (IntelliJ Platform) that auto-types text into the editor at a configurable speed — used for screencasts/demos. Supports inline template commands (`<pause:1000>`, `<reformat>`).

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

1. Splits the input on the configured opening/closing template markers (default `{_…_}`) using a regex built from `Regex.escape(...)`.
2. For each plain-text segment, calls `WriteCharCommand.fromText(...)` which yields one `WriteCharCommand` per character with `delay ± random(jitter)` ms pause. Leading whitespace on each line is stripped — the IDE's enter-handler re-indents.
3. For each template match, appends either a `PauseCommand` or `ReformatCommand`.
4. The collected list is handed to `TypewriterExecutorService.start(commands, onDone)` as a single session. The `Thread.sleep` inside each command provides the inter-character pacing.

### TypewriterExecutorService — sessions, stop, and `onDone`

App-level `@Service`. Wraps a single-threaded `ScheduledExecutorService`. Exposes:

- `start(commands, onDone)` — schedules every command plus a final "complete" task. Refuses to start if a session is already running (the dialog disables Start while running, so this shouldn't happen).
- `stop()` — cancels every scheduled future with `cancel(true)`. The currently-running command unblocks via `InterruptedException` from `Thread.sleep`. Cancelled queued tasks never run.
- `isRunning` — used by the dialog to gate UI state.

`onDone` fires on the EDT exactly once per session — whether the session completed naturally (final task ran) or was cancelled (`stop` triggered the same completion path). The `AtomicBoolean` `completed` guards against double-fire.

The dialog uses `onDone` to thaw its inputs (or close, if `keepOpen` is off).

### WriteCharCommand

Deliberately minimal. For every character — including `\n` — the command does exactly this inside a `WriteCommandAction`:

```kotlin
val offset = caret.offset
document.insertString(offset, char.toString())
caret.moveToOffset(offset + 1)
```

No action handlers, no special cases, no leading-whitespace strip, no auto-pair handling. Whatever string is in the dialog's editor is what gets typed into the target editor, character by character. The user owns the indentation; the IDE doesn't get a chance to be "smart" about it.

Past iterations tried `TypedAction` (to get completion popups), `ACTION_EDITOR_START_NEW_LINE` for newlines, and a `}`-after-newline workaround. All three were removed because language plugins (Rider's C# typed handler in particular) hooked them and produced duplicate braces / extra block scaffolding that wasn't cleanly fixable from outside the language SDK. The simpler the typing logic, the more predictable the output. **Don't add typing-behavior logic back without explicit user request** — this is the third revert in a row.

The `Thread.sleep` for the post-character pause is outside the write action.

### TypeWriterDialog

UI is built with the IntelliJ Kotlin UI DSL (`panel { row { ... } }`). It is **non-modal** (`IdeModalityType.MODELESS`, `isModal = false`) — the user can keep it open while clicking around the IDE, and the active editor is resolved at "Start" time, not at action-trigger time.

The text input is an `EditorTextField` backed by a `LightVirtualFile`. Going through `LightVirtualFile` rather than a raw in-memory `Document` is what gets us PSI: completion, brace matching, formatting, indent guides, and the rest of the "real editor" experience inside the dialog. Changing the language combo creates a fresh `LightVirtualFile` of the new `FileType` and calls `setNewDocumentAndFileType` so the highlighter switches without losing the user's text.

A `ComboBox<FileType>` populated from `FileTypeManager.getInstance().registeredFileTypes` (filtered to non-binary, sorted alphabetically) drives the highlighter. Defaults: the persisted choice from a previous session, falling back to whichever file is focused in the project at action-trigger time, falling back to `PlainTextFileType.INSTANCE`.

The text is read from the field in `doOKAction()` rather than via `bindText` — `EditorTextField`'s API doesn't plug into the UI DSL's text bindings.

`doOKAction` never calls `super.doOKAction()` — instead it freezes the dialog inputs (`setUiEnabled(false)`), kicks off the typing session, and lets the `onDone` callback decide what to do when the run finishes: thaw the inputs (when `keepOpen` is true) or `close(OK_EXIT_CODE)` (when it's false). The dialog therefore stays visible for the duration of every run, which is what makes the **Stop** button reachable.

`setUiEnabled(false)` walks the `dialogPanel` tree disabling every component, then calls `editorField.setViewer(true)` so the code area becomes read-only (plain `isEnabled = false` doesn't prevent typing into an `EditorTextField`). The Start/Stop `DialogWrapperAction`s are toggled in lockstep so only one is enabled at a time.

`doCancelAction` and `dispose()` both call `scheduler.stop()` — closing the window stops any in-flight typing. `dispose()` also makes a best-effort save of the current state.

### TypeWriterSettings

Application-level `PersistentStateComponent` (`@Service(APP)`, stored in `typewriter.xml`) holding `text`, `delay`, `jitter`, `openingSequence`, `closingSequence`, `keepOpen`, and `fileTypeName`. Survives IDE restart. The dialog reads from it on construction and writes to it on OK or close.

### Module layout

```
com.github.asm0dey.typewriterplugin
├── TypeWriterAction              # Action entry; opens the (non-modal) dialog
├── TypeWriterDialog              # Modeless dialog: EditorTextField + language picker + delay/jitter/markers + "keep open"
├── TypeWriterSettings            # @Service(APP) PersistentStateComponent — survives IDE restart
├── TypewriterExecutorService     # @Service(APP) single-threaded scheduler, Disposable
├── TypeWriterBundle / TypeWriterConstants
└── commands/
    ├── Command                   # marker interface : Runnable
    ├── WriteCharCommand          # single char via document.insertString + MOVE_CARET_RIGHT
    ├── PauseCommand              # Thread.sleep
    └── ReformatCommand           # invokes the "ReformatCode" action
```

### Things to watch for when modifying

- `TypewriterExecutorService` is a single-threaded scheduler — commands run sequentially. Don't replace it with a pool unless you also redesign how `WriteCharCommand` interacts with the document.
- `WriteCharCommand` captures the target `Editor` (and a `DataContext`) in its constructor — the dialog resolves these from `FileEditorManager.selectedTextEditor` at the moment "Start" is clicked, not when the action fires. If the user focuses elsewhere mid-typing, behavior is undefined; that's an acceptable limitation given the demo use case.
- Template parsing is regex-based on the user-supplied opening/closing sequences. Always feed them through `Regex.escape` before composing the pattern (already done).
- Use the IntelliJ Platform Gradle Plugin 2.x APIs (`intellijPlatform { ... }` block), not the older `intellij { ... }` DSL. Version catalog lives in `gradle/libs.versions.toml`.
