# ![](src/main/resources/META-INF/pluginIcon.svg) TypeWriter plugin for JetBrains IDEs

![Build](https://github.com/asm0dey/typewriter-plugin/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/20245.svg)](https://plugins.jetbrains.com/plugin/20245)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/20245.svg)](https://plugins.jetbrains.com/plugin/20245)

<!-- Plugin description -->

This plugin implements functionality usually requested by people, who record videos of some code features.

Hit <kbd>Cmd</kbd>+<kbd>Shift</kbd>+<kbd>W</kbd> (or <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>W</kbd> on Windows/Linux) to open the TypeWriter window. Author your text in a real code editor (with completion, brace matching, and syntax highlighting for any installed language), pick a language, and click **Start typing** — the IDE will then autotype your text into whatever editor is in focus.

The dialog is non-modal, so you can leave it open on a second screen and watch the typing happen in your code editor. **Tabs** at the top let you keep multiple scripts ready at once — click **New tab** to add one, click the × on a tab to close it. Each tab has its own text and language; the rest of the settings (delay, markers, etc.) are shared.

Two run modes, controlled by the **Keep window open after starting** checkbox:
- **Off (default)**: clicking Start closes the window first, then types into the editor. Focus stays on the editor.
- **On**: the window stays open during the run, inputs freeze, and the **Stop** button becomes available. When the run finishes, the dialog stays put and focus returns to the typed-into editor.

All your tabs and settings are remembered across IDE restarts.

Special commands can be included in your text using the template syntax:
- `{_pause:1000_}` - Pauses typing for 1000 milliseconds
- `{_reformat_}` - Reformats the code at the current position

The opening/closing markers are configurable in the dialog if `{_` / `_}` collides with your content.

<!-- Plugin description end -->

## Installation

- Using IDE built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "typewriter-plugin"</kbd> >
  <kbd>Install Plugin</kbd>

- Manually:

  Download the [latest release](https://github.com/asm0dey/typewriter-plugin/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>


---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
