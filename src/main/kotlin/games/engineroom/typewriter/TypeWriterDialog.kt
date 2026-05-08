package games.engineroom.typewriter

import games.engineroom.typewriter.TypeWriterBundle.message
import com.intellij.icons.AllIcons
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
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
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
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
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

private val ENRICH_LOG = logger<TypeWriterDialog>()

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
    private val enrichButton: JButton = JButton(message("dialog.enrich")).apply {
        toolTipText = message("dialog.enrich.tooltip")
        addActionListener { openEnrichDialog() }
    }
    private val unenrichButton: JButton = JButton(message("dialog.unenrich")).apply {
        toolTipText = message("dialog.unenrich.tooltip")
        addActionListener { unenrichActiveTab() }
    }

    private val templateList: JBList<TemplateEntry> = JBList(TemplateKind.entries.map { TemplateEntry(it) }).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = makeTemplateRenderer()
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    selectedValue?.let { insertTemplate(it) }
                }
            }
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    selectedValue?.let { insertTemplate(it) }
                }
            }
        })
    }

    private val stopAction: Action = object : DialogWrapperAction(message("dialog.stop")) {
        override fun doAction(e: ActionEvent) {
            scheduler.stop()
        }
    }.also { it.isEnabled = false }

    private lateinit var dialogPanel: DialogPanel
    private lateinit var openingField: JTextField
    private lateinit var closingField: JTextField

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

        // The kotlin UI DSL's `bindText` only flushes to the bound property on `apply()` —
        // typing in the markers fields won't update `openingSequence` / `closingSequence` until
        // OK. The templates list and the insert action both need *live* values, so we hook a
        // DocumentListener that mirrors the field text into the property and forces the list
        // to repaint with each keystroke.
        val markerListener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = sync()
            override fun removeUpdate(e: DocumentEvent) = sync()
            override fun changedUpdate(e: DocumentEvent) = sync()
            private fun sync() {
                openingSequence = openingField.text
                closingSequence = closingField.text
                templateList.repaint()
            }
        }
        openingField.document.addDocumentListener(markerListener)
        closingField.document.addDocumentListener(markerListener)
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

        // JTabbedPane only sees clicks that land directly on itself — clicks on a custom tab
        // header's children go to the children. Without this listener, single-clicking the tab
        // *name* doesn't switch tabs because the JLabel intercepts the click.
        val selectListener = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 1 && SwingUtilities.isLeftMouseButton(e)) {
                    val idx = tabsState.indexOf(state)
                    if (idx >= 0) tabbedPane.selectedIndex = idx
                }
            }
        }
        panel.addMouseListener(selectListener)

        val label = JLabel(state.name).apply {
            toolTipText = message("dialog.rename.tab.tooltip")
            addMouseListener(selectListener)
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
                // EditorTextField hides scrollbars by default — long scripts then push the dialog
                // taller instead of scrolling. Re-enable so the editor scrolls within its bounds.
                editor.setVerticalScrollbarVisible(true)
                editor.setHorizontalScrollbarVisible(true)
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
                add(enrichButton)
                add(unenrichButton)
            }
            add(left, BorderLayout.WEST)
            add(addTabButton, BorderLayout.EAST)
        }

        dialogPanel = panel {
            row {
                label(message("dialog.templates.title"))
            }
            row {
                cell(JBScrollPane(templateList).apply {
                    preferredSize = Dimension(0, 96)
                })
                    .align(Align.FILL)
                    .resizableColumn()
            }
            row {
                cell(toolbar)
                    .align(Align.FILL)
                    .resizableColumn()
            }.topGap(TopGap.MEDIUM)
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
                openingField = textField()
                    .columns(4)
                    .bindText(::openingSequence)
                    .component
                @Suppress("DialogTitleCapitalization")
                label("…")
                closingField = textField()
                    .columns(4)
                    .bindText(::closingSequence)
                    .component
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

    /**
     * Renders each template entry on a single row: monospaced syntax on the left, dimmed
     * description on the right. The syntax string is rebuilt on each paint using the *current*
     * `openingSequence` / `closingSequence`, so if the user changes the markers the list reflects
     * it without explicit refreshing.
     */
    private fun makeTemplateRenderer(): ListCellRenderer<in TemplateEntry> =
        ListCellRenderer { list, value, _, selected, _ ->
            val panel = JPanel(BorderLayout()).apply {
                isOpaque = true
                background = if (selected) list.selectionBackground else list.background
                border = JBUI.Borders.empty(4, 8)
            }
            val syntax = JLabel(value.render(openingSequence, closingSequence)).apply {
                font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
                foreground = if (selected) list.selectionForeground else list.foreground
            }
            val desc = JLabel(message(value.kind.descriptionKey)).apply {
                foreground = if (selected) list.selectionForeground else UIUtil.getInactiveTextColor()
                border = JBUI.Borders.emptyLeft(12)
            }
            panel.add(syntax, BorderLayout.WEST)
            panel.add(desc, BorderLayout.CENTER)
            panel
        }

    /**
     * Insert the template at the active tab's caret using the current marker settings, then
     * focus the editor field so the user can keep typing.
     */
    /**
     * Open the enrichment configuration popup for the active tab's language. The dialog mutates
     * the persisted [EnrichmentPreset] in place; on OK we read its current state and apply
     * [enrichText] to the active tab's content via a [WriteCommandAction] so undo works.
     */
    private fun openEnrichDialog() {
        try {
            // Live values: the user may have edited the marker fields without applying.
            openingSequence = openingField.text
            closingSequence = closingField.text

            val state = tabsState[activeTabIndex]
            val preset = resolveEnrichmentPreset(settings, state.fileType.name)
            val mode = runCatching { EnrichmentMode.valueOf(settings.enrichmentMode) }
                .getOrDefault(EnrichmentMode.HEAVY)

            val dialog = EnrichDialog(
                project = project,
                languageDisplayName = preset.language.ifBlank { state.fileType.name },
                preset = preset,
                initialMode = mode,
            )
            if (!dialog.showAndGet()) return

            val chosenMode = dialog.selectedMode
            settings.enrichmentMode = chosenMode.name

            val current = state.editorField.text
            val transformed = enrichText(
                text = current,
                keywords = preset.keywords,
                mode = chosenMode,
                openingSequence = openingSequence,
                closingSequence = closingSequence,
            )
            if (transformed == current) return
            replaceTabText(state, transformed)
        } catch (t: Throwable) {
            ENRICH_LOG.error("Enrich dialog failed", t)
            Messages.showErrorDialog(
                project,
                "Enrich failed: ${t.javaClass.simpleName}: ${t.message}",
                message("dialog.title"),
            )
        }
    }

    private fun unenrichActiveTab() {
        try {
            openingSequence = openingField.text
            closingSequence = closingField.text

            val state = tabsState[activeTabIndex]
            val current = state.editorField.text
            val transformed = unenrichText(
                text = current,
                openingSequence = openingSequence,
                closingSequence = closingSequence,
            )
            if (transformed == current) return
            replaceTabText(state, transformed)
        } catch (t: Throwable) {
            ENRICH_LOG.error("Unenrich failed", t)
            Messages.showErrorDialog(
                project,
                "Unenrich failed: ${t.javaClass.simpleName}: ${t.message}",
                message("dialog.title"),
            )
        }
    }

    /**
     * Replace the entire tab document via a write action so the change is undoable from the
     * dialog's editor. After replacement, restore focus + caret position so the user can keep
     * editing in place.
     */
    private fun replaceTabText(state: TabState, newText: String) {
        val doc = state.editorField.document
        val ed = state.editorField.editor
        val originalCaret = ed?.caretModel?.primaryCaret?.offset ?: 0
        WriteCommandAction.runWriteCommandAction(project) {
            doc.replaceString(0, doc.textLength, newText)
        }
        ed?.caretModel?.primaryCaret?.moveToOffset(originalCaret.coerceIn(0, doc.textLength))
        state.editorField.requestFocusInWindow()
    }

    private fun insertTemplate(entry: TemplateEntry) {
        val state = tabsState[activeTabIndex]
        val syntax = entry.render(openingSequence, closingSequence)
        val ed = state.editorField.editor
        WriteCommandAction.runWriteCommandAction(project) {
            val doc = state.editorField.document
            val offset = ed?.caretModel?.primaryCaret?.offset ?: doc.textLength
            doc.insertString(offset, syntax)
            ed?.caretModel?.primaryCaret?.moveToOffset(offset + syntax.length)
        }
        state.editorField.requestFocusInWindow()
    }

    private enum class TemplateKind(val pattern: String, val descriptionKey: String) {
        PAUSE("{O}pause:1000{C}", "template.pause.description"),
        REFORMAT("{O}reformat{C}", "template.reformat.description"),
        COMPLETE("{O}complete:3:Word{C}", "template.complete.description"),
        IMPORT_AUTO("{O}import:300{C}", "template.import.auto.description"),
        IMPORT_NS("{O}import:300:Namespace{C}", "template.import.ns.description"),
        IMPORT_OPTION("{O}import:300::2{C}", "template.import.option.description"),
        CARET("{O}caret:up:3{C}", "template.caret.description"),
    }

    private class TemplateEntry(val kind: TemplateKind) {
        fun render(open: String, close: String): String =
            kind.pattern.replace("{O}", open).replace("{C}", close)
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

        // Always suppress IDE auto-import for the duration of the run so the user's `{{import}}`
        // template stays in control. The handle's `restore()` is idempotent, and the scheduler's
        // onDone fires on both natural completion and cancellation, so the user's settings can't
        // be left in the modified state under normal circumstances.
        val importSuppressor = AutoImports.suppress()

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
                onDone = {
                    importSuppressor.restore()
                    onTypingDone()
                },
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
                onDone = {
                    importSuppressor.restore()
                },
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
