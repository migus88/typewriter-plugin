package games.engineroom.typewriter

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection

/**
 * Application-level settings store for the TypeWriter dialog. Survives IDE restart so users don't
 * lose the scripts they were authoring.
 *
 * The schema went through a one-time migration: pre-tab installs had a single [text] +
 * [fileTypeName] pair on the root, post-tab installs use [tabs] with per-tab text/language. The
 * legacy fields stay on the class so old XML loads cleanly and [loadState] folds them into a
 * single seed tab.
 */
@Service(Service.Level.APP)
@State(
    name = "games.engineroom.typewriter.TypeWriterSettings",
    storages = [Storage("typewriter.xml")],
)
class TypeWriterSettings : PersistentStateComponent<TypeWriterSettings> {

    /**
     * `@XCollection(style = v2)` is required — without it IntelliJ's `XmlSerializer` doesn't
     * reliably round-trip a `MutableList` of custom-class items, and tabs silently revert to
     * their defaults on the next session.
     */
    @get:XCollection(style = XCollection.Style.v2)
    var tabs: MutableList<TabData> = mutableListOf()

    var activeTabIndex: Int = 0
    var delay: Int = TypeWriterConstants.defaultDelay
    var jitter: Int = TypeWriterConstants.defaultJitter
    var openingSequence: String = TypeWriterConstants.defaultOpeningSequence
    var closingSequence: String = TypeWriterConstants.defaultClosingSequence
    var keepOpen: Boolean = false
    var completionDelay: Int = TypeWriterConstants.defaultCompletionDelay
    var preExecutionPause: Int = TypeWriterConstants.defaultPreExecutionPause
    var macroColor: Int = TypeWriterConstants.defaultMacroColor
    var macroArgColor: Int = TypeWriterConstants.defaultMacroArgColor

    /**
     * When true, the macro list cells render syntax only and surface the description through a
     * hover tooltip instead of inline text. Default is false — descriptions show under the
     * syntax in every cell, the way they have since the descriptions feature shipped.
     */
    var hideMacroDescriptions: Boolean = false

    /**
     * Per-language keyword presets used by the enrichment dialog. Lazily populated — a language
     * only appears here once the user opens the enrich dialog while that language is active.
     */
    @get:XCollection(style = XCollection.Style.v2)
    var enrichmentPresets: MutableList<EnrichmentPreset> = mutableListOf()

    /**
     * User-defined macros. Each entry maps a name (used as `{{name}}` in scripts) to literal
     * content that gets substituted in before the rest of the typing pipeline runs. Substitution
     * is recursive so a custom macro can reference other custom or built-in macros.
     */
    @get:XCollection(style = XCollection.Style.v2)
    var customMacros: MutableList<CustomMacroData> = mutableListOf()

    /** Last-selected enrichment mode, persisted so the dialog re-opens on the same setting. */
    var enrichmentMode: String = EnrichmentMode.HEAVY.name

    // Legacy single-text fields. Migrated into a tab on first load if present.
    var text: String = ""
    var fileTypeName: String = ""

    override fun getState(): TypeWriterSettings = this

    override fun loadState(state: TypeWriterSettings) {
        XmlSerializerUtil.copyBean(state, this)
        if (tabs.isEmpty() && (text.isNotEmpty() || fileTypeName.isNotEmpty())) {
            tabs.add(TabData().also {
                it.name = "Tab 1"
                it.text = text
                it.fileTypeName = fileTypeName
            })
            text = ""
            fileTypeName = ""
        }
    }
}

/**
 * Per-tab persistence record. `@Tag` gives the element a stable name; `@Attribute` keeps the
 * short fields on the element instead of nested `<option>` blocks. The script body uses the
 * default nested-tag form so multi-line content survives intact.
 */
@Tag("tab")
class TabData {
    @get:Attribute("name")
    var name: String = "Tab"

    @get:Attribute("fileType")
    var fileTypeName: String = ""

    var text: String = ""
}

/**
 * Persistence record for one user-defined macro.
 *
 * - [name] is the identifier typed inside the markers (e.g. "prop" → `{{prop}}` or
 *   `{{prop:Foo:int}}` when the macro takes parameters).
 * - [parameters] is the ordered list of positional parameter names. Each name may be referenced
 *   inside [content] as `$name$` (live-template style) and gets substituted at call time.
 * - [content] is the literal expansion. May contain other macros (built-in or custom) — they're
 *   re-processed by the typing pipeline after expansion.
 */
@Tag("customMacro")
class CustomMacroData {
    @get:Attribute("name")
    var name: String = ""

    @get:XCollection(style = XCollection.Style.v2, elementName = "param")
    var parameters: MutableList<String> = mutableListOf()

    var content: String = ""

    /**
     * Short description shown under the macro syntax in the dialog's macro list. Optional —
     * macros without one fall back to a generic "Custom user-defined macro" tooltip. Plain text;
     * the renderer escapes any HTML-special characters before display.
     */
    @get:Attribute("description")
    var description: String = ""

    /**
     * Optional [com.intellij.openapi.fileTypes.FileType.getName] (e.g. "C#", "Kotlin"). Empty
     * means the macro applies to every language and shows up in every tab's macro list and
     * completion popup. When non-empty, the macro is only surfaced when the active tab's file
     * type matches.
     */
    @get:Attribute("fileType")
    var fileTypeName: String = ""
}
