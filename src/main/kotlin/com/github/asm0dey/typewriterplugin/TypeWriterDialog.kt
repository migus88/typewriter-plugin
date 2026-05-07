package com.github.asm0dey.typewriterplugin

import com.github.asm0dey.typewriterplugin.TypeWriterBundle.message
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.DialogWrapper.IdeModalityType
import com.intellij.openapi.ui.Messages
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.EditorTextField
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.dsl.builder.*
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.JComponent

class TypeWriterDialog(private val project: Project) :
    DialogWrapper(project, true, IdeModalityType.MODELESS) {

    private val settings = service<TypeWriterSettings>()
    private val scheduler = service<TypewriterExecutorService>()

    var text: String = settings.text
    var delay: Int = settings.delay
    var jitter: Int = settings.jitter
    var openingSequence: String = settings.openingSequence
    var closingSequence: String = settings.closingSequence
    var keepOpen: Boolean = settings.keepOpen

    private val initialFileType: FileType = restoreFileType()

    private val editorField: EditorTextField = createEditorField(initialFileType, text)

    private val languageCombo: ComboBox<FileType> = ComboBox(textFileTypes()).apply {
        renderer = SimpleListCellRenderer.create("") { it?.name.orEmpty() }
        selectedItem = initialFileType
        addActionListener {
            val selected = selectedItem as? FileType ?: return@addActionListener
            swapFileType(selected)
        }
    }

    private val stopAction: Action = object : DialogWrapperAction(message("dialog.stop")) {
        override fun doAction(e: ActionEvent) {
            scheduler.stop()
        }
    }.also { it.isEnabled = false }

    private lateinit var dialogPanel: DialogPanel

    init {
        title = message("dialog.title")
        isModal = false
        setOKButtonText(message("dialog.start"))
        setCancelButtonText(message("dialog.close"))
        init()
    }

    private fun createEditorField(fileType: FileType, text: String): EditorTextField {
        val document = createDocument(fileType, text)
        return EditorTextField(document, project, fileType, false, false).apply {
            preferredSize = Dimension(720, 360)
            addSettingsProvider { editor ->
                editor.settings.isLineNumbersShown = true
                editor.settings.isUseSoftWraps = true
                editor.settings.isFoldingOutlineShown = true
                editor.settings.isLineMarkerAreaShown = true
                editor.settings.isIndentGuidesShown = true
                editor.settings.additionalLinesCount = 0
                editor.settings.isCaretRowShown = true
            }
        }
    }

    /**
     * Backs the editor with a [LightVirtualFile] so PSI is available — that's what enables
     * code completion, brace matching, formatting and the rest of the "real editor" features
     * the user expects when authoring a snippet.
     */
    private fun createDocument(fileType: FileType, text: String): Document {
        val ext = fileType.defaultExtension.ifBlank { "txt" }
        val virtualFile = LightVirtualFile("typewriter_input.$ext", fileType, text)
        return FileDocumentManager.getInstance().getDocument(virtualFile)
            ?: EditorFactory.getInstance().createDocument(text)
    }

    private fun swapFileType(fileType: FileType) {
        val current = editorField.text
        val newDocument = createDocument(fileType, current)
        editorField.setNewDocumentAndFileType(fileType, newDocument)
    }

    override fun createCenterPanel(): JComponent {
        dialogPanel = panel {
            row(message("dialog.language")) {
                cell(languageCombo)
            }
            row {
                cell(editorField)
                    .label(message("dialog.text"), LabelPosition.TOP)
                    .focused()
                    .resizableColumn()
                    .align(Align.FILL)
            }.resizableRow()
            row {
                intTextField(IntRange(1, 2000), 4)
                    .label(message("dialog.delay"))
                    .bindIntText(::delay)
                    .gap(RightGap.SMALL)
                @Suppress("DialogTitleCapitalization")
                label(message("dialog.ms"))

                intTextField(IntRange(0, 2000), 4)
                    .label(message("dialog.jitter"))
                    .bindIntText(::jitter)
                    .gap(RightGap.SMALL)
                @Suppress("DialogTitleCapitalization")
                label(message("dialog.ms"))
            }
            row(message("dialog.template.markers")) {
                textField()
                    .columns(4)
                    .bindText(::openingSequence)
                @Suppress("DialogTitleCapitalization")
                label("…")
                textField()
                    .columns(4)
                    .bindText(::closingSequence)
            }
            row {
                checkBox(message("dialog.keep.open"))
                    .bindSelected(::keepOpen)
            }
        }
        return dialogPanel
    }

    override fun createActions(): Array<Action> = arrayOf(okAction, stopAction, cancelAction)

    override fun getPreferredFocusedComponent(): JComponent = editorField

    override fun doOKAction() {
        dialogPanel.apply()
        text = editorField.text
        persistSettings()

        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        if (editor == null) {
            Messages.showWarningDialog(project, message("dialog.no.editor"), message("dialog.title"))
            return
        }

        setUiEnabled(false)
        executeTyping(
            editor = editor,
            text = text,
            openingSequence = openingSequence,
            closingSequence = closingSequence,
            delay = delay.toLong(),
            jitter = jitter,
            scheduler = scheduler,
            onDone = ::onTypingDone,
        )
        // Don't call super.doOKAction() — we keep the dialog open during typing regardless of
        // keepOpen, so the Stop button is reachable. The onDone callback closes if needed.
    }

    private fun onTypingDone() {
        if (isDisposed) return
        if (keepOpen) {
            setUiEnabled(true)
        } else {
            close(OK_EXIT_CODE)
        }
    }

    override fun doCancelAction() {
        scheduler.stop()
        super.doCancelAction()
    }

    override fun dispose() {
        scheduler.stop()
        try {
            if (::dialogPanel.isInitialized) {
                dialogPanel.apply()
                text = editorField.text
                persistSettings()
            }
        } catch (_: Throwable) {
            // panel may already be disposed; not worth surfacing
        }
        super.dispose()
    }

    /**
     * Freeze (`enabled = false`) or thaw the dialog inputs so the user can't mutate the script
     * or its settings mid-run. The Start/Stop actions are toggled so only one of them is
     * available at a time. `EditorTextField.setViewer` switches the editor to read-only —
     * `isEnabled` alone wouldn't prevent typing into it.
     */
    private fun setUiEnabled(enabled: Boolean) {
        fun walk(c: Component) {
            c.isEnabled = enabled
            if (c is Container) for (child in c.components) walk(child)
        }
        walk(dialogPanel)
        editorField.setViewer(!enabled)
        okAction.isEnabled = enabled
        stopAction.isEnabled = !enabled
    }

    private fun persistSettings() {
        settings.text = text
        settings.delay = delay
        settings.jitter = jitter
        settings.openingSequence = openingSequence
        settings.closingSequence = closingSequence
        settings.keepOpen = keepOpen
        settings.fileTypeName = (languageCombo.selectedItem as? FileType)?.name.orEmpty()
    }

    private fun restoreFileType(): FileType {
        val saved = settings.fileTypeName
        if (saved.isNotBlank()) {
            FileTypeManager.getInstance().findFileTypeByName(saved)?.let { return it }
        }
        return detectFileType()
    }

    private fun detectFileType(): FileType {
        val active = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        return active?.fileType ?: PlainTextFileType.INSTANCE
    }

    private fun textFileTypes(): Array<FileType> =
        FileTypeManager.getInstance().registeredFileTypes
            .filter { !it.isBinary }
            .sortedBy { it.name.lowercase() }
            .toTypedArray()
}
