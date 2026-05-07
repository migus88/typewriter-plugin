package com.github.asm0dey.typewriterplugin

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

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
    name = "com.github.asm0dey.typewriterplugin.TypeWriterSettings",
    storages = [Storage("typewriter.xml")],
)
class TypeWriterSettings : PersistentStateComponent<TypeWriterSettings> {
    var tabs: MutableList<TabData> = mutableListOf()
    var activeTabIndex: Int = 0
    var delay: Int = TypeWriterConstants.defaultDelay
    var jitter: Int = TypeWriterConstants.defaultJitter
    var openingSequence: String = TypeWriterConstants.defaultOpeningSequence
    var closingSequence: String = TypeWriterConstants.defaultClosingSequence
    var keepOpen: Boolean = false

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
 * Per-tab persistence record. Plain class (not a `data class`) so IntelliJ's XmlSerializer can
 * round-trip it via reflection without needing `@JvmOverloads` on a generated constructor.
 */
class TabData {
    var name: String = "Tab"
    var text: String = ""
    var fileTypeName: String = ""
}
