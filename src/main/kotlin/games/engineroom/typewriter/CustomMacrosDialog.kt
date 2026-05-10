package games.engineroom.typewriter

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.DialogWrapper.IdeModalityType
import com.intellij.openapi.ui.Messages
import com.intellij.testFramework.LightVirtualFile
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.ui.EditorTextField
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import games.engineroom.typewriter.TypeWriterBundle.message
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Point
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Modal popup for managing user-defined macros. Each entry has a name (typed inside the
 * configured markers, e.g. `{{prop}}`) and a content body — anything that can appear in a tab's
 * editor. Edits live in a working list copy; the changes commit to [settings] only on OK.
 *
 * Layout: list of macros on the left, name + content editor on the right. Buttons under the list
 * add or delete entries. Validation runs on OK — invalid names (empty, whitespace, `:`, built-in
 * collision, duplicates) keep the dialog open with a focused error.
 *
 * Each macro carries an optional [language][CustomMacroData.fileTypeName]. The right pane's
 * language combo binds to it; the content editor swaps its underlying [FileType] to match so the
 * editor's syntax highlighting and completion mirror what the macro will see when it expands. A
 * macro with the combo set to "All languages" applies everywhere; otherwise it only shows up in
 * tabs whose file type matches.
 */
class CustomMacrosDialog(
    private val project: Project,
    private val parentComponent: Component,
    private val settings: TypeWriterSettings,
    private val openingSequence: String,
    private val closingSequence: String,
) : DialogWrapper(project, parentComponent, true, IdeModalityType.IDE) {

    private data class Working(
        var name: String,
        var parameters: MutableList<String>,
        var content: String,
        var description: String,
        /** Empty string = "All languages"; otherwise a [FileType.getName]. */
        var fileTypeName: String,
    )

    private val working: MutableList<Working> = settings.customMacros
        .map { Working(it.name, it.parameters.toMutableList(), it.content, it.description, it.fileTypeName) }
        .toMutableList()

    private val listModel: DefaultListModel<Working> = DefaultListModel<Working>().apply {
        for (w in working) addElement(w)
    }

    private val macroList: JBList<Working> = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = makeRenderer()
    }

    private val nameField: JBTextField = JBTextField().apply {
        emptyText.text = message("custom.macros.name.placeholder")
    }
    private val paramsField: JBTextField = JBTextField().apply {
        emptyText.text = message("custom.macros.parameters.placeholder")
        toolTipText = message("custom.macros.parameters.tooltip")
    }
    private val descriptionField: JBTextField = JBTextField().apply {
        emptyText.text = message("custom.macros.description.placeholder")
        toolTipText = message("custom.macros.description.tooltip")
    }

    /**
     * Combo of `null` (= "All languages") + every non-binary registered [FileType], sorted by
     * name. Driving a `ComboBox<FileType?>` directly is awkward at the call sites because the
     * combo's items are declared `FileType` non-null in IntelliJ's API, but Swing's underlying
     * `JComboBox` happily accepts nulls — using `Array<FileType?>` works at runtime and keeps the
     * "all languages" sentinel inside the same control as the named file types.
     */
    private val languageCombo: ComboBox<FileType?> = ComboBox(languageItems()).apply {
        renderer = SimpleListCellRenderer.create(message("custom.macros.language.all")) { it?.name.orEmpty() }
        toolTipText = message("custom.macros.language.tooltip")
    }
    private val contentField: EditorTextField = createContentEditor()

    /** Tracks the FileType currently set on [contentField] so swaps can no-op when unchanged. */
    private var currentEditorFileType: FileType = PlainTextFileType.INSTANCE

    private val addButton: JButton = JButton(AllIcons.General.Add).apply {
        toolTipText = message("custom.macros.add.tooltip")
        addActionListener { addEntry() }
    }
    private val deleteButton: JButton = JButton(AllIcons.General.Remove).apply {
        toolTipText = message("custom.macros.delete.tooltip")
        addActionListener { deleteSelected() }
    }

    /** Set while we're loading the right pane from a list selection — avoids feedback loops. */
    private var suppressFieldListeners: Boolean = false

    init {
        title = message("custom.macros.dialog.title")
        isModal = true

        macroList.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) loadSelectedIntoFields()
        }
        nameField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = pushNameToModel()
            override fun removeUpdate(e: DocumentEvent) = pushNameToModel()
            override fun changedUpdate(e: DocumentEvent) = pushNameToModel()
        })
        paramsField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = pushParamsToModel()
            override fun removeUpdate(e: DocumentEvent) = pushParamsToModel()
            override fun changedUpdate(e: DocumentEvent) = pushParamsToModel()
        })
        descriptionField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = pushDescriptionToModel()
            override fun removeUpdate(e: DocumentEvent) = pushDescriptionToModel()
            override fun changedUpdate(e: DocumentEvent) = pushDescriptionToModel()
        })
        languageCombo.addActionListener {
            if (suppressFieldListeners) return@addActionListener
            pushLanguageToModel()
        }
        // The document listener is installed inside [createContentEditor]'s `addSettingsProvider`
        // block — that callback fires for every new editor [contentField] creates, so swapping
        // the underlying file type re-attaches the listener automatically.

        if (working.isNotEmpty()) macroList.selectedIndex = 0 else clearFields()
        updateFieldsEnabled()

        init()
    }

    private fun createContentEditor(): EditorTextField {
        val virtualFile = LightVirtualFile("custom_macro.txt", PlainTextFileType.INSTANCE, "")
        // Same gate the dialog editors use — without it, TypewriterCompletionContributor bails
        // out before suggesting macros / words inside the macro body.
        virtualFile.putUserData(TYPEWRITER_DIALOG_FILE_KEY, true)
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)
            ?: EditorFactory.getInstance().createDocument("")
        return EditorTextField(document, project, PlainTextFileType.INSTANCE, false, false).apply {
            preferredSize = JBUI.size(420, 280)
            addSettingsProvider { editor ->
                editor.settings.isLineNumbersShown = true
                editor.settings.isUseSoftWraps = true
                editor.settings.additionalLinesCount = 0
                editor.settings.isCaretRowShown = true
                editor.setVerticalScrollbarVisible(true)
                installTypewriterAutoPopupTrigger(editor, project, disposable) { openingSequence }
                MacroHighlighter(
                    editor,
                    disposable,
                    { openingSequence },
                    { closingSequence },
                    { Color(settings.macroColor) },
                    { Color(settings.macroArgColor) },
                )
                // Listener fires from inside the settings-provider so it survives FileType swaps
                // (each swap creates a fresh editor, which re-runs the providers).
                editor.document.addDocumentListener(
                    object : com.intellij.openapi.editor.event.DocumentListener {
                        override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                            pushContentToModel()
                        }
                    },
                    disposable,
                )
            }
        }
    }

    /**
     * Re-back [contentField] with a [LightVirtualFile] of [fileType], preserving the current text.
     * The new virtual file is tagged with [TYPEWRITER_DIALOG_FILE_KEY] so the completion
     * contributor still recognises it. Settings providers re-fire on the new editor, so the macro
     * highlighter and auto-popup trigger are re-attached automatically.
     */
    private fun swapEditorFileType(fileType: FileType) {
        if (fileType == currentEditorFileType) return
        val text = contentField.text
        val ext = fileType.defaultExtension.ifBlank { "txt" }
        val virtualFile = LightVirtualFile("custom_macro.$ext", fileType, text)
        virtualFile.putUserData(TYPEWRITER_DIALOG_FILE_KEY, true)
        val newDoc = FileDocumentManager.getInstance().getDocument(virtualFile)
            ?: EditorFactory.getInstance().createDocument(text)
        contentField.setNewDocumentAndFileType(fileType, newDoc)
        currentEditorFileType = fileType
    }

    /**
     * Resolve a stored fileTypeName to the matching [FileType], falling back to plain text when
     * the name is empty or no longer registered.
     */
    private fun resolveFileType(name: String): FileType {
        if (name.isBlank()) return PlainTextFileType.INSTANCE
        return FileTypeManager.getInstance().findFileTypeByName(name) ?: PlainTextFileType.INSTANCE
    }

    override fun createCenterPanel(): JComponent {
        val listScroll = JBScrollPane(
            macroList,
            javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED,
        ).apply {
            border = JBUI.Borders.customLine(com.intellij.ui.JBColor.border(), 1)
            preferredSize = JBUI.size(220, 320)
        }
        val listButtons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            val sq = JBUI.size(28, 28)
            addButton.preferredSize = sq
            deleteButton.preferredSize = sq
            add(addButton)
            add(deleteButton)
        }
        val leftPane = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            add(listScroll, BorderLayout.CENTER)
            add(listButtons, BorderLayout.SOUTH)
            preferredSize = JBUI.size(240, 360)
        }

        // Two-column GridBag-ish layout via BoxLayout: stacked rows, each row is a labelled
        // field. Keeps the form compact and aligns the labels' baselines with the inputs.
        val labelWidth = JLabel(message("custom.macros.description.label")).preferredSize.width + JBUI.scale(8)
        fun labelled(text: String, field: JComponent): JPanel = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            val label = JLabel(text)
            label.preferredSize = Dimension(labelWidth, label.preferredSize.height)
            add(label, BorderLayout.WEST)
            add(field, BorderLayout.CENTER)
        }
        val nameRow = labelled(message("custom.macros.name.label"), nameField)
        val paramsRow = labelled(message("custom.macros.parameters.label"), paramsField)
        val descriptionRow = labelled(message("custom.macros.description.label"), descriptionField)
        val languageRow = labelled(message("custom.macros.language.label"), languageCombo)

        val formRows = JPanel().apply {
            isOpaque = false
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            add(nameRow)
            add(javax.swing.Box.createVerticalStrut(JBUI.scale(4)))
            add(paramsRow)
            add(javax.swing.Box.createVerticalStrut(JBUI.scale(4)))
            add(descriptionRow)
            add(javax.swing.Box.createVerticalStrut(JBUI.scale(4)))
            add(languageRow)
        }
        val rightHeader = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            add(formRows, BorderLayout.NORTH)
            add(JLabel(message("custom.macros.content.label")), BorderLayout.SOUTH)
        }
        val rightPane = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            add(rightHeader, BorderLayout.NORTH)
            add(contentField, BorderLayout.CENTER)
            preferredSize = JBUI.size(440, 360)
        }

        return OnePixelSplitter(false, 0.32f).apply {
            firstComponent = leftPane
            secondComponent = rightPane
            setHonorComponentsMinimumSize(true)
            preferredSize = JBUI.size(720, 380)
        }
    }

    override fun getPreferredFocusedComponent(): JComponent =
        if (working.isEmpty()) addButton else nameField

    /**
     * Render an entry as `{{name}}` (or `{{name:Param1:Param2}}` when parameters are defined),
     * so the list reflects exactly the call shape that gets typed into a script.
     */
    private fun makeRenderer(): ListCellRenderer<in Working> = ListCellRenderer { list, value, _, selected, _ ->
        JPanel(BorderLayout()).apply {
            isOpaque = true
            background = if (selected) list.selectionBackground else list.background
            border = JBUI.Borders.empty(4, 8)
            val displayName = value.name.ifEmpty { message("custom.macros.unnamed") }
            val paramsTail = if (value.parameters.isEmpty()) "" else ":${value.parameters.joinToString(":")}"
            val rendered = "$openingSequence$displayName$paramsTail$closingSequence"
            val label = JLabel(rendered).apply {
                font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
                foreground = when {
                    selected -> list.selectionForeground
                    value.name.isEmpty() -> com.intellij.util.ui.UIUtil.getInactiveTextColor()
                    else -> list.foreground
                }
            }
            add(label, BorderLayout.WEST)
        }
    }

    private fun loadSelectedIntoFields() {
        val idx = macroList.selectedIndex
        suppressFieldListeners = true
        try {
            if (idx < 0 || idx >= working.size) {
                clearFields()
            } else {
                val w = working[idx]
                nameField.text = w.name
                paramsField.text = w.parameters.joinToString(", ")
                descriptionField.text = w.description
                val targetFt = resolveFileType(w.fileTypeName)
                // Selecting the combo item by FileType identity — `selectedItem = targetFt` works
                // because `languageItems()` includes the same registered instances FileTypeManager
                // returns; null falls through to the "All languages" sentinel.
                languageCombo.selectedItem = if (w.fileTypeName.isBlank()) null else targetFt
                swapEditorFileType(targetFt)
                contentField.text = w.content
            }
        } finally {
            suppressFieldListeners = false
        }
        updateFieldsEnabled()
    }

    private fun clearFields() {
        nameField.text = ""
        paramsField.text = ""
        descriptionField.text = ""
        languageCombo.selectedItem = null
        swapEditorFileType(PlainTextFileType.INSTANCE)
        contentField.text = ""
    }

    private fun updateFieldsEnabled() {
        val hasSelection = macroList.selectedIndex in 0 until working.size
        nameField.isEnabled = hasSelection
        paramsField.isEnabled = hasSelection
        descriptionField.isEnabled = hasSelection
        languageCombo.isEnabled = hasSelection
        contentField.isEnabled = hasSelection
        deleteButton.isEnabled = hasSelection
    }

    private fun pushNameToModel() {
        if (suppressFieldListeners) return
        val idx = macroList.selectedIndex
        if (idx < 0 || idx >= working.size) return
        working[idx].name = nameField.text
        listModel.set(idx, working[idx])
    }

    private fun pushParamsToModel() {
        if (suppressFieldListeners) return
        val idx = macroList.selectedIndex
        if (idx < 0 || idx >= working.size) return
        working[idx].parameters = parseParameters(paramsField.text)
        listModel.set(idx, working[idx])
    }

    private fun pushDescriptionToModel() {
        if (suppressFieldListeners) return
        val idx = macroList.selectedIndex
        if (idx < 0 || idx >= working.size) return
        working[idx].description = descriptionField.text
    }

    private fun pushLanguageToModel() {
        val idx = macroList.selectedIndex
        if (idx < 0 || idx >= working.size) return
        val selected = languageCombo.selectedItem as? FileType
        working[idx].fileTypeName = selected?.name.orEmpty()
        swapEditorFileType(selected ?: PlainTextFileType.INSTANCE)
    }

    private fun pushContentToModel() {
        if (suppressFieldListeners) return
        val idx = macroList.selectedIndex
        if (idx < 0 || idx >= working.size) return
        working[idx].content = contentField.text
    }

    private fun addEntry() {
        val newEntry = Working(suggestNewName(), mutableListOf(), "", "", "")
        working += newEntry
        listModel.addElement(newEntry)
        val newIdx = working.size - 1
        macroList.selectedIndex = newIdx
        macroList.ensureIndexIsVisible(newIdx)
        SwingUtilities.invokeLater { nameField.requestFocusInWindow() }
    }

    /** Split the params field on commas (or whitespace), trim, drop empties. */
    private fun parseParameters(raw: String): MutableList<String> =
        raw.split(',', ' ', '\t').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()

    private fun deleteSelected() {
        val idx = macroList.selectedIndex
        if (idx < 0 || idx >= working.size) return
        working.removeAt(idx)
        listModel.remove(idx)
        if (working.isNotEmpty()) {
            val nextIdx = idx.coerceAtMost(working.size - 1)
            macroList.selectedIndex = nextIdx
        } else {
            clearFields()
            updateFieldsEnabled()
        }
    }

    private fun suggestNewName(): String {
        val taken = working.map { it.name }.toSet()
        var n = 1
        while ("macro$n" in taken) n++
        return "macro$n"
    }

    override fun doOKAction() {
        // Trim each name in place so trailing-space typos don't persist; the trim'd value is
        // also what gets validated below.
        for (w in working) w.name = w.name.trim()

        val invalid = working.firstOrNull { !isValidName(it.name) }
        if (invalid != null) {
            macroList.selectedIndex = working.indexOf(invalid)
            Messages.showErrorDialog(
                project,
                message("custom.macros.invalid.name", invalid.name.ifEmpty { "(empty)" }),
                message("custom.macros.dialog.title"),
            )
            return
        }
        val seen = mutableSetOf<String>()
        val dup = working.firstOrNull { !seen.add(it.name) }
        if (dup != null) {
            macroList.selectedIndex = working.indexOf(dup)
            Messages.showErrorDialog(
                project,
                message("custom.macros.duplicate", dup.name),
                message("custom.macros.dialog.title"),
            )
            return
        }

        for (w in working) {
            val badParam = w.parameters.firstOrNull { !isValidParamName(it) }
            if (badParam != null) {
                macroList.selectedIndex = working.indexOf(w)
                Messages.showErrorDialog(
                    project,
                    message("custom.macros.invalid.parameter", badParam, w.name),
                    message("custom.macros.dialog.title"),
                )
                return
            }
            val seenParams = mutableSetOf<String>()
            val dupParam = w.parameters.firstOrNull { !seenParams.add(it) }
            if (dupParam != null) {
                macroList.selectedIndex = working.indexOf(w)
                Messages.showErrorDialog(
                    project,
                    message("custom.macros.duplicate.parameter", dupParam, w.name),
                    message("custom.macros.dialog.title"),
                )
                return
            }
        }

        settings.customMacros = working
            .map { w ->
                CustomMacroData().apply {
                    name = w.name
                    parameters = w.parameters.toMutableList()
                    content = w.content
                    description = w.description.trim()
                    fileTypeName = w.fileTypeName
                }
            }
            .toMutableList()
        super.doOKAction()
    }

    /**
     * `(project, parentComponent, …)` already wires window ownership, but the project-aware path
     * still centres the dialog on the IDE frame. Compute the centre relative to the typewriter
     * window so the popup lands on top of it (mirrors [SettingsDialog]).
     */
    override fun getInitialLocation(): Point? {
        val parentWindow = SwingUtilities.getWindowAncestor(parentComponent) ?: return null
        val pref = preferredSize ?: return null
        val x = parentWindow.x + (parentWindow.width - pref.width) / 2
        val y = parentWindow.y + (parentWindow.height - pref.height) / 2
        return Point(x.coerceAtLeast(0), y.coerceAtLeast(0))
    }

    private fun isValidName(name: String): Boolean {
        if (name.isEmpty()) return false
        if (name.any { it.isWhitespace() }) return false
        if (':' in name) return false
        if (name.contains(openingSequence) || name.contains(closingSequence)) return false
        if (name in BUILT_IN_MACRO_NAMES) return false
        return true
    }

    /**
     * Parameter names get embedded into a regex (`$name$`) at expansion time, so they need to be
     * a plain identifier — letters, digits, underscore, and not starting with a digit. The regex
     * `\w+` matches them, so any name we accept here will round-trip through [substituteMacroParams].
     */
    private fun isValidParamName(name: String): Boolean {
        if (name.isEmpty()) return false
        if (!name[0].isLetter() && name[0] != '_') return false
        return name.all { it.isLetterOrDigit() || it == '_' }
    }

    /**
     * Combo items: a leading `null` (= "All languages") followed by every non-binary registered
     * file type, sorted by name. Same source as the main dialog's language combo, just with the
     * extra null sentinel up front.
     */
    private fun languageItems(): Array<FileType?> {
        val types = FileTypeManager.getInstance().registeredFileTypes
            .filter { !it.isBinary }
            .sortedBy { it.name.lowercase() }
        return arrayOf<FileType?>(null) + types.toTypedArray()
    }
}
