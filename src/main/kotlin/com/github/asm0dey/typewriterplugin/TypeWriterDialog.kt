package com.github.asm0dey.typewriterplugin

import com.github.asm0dey.typewriterplugin.TypeWriterBundle.message
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
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
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.EditorTextField
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.dsl.builder.*
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Insets
import java.awt.event.ActionEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities

class TypeWriterDialog(private val project: Project) :
    DialogWrapper(project, true, IdeModalityType.MODELESS) {

    private val settings = service<TypeWriterSettings>()
    private val scheduler = service<TypewriterExecutorService>()

    var delay: Int = settings.delay
    var jitter: Int = settings.jitter
    var openingSequence: String = settings.openingSequence
    var closingSequence: String = settings.closingSequence
    var keepOpen: Boolean = settings.keepOpen
    var completionDelay: Int = settings.completionDelay

    private val tabsState: MutableList<TabState> = mutableListOf()
    private var activeTabIndex: Int = 0
    private var targetEditor: Editor? = null
    private var suppressLanguageListener: Boolean = false

    private val tabbedPane = JBTabbedPane()
    private val languageCombo: ComboBox<FileType> = ComboBox(textFileTypes())
    private val addTabButton: JButton = JButton(message("dialog.add.tab"), AllIcons.General.Add)

    private val stopAction: Action = object : DialogWrapperAction(message("dialog.stop")) {
        override fun doAction(e: ActionEvent) {
            scheduler.stop()
        }
    }.also { it.isEnabled = false }

    private lateinit var dialogPanel: DialogPanel

    init {
        // Build initial tabs from persisted state, falling back to a single empty default.
        val saved = settings.tabs
        if (saved.isEmpty()) {
            tabsState += createNewTabState()
        } else {
            for (data in saved) tabsState += createTabStateFromData(data)
        }
        activeTabIndex = settings.activeTabIndex.coerceIn(0, tabsState.size - 1)

        for ((i, state) in tabsState.withIndex()) {
            tabbedPane.addTab(state.name, state.editorField)
            tabbedPane.setTabComponentAt(i, makeTabHeader(state))
        }
        tabbedPane.selectedIndex = activeTabIndex
        tabbedPane.addChangeListener {
            val newIndex = tabbedPane.selectedIndex
            if (newIndex >= 0) {
                activeTabIndex = newIndex
                updateLanguageCombo()
            }
        }

        languageCombo.renderer = SimpleListCellRenderer.create("") { it?.name.orEmpty() }
        languageCombo.selectedItem = tabsState[activeTabIndex].fileType
        languageCombo.addActionListener {
            if (suppressLanguageListener) return@addActionListener
            val selected = languageCombo.selectedItem as? FileType ?: return@addActionListener
            val active = tabsState[activeTabIndex]
            if (active.fileType != selected) active.swapFileType(selected)
        }

        addTabButton.isFocusable = false
        addTabButton.addActionListener { addNewTab() }

        title = message("dialog.title")
        isModal = false
        setOKButtonText(message("dialog.start"))
        setCancelButtonText(message("dialog.close"))
        init()
    }

    // ── Tab management ──────────────────────────────────────────────────────────────────────

    private fun addNewTab() {
        val state = createNewTabState()
        tabsState += state
        val newIndex = tabsState.size - 1
        tabbedPane.addTab(state.name, state.editorField)
        tabbedPane.setTabComponentAt(newIndex, makeTabHeader(state))
        tabbedPane.selectedIndex = newIndex
    }

    private fun closeTab(state: TabState) {
        if (tabsState.size <= 1) return
        val index = tabsState.indexOf(state)
        if (index < 0) return
        tabsState.removeAt(index)
        tabbedPane.removeTabAt(index)
        if (activeTabIndex >= tabsState.size) activeTabIndex = tabsState.size - 1
        tabbedPane.selectedIndex = activeTabIndex
        updateLanguageCombo()
    }

    private fun makeTabHeader(state: TabState): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        panel.isOpaque = false
        val label = JLabel(state.name).apply {
            toolTipText = message("dialog.rename.tab.tooltip")
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) {
                        e.consume()
                        beginInlineRename(panel, this@apply, state)
                    }
                }
            })
        }
        val close = JButton(AllIcons.Actions.Close).apply {
            rolloverIcon = AllIcons.Actions.CloseHovered
            isFocusable = false
            isContentAreaFilled = false
            isBorderPainted = false
            margin = Insets(0, 2, 0, 2)
            preferredSize = Dimension(16, 16)
            toolTipText = message("dialog.close.tab")
            addActionListener { closeTab(state) }
        }
        panel.add(label)
        panel.add(close)
        return panel
    }

    /**
     * Swap the tab's label out for a [JTextField] and let the user type a new name. Enter (or
     * focus loss) commits, Escape cancels. The header keeps its close button throughout.
     */
    private fun beginInlineRename(header: JPanel, label: JLabel, state: TabState) {
        val close = header.components.find { it is JButton } ?: return
        val field = JTextField(state.name).apply {
            columns = state.name.length.coerceAtLeast(8)
        }

        var finished = false
        fun finish(commit: Boolean) {
            if (finished) return
            finished = true
            if (commit) {
                val newName = field.text.trim().ifBlank { state.name }
                state.name = newName
                label.text = newName
                val idx = tabsState.indexOf(state)
                if (idx >= 0) tabbedPane.setTitleAt(idx, newName)
            }
            header.removeAll()
            header.add(label)
            header.add(close)
            header.revalidate()
            header.repaint()
        }

        field.addActionListener { finish(true) }
        field.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) = finish(true)
        })
        field.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ESCAPE) finish(false)
            }
        })

        header.removeAll()
        header.add(field)
        header.add(close)
        header.revalidate()
        header.repaint()
        field.requestFocusInWindow()
        field.selectAll()
    }

    private fun createNewTabState(): TabState {
        val ft = detectFileType()
        return TabState(nextTabName(), ft, createEditorField(ft, ""))
    }

    private fun createTabStateFromData(data: TabData): TabState {
        val ft = FileTypeManager.getInstance().findFileTypeByName(data.fileTypeName) ?: detectFileType()
        return TabState(data.name.ifBlank { "Tab" }, ft, createEditorField(ft, data.text))
    }

    private fun nextTabName(): String {
        val rx = Regex("""Tab (\d+)""")
        val highest = tabsState
            .mapNotNull { rx.matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 0
        return "Tab ${highest + 1}"
    }

    private fun updateLanguageCombo() {
        suppressLanguageListener = true
        languageCombo.selectedItem = tabsState[activeTabIndex].fileType
        suppressLanguageListener = false
    }

    // ── Editor / document factories ─────────────────────────────────────────────────────────

    private fun createEditorField(fileType: FileType, text: String): EditorTextField {
        val document = createDocument(fileType, text)
        return EditorTextField(document, project, fileType, false, false).apply {
            preferredSize = Dimension(720, 320)
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

    private fun createDocument(fileType: FileType, text: String): Document {
        val ext = fileType.defaultExtension.ifBlank { "txt" }
        val virtualFile = LightVirtualFile("typewriter_input.$ext", fileType, text)
        return FileDocumentManager.getInstance().getDocument(virtualFile)
            ?: EditorFactory.getInstance().createDocument(text)
    }

    // ── Layout ──────────────────────────────────────────────────────────────────────────────

    override fun createCenterPanel(): JComponent {
        val toolbar = JPanel(BorderLayout()).apply {
            val left = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                add(JLabel(message("dialog.language") + ":"))
                add(languageCombo)
            }
            add(left, BorderLayout.WEST)
            add(addTabButton, BorderLayout.EAST)
        }

        dialogPanel = panel {
            row {
                cell(toolbar)
                    .align(Align.FILL)
                    .resizableColumn()
            }
            row {
                cell(tabbedPane)
                    .align(Align.FILL)
                    .resizableColumn()
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
                intTextField(IntRange(0, 10000), 4)
                    .label(message("dialog.completion.delay"))
                    .bindIntText(::completionDelay)
                    .gap(RightGap.SMALL)
                @Suppress("DialogTitleCapitalization")
                label(message("dialog.ms"))
            }
            row {
                checkBox(message("dialog.keep.open"))
                    .bindSelected(::keepOpen)
            }
        }
        return dialogPanel
    }

    override fun createActions(): Array<Action> = arrayOf(okAction, stopAction, cancelAction)

    override fun getPreferredFocusedComponent(): JComponent = tabsState[activeTabIndex].editorField

    // ── OK / Stop / Cancel / dispose ────────────────────────────────────────────────────────

    override fun doOKAction() {
        dialogPanel.apply()
        persistSettings()

        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        if (editor == null) {
            Messages.showWarningDialog(project, message("dialog.no.editor"), message("dialog.title"))
            return
        }

        targetEditor = editor
        val activeText = tabsState[activeTabIndex].editorField.text
        val keepOpenSnapshot = keepOpen

        if (keepOpenSnapshot) {
            // Stay open during the run so the Stop button is reachable. Freeze inputs;
            // onTypingDone unfreezes (or closes, if the user toggled keepOpen off mid-run).
            setUiEnabled(false)
            // Push focus to the target editor so its caret blinks and is visible while typing.
            // requestFocusInWindow doesn't cross window boundaries; IdeFocusManager does.
            IdeFocusManager.getInstance(project).requestFocus(editor.contentComponent, true)
            executeTyping(
                editor = editor,
                text = activeText,
                openingSequence = openingSequence,
                closingSequence = closingSequence,
                delay = delay.toLong(),
                jitter = jitter,
                completionDelay = completionDelay.toLong(),
                scheduler = scheduler,
                onDone = ::onTypingDone,
            )
        } else {
            // Close the dialog *before* typing starts. Focus returns to the IDE editor and the
            // typing animation runs without the dialog hovering over the target.
            super.doOKAction()
            executeTyping(
                editor = editor,
                text = activeText,
                openingSequence = openingSequence,
                closingSequence = closingSequence,
                delay = delay.toLong(),
                jitter = jitter,
                completionDelay = completionDelay.toLong(),
                scheduler = scheduler,
                onDone = {},
            )
        }
    }

    private fun onTypingDone() {
        if (isDisposed) return
        if (keepOpen) {
            setUiEnabled(true)
            // Cross-window focus: keep the typed-into editor focused so the user can keep
            // working there. Plain requestFocusInWindow can't move focus to a different window.
            targetEditor?.let {
                IdeFocusManager.getInstance(project).requestFocus(it.contentComponent, true)
            }
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
                persistSettings()
            }
        } catch (_: Throwable) {
            // panel may already be disposed; not worth surfacing
        }
        super.dispose()
    }

    private fun setUiEnabled(enabled: Boolean) {
        fun walk(c: Component) {
            c.isEnabled = enabled
            if (c is Container) for (child in c.components) walk(child)
        }
        walk(dialogPanel)
        for (state in tabsState) state.editorField.setViewer(!enabled)
        okAction.isEnabled = enabled
        stopAction.isEnabled = !enabled
    }

    private fun persistSettings() {
        settings.delay = delay
        settings.jitter = jitter
        settings.openingSequence = openingSequence
        settings.closingSequence = closingSequence
        settings.keepOpen = keepOpen
        settings.completionDelay = completionDelay
        settings.activeTabIndex = activeTabIndex
        settings.tabs = tabsState.map { it.toData() }.toMutableList()
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────

    private fun detectFileType(): FileType {
        val active = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        return active?.fileType ?: PlainTextFileType.INSTANCE
    }

    private fun textFileTypes(): Array<FileType> =
        FileTypeManager.getInstance().registeredFileTypes
            .filter { !it.isBinary }
            .sortedBy { it.name.lowercase() }
            .toTypedArray()

    private inner class TabState(
        var name: String,
        var fileType: FileType,
        val editorField: EditorTextField,
    ) {
        fun swapFileType(newFileType: FileType) {
            val current = editorField.text
            val newDoc = createDocument(newFileType, current)
            editorField.setNewDocumentAndFileType(newFileType, newDoc)
            fileType = newFileType
        }

        fun toData(): TabData = TabData().also {
            it.name = name
            it.text = editorField.text
            it.fileTypeName = fileType.name
        }
    }
}
