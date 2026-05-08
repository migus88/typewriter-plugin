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

    /**
     * Toggle the IDE's auto-import-on-the-fly setting off for the duration of a typing run.
     * Default `true` — typewriter sessions are screencasts where the user wants explicit control
     * over when usings are added (via `{{import}}`), and Rider/ReSharper otherwise sneaks them
     * in as soon as an unresolved name resolves uniquely.
     */
    var suppressAutoImport: Boolean = true

    /**
     * Per-language keyword presets used by the enrichment dialog. Lazily populated — a language
     * only appears here once the user opens the enrich dialog while that language is active.
     */
    @get:XCollection(style = XCollection.Style.v2)
    var enrichmentPresets: MutableList<EnrichmentPreset> = mutableListOf()

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
