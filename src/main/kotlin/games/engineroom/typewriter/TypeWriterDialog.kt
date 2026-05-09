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
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleListCellRenderer
import java.awt.Color
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Insets
import java.awt.LayoutManager
import java.awt.event.ActionEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

private val ENRICH_LOG = logger<TypeWriterDialog>()

class TypeWriterDialog(private val project: Project) :
    DialogWrapper(project, true, IdeModalityType.MODELESS) {

    private val settings = service<TypeWriterSettings>()
    private val scheduler = service<TypewriterExecutorService>()

    private var keepOpen: Boolean = settings.keepOpen
    private var openingSequence: String = settings.openingSequence
    private var closingSequence: String = settings.closingSequence

    private val tabsState: MutableList<TabState> = mutableListOf()
    private var activeTabIndex: Int = 0
    private var targetEditor: Editor? = null
    private var suppressLanguageListener: Boolean = false

    private val tabbedPane = JBTabbedPane()
    private val languageCombo: ComboBox<FileType> = ComboBox(textFileTypes())
    private val plusButton: JButton = JButton(AllIcons.General.Add).apply {
        toolTipText = message("dialog.add.tab.tooltip")
        addActionListener { addNewTab() }
    }
    private val settingsButton: JButton = JButton(AllIcons.General.Settings).apply {
        toolTipText = message("dialog.settings.tooltip")
        addActionListener { openSettingsDialog() }
    }
    private val enrichButton: JButton = JButton(message("dialog.enrich")).apply {
        toolTipText = message("dialog.enrich.tooltip")
        addActionListener { openEnrichDialog() }
    }
    private val clearMacrosButton: JButton = JButton(message("dialog.clear.macros")).apply {
        toolTipText = message("dialog.clear.macros.tooltip")
        addActionListener { clearMacrosInActiveTab() }
    }
    private val keepOpenCheckBox: JCheckBox = JCheckBox(message("dialog.keep.open"), keepOpen).apply {
        addActionListener { keepOpen = isSelected }
    }

    private val macroList: JBList<MacroEntry> =
        object : JBList<MacroEntry>(MacroKind.entries.map { MacroEntry(it) }) {
            override fun getToolTipText(event: MouseEvent): String? {
                val idx = locationToIndex(event.point)
                if (idx < 0) return null
                val cellBounds = getCellBounds(idx, idx) ?: return null
                if (!cellBounds.contains(event.point)) return null
                return message(model.getElementAt(idx).kind.descriptionKey)
            }
        }.apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = makeMacroRenderer()
            // Without an explicit non-null tooltip text, getToolTipText(MouseEvent) is never
            // consulted — Swing only invokes the per-event override when the component is already
            // tooltip-enabled.
            toolTipText = ""
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) {
                        selectedValue?.let { insertMacro(it) }
                    }
                }
            })
            addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_ENTER) {
                        selectedValue?.let { insertMacro(it) }
                    }
                }
            })
        }

    private val startStopAction: Action = object : DialogWrapperAction(message("dialog.start")) {
        init {
            putValue(DEFAULT_ACTION, true)
        }

        override fun doAction(e: ActionEvent) {
            if (scheduler.isRunning) {
                scheduler.stop()
            } else {
                startTyping()
            }
        }
    }

    private lateinit var dialogPanel: DialogPanel

    /** All macro highlighters created across the tab editors — refreshed when markers change. */
    private val macroHighlighters: MutableList<MacroHighlighter> = mutableListOf()

    init {
        // Preload keyboard sounds + warm up the audio mixer on a background thread so the first
        // keystroke doesn't pay file IO + first-time mixer init latency.
        KeyboardSoundService.get().prewarm()

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
            if (newIndex < 0 || newIndex >= tabsState.size) return@addChangeListener
            activeTabIndex = newIndex
            updateLanguageCombo()
        }

        languageCombo.renderer = SimpleListCellRenderer.create("") { it?.name.orEmpty() }
        languageCombo.selectedItem = tabsState[activeTabIndex].fileType
        languageCombo.addActionListener {
            if (suppressLanguageListener) return@addActionListener
            val selected = languageCombo.selectedItem as? FileType ?: return@addActionListener
            val active = tabsState[activeTabIndex]
            if (active.fileType != selected) active.swapFileType(selected)
        }

        title = message("dialog.title")
        isModal = false
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
        // The tab strip's rollover state is driven by JTabbedPaneUI's own MouseMotionListener
        // attached to the tabbedPane. With a custom tab component, hover events over the title
        // hit the JLabel and never reach the tabbedPane, so the rollover never updates. Convert
        // and re-dispatch every motion/enter/exit event to the tabbedPane so the UI's rollover
        // tracker sees them.
        val hoverForwarder = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) = forward(e)
            override fun mouseExited(e: MouseEvent) = forward(e)
            override fun mouseMoved(e: MouseEvent) = forward(e)
            override fun mouseDragged(e: MouseEvent) = forward(e)
            private fun forward(e: MouseEvent) {
                val converted = SwingUtilities.convertMouseEvent(e.component, e, tabbedPane)
                tabbedPane.dispatchEvent(converted)
            }
        }
        panel.addMouseListener(selectListener)
        panel.addMouseListener(hoverForwarder)
        panel.addMouseMotionListener(hoverForwarder)

        val label = JLabel(state.name).apply {
            toolTipText = message("dialog.rename.tab.tooltip")
            addMouseListener(selectListener)
            addMouseListener(hoverForwarder)
            addMouseMotionListener(hoverForwarder)
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
                editor.setVerticalScrollbarVisible(true)
                editor.setHorizontalScrollbarVisible(true)
                macroHighlighters += MacroHighlighter(
                    editor,
                    disposable,
                    { openingSequence },
                    { closingSequence },
                    { Color(settings.macroColor) },
                    { Color(settings.macroArgColor) },
                )
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
        // Force the settings icon button to the same height as the language combo so the two
        // controls align on the top row.
        val comboH = languageCombo.preferredSize.height
        settingsButton.preferredSize = Dimension(comboH, comboH)
        val toolbar = JPanel(BorderLayout(4, 0)).apply {
            add(languageCombo, BorderLayout.CENTER)
            add(settingsButton, BorderLayout.EAST)
        }

        val macroScroll = JBScrollPane(
            macroList,
            javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED,
        ).apply {
            border = JBUI.Borders.customLine(com.intellij.ui.JBColor.border(), 1)
            viewport.background = macroList.background
        }
        // Match the macros-header buttons' height + insets to the JBTabbedPane tab strip on the
        // right side, so the two header rows line up across the splitter.
        val tabH = run {
            // JBTabbedPane has at least one tab by construction, so getBoundsAt(0) yields a
            // representative tab height once laid out. Use a sensible fallback before then.
            tabbedPane.getBoundsAt(0)?.height?.takeIf { it > 0 } ?: 30
        }
        listOf(enrichButton, clearMacrosButton).forEach {
            it.preferredSize = Dimension(it.preferredSize.width, tabH)
            it.margin = Insets(0, 12, 0, 12)
        }
        val macroHeader = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(enrichButton)
            add(clearMacrosButton)
        }
        val macroColumn = JPanel(BorderLayout(0, 0)).apply {
            preferredSize = Dimension(240, 0)
            add(macroHeader, BorderLayout.NORTH)
            add(macroScroll, BorderLayout.CENTER)
        }

        // Layered pane: tabbedPane fills the right column; the "+" rides PALETTE_LAYER pinned to
        // the top-right so it sits on the same line as the tabs.
        val tabsLayer = JLayeredPane()
        tabsLayer.layout = object : LayoutManager {
            override fun layoutContainer(parent: Container) {
                tabbedPane.setBounds(0, 0, parent.width, parent.height)
                val gap = 4
                val pPref = plusButton.preferredSize
                val pW = pPref.width.coerceAtLeast(28)
                val pH = pPref.height.coerceAtMost(28).coerceAtLeast(22)
                plusButton.setBounds(parent.width - pW - gap, gap, pW, pH)
            }
            override fun preferredLayoutSize(parent: Container): Dimension = tabbedPane.preferredSize
            override fun minimumLayoutSize(parent: Container): Dimension = tabbedPane.minimumSize
            override fun addLayoutComponent(name: String?, comp: Component?) {}
            override fun removeLayoutComponent(comp: Component?) {}
        }
        tabsLayer.add(tabbedPane, JLayeredPane.DEFAULT_LAYER, 0)
        tabsLayer.add(plusButton, JLayeredPane.PALETTE_LAYER, 0)

        val contentSplit = OnePixelSplitter(false, 0.22f).apply {
            firstComponent = macroColumn
            secondComponent = tabsLayer
            setHonorComponentsMinimumSize(true)
        }

        dialogPanel = panel {
            row {
                cell(toolbar)
                    .align(Align.FILL)
                    .resizableColumn()
            }
            row {
                cell(contentSplit)
                    .align(Align.FILL)
                    .resizableColumn()
            }.resizableRow()
        }
        return dialogPanel
    }

    override fun createSouthPanel(): JComponent {
        val defaultSouth = super.createSouthPanel()
        // BorderLayout vertically centers WEST and CENTER in the available height — putting both
        // the checkbox and the default south panel directly into a BorderLayout aligns the
        // checkbox baseline with the Start button without piling on extra padding. Equal left/
        // right insets match the default south panel's natural horizontal margin.
        keepOpenCheckBox.border = JBUI.Borders.emptyLeft(12)
        return JPanel(BorderLayout()).apply {
            add(keepOpenCheckBox, BorderLayout.WEST)
            add(defaultSouth, BorderLayout.CENTER)
        }
    }

    /**
     * Macro list renderer — single-column list of monospaced macro syntax. Description text is
     * surfaced via the list's per-cell tooltip rather than alongside the syntax to keep the panel
     * narrow.
     */
    private fun makeMacroRenderer(): ListCellRenderer<in MacroEntry> =
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
            panel.add(syntax, BorderLayout.WEST)
            panel
        }

    private fun openSettingsDialog() {
        val dialog = SettingsDialog(project, dialogPanel, settings)
        if (!dialog.showAndGet()) return
        // Pull the freshly-applied values back into the dialog's local snapshot so the macros
        // list / highlighter picks up the new markers and color without restarting.
        openingSequence = settings.openingSequence
        closingSequence = settings.closingSequence
        macroList.repaint()
        for (h in macroHighlighters) h.refresh()
    }

    private fun openEnrichDialog() {
        try {
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

    private fun clearMacrosInActiveTab() {
        try {
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
            ENRICH_LOG.error("Clear macros failed", t)
            Messages.showErrorDialog(
                project,
                "Clear macros failed: ${t.javaClass.simpleName}: ${t.message}",
                message("dialog.title"),
            )
        }
    }

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

    /**
     * Insert a macro at the active tab. When the editor has a selection and the macro defines a
     * placeholder (e.g. `Word`, `Namespace`), the selected text replaces the placeholder inside
     * the inserted syntax — turning a highlighted token into the macro's argument in one step.
     */
    private fun insertMacro(entry: MacroEntry) {
        val state = tabsState[activeTabIndex]
        val ed = state.editorField.editor
        val selectionModel = ed?.selectionModel
        val hasSelection = selectionModel != null && selectionModel.hasSelection()
        val selectedText = if (hasSelection) selectionModel.selectedText.orEmpty() else ""

        val syntax = entry.render(openingSequence, closingSequence).let { rendered ->
            val placeholder = entry.kind.placeholder
            if (hasSelection && placeholder != null && rendered.contains(placeholder)) {
                rendered.replace(placeholder, selectedText)
            } else {
                rendered
            }
        }

        WriteCommandAction.runWriteCommandAction(project) {
            val doc = state.editorField.document
            val (start, end) = if (hasSelection) {
                selectionModel.selectionStart to selectionModel.selectionEnd
            } else {
                val caret = ed?.caretModel?.primaryCaret?.offset ?: doc.textLength
                caret to caret
            }
            doc.replaceString(start, end, syntax)
            ed?.caretModel?.primaryCaret?.moveToOffset(start + syntax.length)
            if (hasSelection) selectionModel.removeSelection()
        }
        state.editorField.requestFocusInWindow()
    }

    /**
     * `placeholder` is the substring inside `pattern` that gets replaced by selected editor text
     * when a macro is inserted while a selection is active. `null` means the macro has no
     * placeholder and the selection is just overwritten with the rendered syntax.
     */
    private enum class MacroKind(
        val pattern: String,
        val descriptionKey: String,
        val placeholder: String? = null,
    ) {
        PAUSE("{O}pause:1000{C}", "macro.pause.description"),
        REFORMAT("{O}reformat{C}", "macro.reformat.description"),
        COMPLETE("{O}complete:3:Word{C}", "macro.complete.description", placeholder = "Word"),
        COMPLETE_DELAY(
            "{O}complete:3:500:Word{C}",
            "macro.complete.delay.description",
            placeholder = "Word",
        ),
        IMPORT_AUTO("{O}import:300{C}", "macro.import.auto.description"),
        IMPORT_NS(
            "{O}import:300:Namespace{C}",
            "macro.import.ns.description",
            placeholder = "Namespace",
        ),
        IMPORT_OPTION("{O}import:300::2{C}", "macro.import.option.description"),
        CARET("{O}caret:up:3{C}", "macro.caret.description"),
    }

    private class MacroEntry(val kind: MacroKind) {
        fun render(open: String, close: String): String =
            kind.pattern.replace("{O}", open).replace("{C}", close)
    }

    override fun createActions(): Array<Action> = arrayOf(startStopAction)

    override fun getPreferredFocusedComponent(): JComponent = tabsState[activeTabIndex].editorField

    // ── Start / Stop / dispose ──────────────────────────────────────────────────────────────

    private fun startTyping() {
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

        val importSuppressor = AutoImports.suppress()

        if (keepOpenSnapshot) {
            setUiEnabled(false)
            IdeFocusManager.getInstance(project).requestFocus(editor.contentComponent, true)
            try {
                executeTyping(
                    editor = editor,
                    text = activeText,
                    openingSequence = openingSequence,
                    closingSequence = closingSequence,
                    delay = settings.delay.toLong(),
                    jitter = settings.jitter,
                    completionDelay = settings.completionDelay.toLong(),
                    preExecutionPause = settings.preExecutionPause.toLong(),
                    scheduler = scheduler,
                    onDone = {
                        importSuppressor.restore()
                        onTypingDone()
                    },
                )
            } catch (t: Throwable) {
                // EDT-side planning errors (bad script, bracket-matching edge cases) used to leave
                // the dialog frozen with no session to stop. Recover the UI and surface the cause.
                importSuppressor.restore()
                setUiEnabled(true)
                Messages.showErrorDialog(
                    project,
                    "Failed to start typing: ${t.javaClass.simpleName}: ${t.message}",
                    message("dialog.title"),
                )
            }
        } else {
            close(OK_EXIT_CODE)
            try {
                executeTyping(
                    editor = editor,
                    text = activeText,
                    openingSequence = openingSequence,
                    closingSequence = closingSequence,
                    delay = settings.delay.toLong(),
                    jitter = settings.jitter,
                    completionDelay = settings.completionDelay.toLong(),
                    preExecutionPause = settings.preExecutionPause.toLong(),
                    scheduler = scheduler,
                    onDone = {
                        importSuppressor.restore()
                    },
                )
            } catch (t: Throwable) {
                importSuppressor.restore()
                Messages.showErrorDialog(
                    project,
                    "Failed to start typing: ${t.javaClass.simpleName}: ${t.message}",
                    message("dialog.title"),
                )
            }
        }
    }

    private fun onTypingDone() {
        if (isDisposed) return
        if (keepOpen) {
            setUiEnabled(true)
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
        TypeWriterAction.clearOpenDialog(project, this)
        super.dispose()
    }

    private fun setUiEnabled(enabled: Boolean) {
        fun walk(c: Component) {
            c.isEnabled = enabled
            if (c is Container) for (child in c.components) walk(child)
        }
        walk(dialogPanel)
        for (state in tabsState) state.editorField.setViewer(!enabled)
        // The single bottom button serves as both Start (when idle) and Stop (when running).
        // Keep it enabled in both states; just toggle the label.
        startStopAction.putValue(
            Action.NAME,
            if (enabled) message("dialog.start") else message("dialog.stop"),
        )
        keepOpenCheckBox.isEnabled = true
    }

    private fun persistSettings() {
        settings.keepOpen = keepOpen
        settings.openingSequence = openingSequence
        settings.closingSequence = closingSequence
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

    fun bringToFront() {
        val window = peer.window ?: return
        window.toFront()
        window.requestFocus()
    }
}
