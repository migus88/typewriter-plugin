package com.github.asm0dey.typewriterplugin

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Application-level settings store for the TypeWriter dialog. Survives IDE restart so users don't
 * lose the script they were authoring.
 */
@Service(Service.Level.APP)
@State(
    name = "com.github.asm0dey.typewriterplugin.TypeWriterSettings",
    storages = [Storage("typewriter.xml")],
)
class TypeWriterSettings : PersistentStateComponent<TypeWriterSettings> {
    var text: String = ""
    var delay: Int = TypeWriterConstants.defaultDelay
    var jitter: Int = TypeWriterConstants.defaultJitter
    var openingSequence: String = TypeWriterConstants.defaultOpeningSequence
    var closingSequence: String = TypeWriterConstants.defaultClosingSequence
    var keepOpen: Boolean = false
    var fileTypeName: String = ""

    override fun getState(): TypeWriterSettings = this

    override fun loadState(state: TypeWriterSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }
}
