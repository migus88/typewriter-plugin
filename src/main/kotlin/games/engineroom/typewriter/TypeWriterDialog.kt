package games.engineroom.typewriter

import games.engineroom.typewriter.TypeWriterBundle.message
import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.util.TextRange
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
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Point
import java.awt.event.ActionEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Action
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

private val ENRICH_LOG = logger<TypeWriterDialog>()

/** Strip HTML tags from a description string for use in the manually-wrapped list cell. */
private fun stripHtmlTags(text: String): String =
    text.replace(Regex("<[^>]*>"), "").replace(Regex("\\s+"), " ").trim()

/**
 * Greedy word-wrap [plain] into at most [maxLines] lines that each fit inside [maxWidth] pixels
 * under [fm]. The final line gets a trailing ellipsis when the input doesn't fully fit. Single
 * words wider than [maxWidth] are broken at the last character that still fits, so a giant
 * unbroken token can't blow out the cell.
 */
private fun fitDescription(
    plain: String,
    maxLines: Int,
    maxWidth: Int,
    fm: FontMetrics,
): List<String> {
    if (maxLines <= 0 || maxWidth <= 0 || plain.isEmpty()) return emptyList()

    val words = plain.split(' ').filter { it.isNotEmpty() }
    val lines = mutableListOf<String>()
    var line = StringBuilder()
    var i = 0

    fun flush() {
        if (line.isNotEmpty()) lines.add(line.toString())
        line = StringBuilder()
    }

    while (i < words.size) {
        val word = words[i]
        val candidate = if (line.isEmpty()) word else "$line $word"
        if (fm.stringWidth(candidate) <= maxWidth) {
            line = StringBuilder(candidate)
            i++
        } else if (line.isEmpty()) {
            val cap = breakWordToFit(word, maxWidth, fm)
            if (cap.isEmpty()) break
            line = StringBuilder(cap)
            flush()
            i++
            if (lines.size >= maxLines) break
        } else {
            flush()
            if (lines.size >= maxLines) break
        }
    }
    flush()

    if (lines.size > maxLines) {
        val capped = lines.take(maxLines).toMutableList()
        capped[maxLines - 1] = appendEllipsis(capped[maxLines - 1], maxWidth, fm)
        return capped
    }
    if (i < words.size && lines.size == maxLines) {
        lines[lines.size - 1] = appendEllipsis(lines[lines.size - 1], maxWidth, fm)
    }
    return lines
}

private fun breakWordToFit(word: String, maxWidth: Int, fm: FontMetrics): String {
    val out = StringBuilder()
    for (c in word) {
        val candidate = StringBuilder(out).append(c).toString()
        if (fm.stringWidth(candidate) <= maxWidth) out.append(c) else break
    }
    return out.toString()
}

private fun appendEllipsis(line: String, maxWidth: Int, fm: FontMetrics): String {
    val ellipsis = "…"
    var s = line
    while (s.isNotEmpty() && fm.stringWidth("$s$ellipsis") > maxWidth) {
        s = s.dropLast(1).trimEnd()
    }
    return "$s$ellipsis"
}

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

    // Custom tab strip: a single-row JPanel of TabButtons inside a horizontal scroll pane, with a
    // CardLayout below for the active tab's editor. Built ourselves rather than using JBTabs
    // because (a) JBTabs's close-X auto-hides on hover with no per-tab override, and (b) JBTabs
    // overflow goes to a chevron-popup, never a horizontal scroll bar.
    private val tabStrip: JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
    }
    private val tabScroll: JBScrollPane = JBScrollPane(
        tabStrip,
        javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
        // ALWAYS instead of AS_NEEDED so the scrollbar reserves a dedicated row at the bottom of
        // the strip — without that, JBScrollPane on macOS renders the horizontal bar in overlay
        // mode, painting it on top of the tab labels.
        javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS,
    ).apply {
        border = JBUI.Borders.empty()
        horizontalScrollBar.unitIncrement = JBUI.scale(30)
        horizontalScrollBar.blockIncrement = JBUI.scale(120)
        // Pin the strip+scrollbar block to a known total height. NORTH placement asks for the
        // preferred height; without an explicit value the layout slack made the bar visually
        // collide with the labels.
        preferredSize = JBUI.size(0, TAB_ROW_HEIGHT + SCROLLBAR_HEIGHT)
        minimumSize = JBUI.size(0, TAB_ROW_HEIGHT + SCROLLBAR_HEIGHT)
    }
    private val tabContent: JPanel = JPanel(CardLayout()).apply { isOpaque = false }
    private val languageCombo: ComboBox<FileType> = ComboBox(textFileTypes())
    private val plusButton: JButton = JButton(AllIcons.General.Add).apply {
        toolTipText = message("dialog.add.tab.tooltip")
        addActionListener { addNewTab() }
    }
    private val settingsButton: JButton = JButton(AllIcons.General.Settings).apply {
        toolTipText = message("dialog.settings.tooltip")
        addActionListener { openSettingsDialog() }
    }
    private val customMacrosButton: JButton = JButton(message("dialog.custom.macros")).apply {
        toolTipText = message("dialog.custom.macros.tooltip")
        addActionListener { openCustomMacrosDialog() }
    }
    private val keepOpenCheckBox: JCheckBox = JCheckBox(message("dialog.keep.open"), keepOpen).apply {
        addActionListener { keepOpen = isSelected }
    }

    /** Backing model for [macroList] — repopulated whenever custom macros change. */
    private val macroListModel: javax.swing.DefaultListModel<MacroEntry> =
        javax.swing.DefaultListModel<MacroEntry>().also { rebuildMacroEntries(it) }

    // The renderer surfaces each macro's description directly inside the cell, so a hover
    // tooltip would just duplicate it (and pop out of bounds for long descriptions). Rely on
    // the inline rendering instead — no per-cell tooltip.
    //
    // `setExpandableItemsEnabled(false)` turns off JBList's hover-to-expand overlay. With it on,
    // hovering a macro renders a popup *over* surrounding UI to show the row's "full" content —
    // which both overlays the column's neighbours and truncates the wrapped HTML description on
    // its right edge. The inline cell already shows the full description, so the overlay only
    // adds noise.
    private val macroList: JBList<MacroEntry> = JBList(macroListModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = makeMacroRenderer()
        setExpandableItemsEnabled(false)
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

    private fun rebuildMacroEntries(model: javax.swing.DefaultListModel<MacroEntry>) {
        model.clear()
        for (kind in MacroKind.entries) model.addElement(MacroEntry.BuiltIn(kind))
        for (data in settings.customMacros) {
            model.addElement(MacroEntry.Custom(data.name, data.parameters.toList(), data.description))
        }
    }

    // Enrich + Clear macros are surfaced as DialogWrapperActions (rather than standalone JButtons)
    // so they render with the same visual treatment as Start — same height, padding, and rounded
    // shape — without picking up the default-action accent fill that's reserved for Start.
    private val enrichAction: Action = object : DialogWrapperAction(message("dialog.enrich")) {
        override fun doAction(e: ActionEvent) {
            openEnrichDialog()
        }
    }
    private val clearMacrosAction: Action = object : DialogWrapperAction(message("dialog.clear.macros")) {
        override fun doAction(e: ActionEvent) {
            clearMacrosInActiveTab()
        }
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

        for (state in tabsState) {
            val button = makeTabButton(state)
            state.button = button
            tabStrip.add(button)
            tabContent.add(state.editorField, state.cardId)
        }
        selectTabByIndex(activeTabIndex)

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
        val button = makeTabButton(state)
        state.button = button
        tabStrip.add(button)
        tabContent.add(state.editorField, state.cardId)
        tabStrip.revalidate()
        tabStrip.repaint()
        selectTabByIndex(tabsState.size - 1)
        // Defer the scroll so the strip has finished its layout pass under the new child.
        SwingUtilities.invokeLater { scrollTabIntoView(state) }
    }

    private fun closeTabState(state: TabState) {
        if (tabsState.size <= 1) return
        val index = tabsState.indexOf(state)
        if (index < 0) return
        val button = state.button
        tabsState.removeAt(index)
        if (button != null) tabStrip.remove(button)
        tabContent.remove(state.editorField)
        tabStrip.revalidate()
        tabStrip.repaint()
        if (activeTabIndex >= tabsState.size) activeTabIndex = tabsState.size - 1
        selectTabByIndex(activeTabIndex)
    }

    private fun selectTabByIndex(index: Int) {
        if (index < 0 || index >= tabsState.size) return
        activeTabIndex = index
        for ((i, s) in tabsState.withIndex()) {
            s.button?.setActive(i == index)
        }
        (tabContent.layout as CardLayout).show(tabContent, tabsState[index].cardId)
        updateLanguageCombo()
        SwingUtilities.invokeLater { scrollTabIntoView(tabsState[index]) }
    }

    private fun scrollTabIntoView(state: TabState) {
        val button = state.button ?: return
        val bounds = button.bounds
        if (bounds.width > 0) tabStrip.scrollRectToVisible(bounds)
    }

    private fun makeTabButton(state: TabState): TabButton =
        TabButton(
            state = state,
            onSelect = { selectTabByIndex(tabsState.indexOf(state)) },
            onClose = { closeTabState(state) },
            onRename = { renameTab(state) },
        )

    private fun renameTab(state: TabState) {
        // `Messages.showInputDialog(component, …)` accepts a parent component but still places the
        // popup on the IDE frame's centre — there's no hook to override the location. Use a
        // small purpose-built DialogWrapper that honours `getInitialLocation()`, mirroring how
        // SettingsDialog and CustomMacrosDialog land on top of the typewriter window.
        val dialog = RenameTabDialog(project, dialogPanel, state.name)
        if (!dialog.showAndGet()) return
        val newName = dialog.newName.trim()
        if (newName.isEmpty()) return
        state.name = newName
        state.button?.setTitle(newName)
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
            preferredSize = JBUI.size(720, 320)
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
                installPlainEnterHandler(editor)
                installAutoPopupTrigger(editor)
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

    /**
     * Schedule the IDE's completion auto-popup ([AutoPopupController]) when the user types
     * either the closing character of the configured opening marker (so macro suggestions appear
     * the moment they open `` `{ ``) or the second consecutive identifier character (so word /
     * keyword completion behaves like the IDE editors).
     *
     * [TypewriterCompletionContributor] decides what the popup actually contains; this listener
     * just decides *when* to schedule it. The single-char filter (`newLength == 1 &&
     * oldLength == 0`) excludes programmatic mutations — Enrich/Clear macros, plain-Enter inserts,
     * macro-list inserts via [insertMacro], and lookup-accept replacements are all multi-char or
     * have non-zero `oldLength`.
     */
    private fun installAutoPopupTrigger(editor: Editor) {
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (event.newLength != 1 || event.oldLength != 0) return

                val typed = event.newFragment[0]
                val docText = event.document.charsSequence
                val caretAfter = event.offset + 1

                val open = openingSequence
                val openLast = open.lastOrNull()
                val justClosedOpener = openLast != null &&
                    typed == openLast &&
                    caretAfter >= open.length &&
                    matchesAt(docText, open, caretAfter - open.length)

                val prev = if (event.offset > 0) docText[event.offset - 1] else ' '
                val isSecondIdentChar = isIdentifierChar(typed) && isIdentifierChar(prev)

                if (justClosedOpener || isSecondIdentChar) {
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) return@invokeLater
                        AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
                    }
                }
            }

            private fun matchesAt(text: CharSequence, marker: String, offset: Int): Boolean {
                for (j in marker.indices) {
                    if (text[offset + j] != marker[j]) return false
                }
                return true
            }

            private fun isIdentifierChar(c: Char): Boolean =
                c.isLetterOrDigit() || c == '_'
        }, disposable)
    }

    /**
     * Replace the language's smart-Enter with a plain newline that preserves the current line's
     * leading whitespace. Without this, languages like C# auto-indent every line break by 4
     * spaces, even when the user just wants to lay text out manually.
     *
     * When the completion lookup is active, the action disables itself in [AnAction.update] —
     * `IdeKeyEventDispatcher` then skips it and the keystroke falls through to the lookup's own
     * Enter handler, which accepts the highlighted entry. Without this gate, picking a macro from
     * the auto-completion popup with Enter inserted a newline instead.
     */
    private fun installPlainEnterHandler(editor: Editor) {
        val plainEnter = object : AnAction() {
            override fun update(e: AnActionEvent) {
                val ed = e.getData(CommonDataKeys.EDITOR) ?: editor
                e.presentation.isEnabled = LookupManager.getActiveLookup(ed) == null
            }

            override fun actionPerformed(e: AnActionEvent) {
                val ed = e.getData(CommonDataKeys.EDITOR) ?: editor
                val caret = ed.caretModel.primaryCaret
                val doc = ed.document
                val offset = caret.offset
                val lineNum = doc.getLineNumber(offset)
                val lineStart = doc.getLineStartOffset(lineNum)
                val before = doc.getText(TextRange(lineStart, offset))
                val indent = before.takeWhile { it == ' ' || it == '\t' }
                WriteCommandAction.runWriteCommandAction(project) {
                    doc.insertString(offset, "\n$indent")
                    caret.moveToOffset(offset + 1 + indent.length)
                }
            }
        }
        plainEnter.registerCustomShortcutSet(
            CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)),
            editor.contentComponent,
            disposable,
        )
    }

    private fun createDocument(fileType: FileType, text: String): Document {
        val ext = fileType.defaultExtension.ifBlank { "txt" }
        val virtualFile = LightVirtualFile("typewriter_input.$ext", fileType, text)
        // Tag the file so TypewriterCompletionContributor knows it should fire here (and only
        // here — the contributor is registered for `language="any"` so the tag is what gates it
        // out of every other editor in the IDE).
        virtualFile.putUserData(TYPEWRITER_DIALOG_FILE_KEY, true)
        return FileDocumentManager.getInstance().getDocument(virtualFile)
            ?: EditorFactory.getInstance().createDocument(text)
    }

    // ── Layout ──────────────────────────────────────────────────────────────────────────────

    override fun createCenterPanel(): JComponent {
        // Match the icon button heights to the language combo so the top row aligns.
        val comboH = languageCombo.preferredSize.height
        val sq = Dimension(comboH, comboH)
        settingsButton.preferredSize = sq
        // The + button sits on the tabs row (see below) — sized to the tab strip's height so the
        // two visually align. Settings stays on the language-combo toolbar.
        plusButton.preferredSize = JBUI.size(comboH.coerceAtMost(TAB_ROW_HEIGHT), TAB_ROW_HEIGHT)
        val toolbar = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
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
        // Match the footer button's height to the tab strip's natural height so the column footer
        // lines up vertically with the tabs across the splitter.
        val tabH = JBUI.scale(30)
        customMacrosButton.preferredSize = Dimension(customMacrosButton.preferredSize.width, tabH)
        customMacrosButton.margin = JBUI.insets(0, 12)
        // "Edit" sits at the bottom-right of the macro column — it edits the custom macros (which
        // appear at the bottom of the list). Right-aligned so it doesn't read like the start of
        // a row of controls.
        val macroFooter = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
            add(customMacrosButton)
        }
        val macroColumn = JPanel(BorderLayout(0, 0)).apply {
            preferredSize = JBUI.size(240, 0)
            add(macroScroll, BorderLayout.CENTER)
            add(macroFooter, BorderLayout.SOUTH)
        }

        // Tab strip + plus button share one row at the top. The strip fills the remaining width
        // (CENTER) and the plus button anchors at EAST — that way the strip's horizontal scroll
        // bar belongs to the tabs alone, never extending under the plus button. The plus is
        // wrapped so it pins to the top of the row instead of stretching vertically with the
        // scrollbar gutter. Below it, the active tab's editor fills CENTER via CardLayout swap.
        val plusWrap = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(plusButton, BorderLayout.NORTH)
        }
        val tabsRow = JPanel(BorderLayout()).apply {
            add(tabScroll, BorderLayout.CENTER)
            add(plusWrap, BorderLayout.EAST)
            preferredSize = JBUI.size(0, TAB_ROW_HEIGHT + SCROLLBAR_HEIGHT)
        }
        val tabsPanel = JPanel(BorderLayout()).apply {
            add(tabsRow, BorderLayout.NORTH)
            add(tabContent, BorderLayout.CENTER)
        }

        val contentSplit = OnePixelSplitter(false, 0.22f).apply {
            firstComponent = macroColumn
            secondComponent = tabsPanel
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
     * Macro list renderer — two-block cells with the syntax on top and a smaller, dimmer
     * description below. Descriptions are manually word-wrapped via [fitDescription] (capped at
     * 3 lines, last line ellipsized when truncated) and rendered as one [JLabel] per line in a
     * vertical stack. Manual wrapping replaced an earlier `<html><div style='width:...'>` JLabel
     * approach because the HTML width hint didn't reliably match the actual cell viewport — long
     * descriptions overran the right edge instead of wrapping cleanly.
     *
     * Any HTML markup in the source description (e.g. `<b>` highlights in built-in messages) is
     * stripped before wrapping; that's the cost of the more reliable rendering.
     *
     * Custom macros render in italic and the first one carries a 1-pixel top border, separating
     * the user's macros from the built-ins above without needing a non-selectable header row.
     */
    private fun makeMacroRenderer(): ListCellRenderer<in MacroEntry> =
        ListCellRenderer { list, value, index, selected, _ ->
            val isCustom = value is MacroEntry.Custom
            val isFirstCustom = isCustom &&
                (index == 0 || list.model.getElementAt(index - 1) !is MacroEntry.Custom)
            val rowBg = if (selected) list.selectionBackground else list.background
            val rowFg = if (selected) list.selectionForeground else list.foreground
            // Inactive-text colour is theme-aware (lighter on dark, darker on light), giving the
            // description natural contrast without us picking a fixed shade per theme.
            val descFg: Color = if (selected) {
                rowFg
            } else {
                com.intellij.util.ui.UIUtil.getInactiveTextColor()
            }

            val syntax = JLabel(value.render(openingSequence, closingSequence)).apply {
                font = Font(Font.MONOSPACED, if (isCustom) Font.ITALIC else Font.PLAIN, font.size)
                foreground = rowFg
                alignmentX = 0f
            }

            val descText = stripHtmlTags(value.description())
            val descFont = list.font.deriveFont(list.font.size2D - JBUI.scale(1).toFloat())
            val descLines: List<String> = if (descText.isBlank()) emptyList() else {
                // 8px horizontal padding each side (16) + 1px border + scrollbar gutter (~12px on
                // macOS) + a few px safety. Falling back to 220 keeps the cell renderable during
                // the first paint, before the list has been sized.
                val maxWidth = (list.width.takeIf { it > 0 } ?: 220) - JBUI.scale(28)
                val fm = list.getFontMetrics(descFont)
                fitDescription(descText, maxLines = 3, maxWidth = maxWidth, fm = fm)
            }

            val column = JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(syntax)
                if (descLines.isNotEmpty()) {
                    add(javax.swing.Box.createVerticalStrut(JBUI.scale(2)))
                    for (line in descLines) {
                        add(JLabel(line).apply {
                            font = descFont
                            foreground = descFg
                            alignmentX = 0f
                        })
                    }
                }
            }

            JPanel(BorderLayout()).apply {
                isOpaque = true
                background = rowBg
                border = if (isFirstCustom) {
                    JBUI.Borders.compound(
                        JBUI.Borders.customLine(com.intellij.ui.JBColor.border(), 1, 0, 0, 0),
                        JBUI.Borders.empty(6, 8),
                    )
                } else {
                    JBUI.Borders.empty(6, 8)
                }
                add(column, BorderLayout.CENTER)
            }
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

    private fun openCustomMacrosDialog() {
        val dialog = CustomMacrosDialog(
            project = project,
            parentComponent = dialogPanel,
            settings = settings,
            openingSequence = openingSequence,
            closingSequence = closingSequence,
        )
        if (!dialog.showAndGet()) return
        rebuildMacroEntries(macroListModel)
        macroList.repaint()
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
            val placeholder = entry.placeholder()
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

    private sealed class MacroEntry {
        abstract fun render(open: String, close: String): String
        abstract fun placeholder(): String?
        /**
         * Short description shown directly under the macro syntax in the list cell. The renderer
         * strips HTML tags before measuring/wrapping, so this can return raw bundle messages
         * (which may include `<b>` markup) without breaking the layout.
         */
        abstract fun description(): String

        class BuiltIn(val kind: MacroKind) : MacroEntry() {
            override fun render(open: String, close: String): String =
                kind.pattern.replace("{O}", open).replace("{C}", close)
            override fun placeholder(): String? = kind.placeholder
            override fun description(): String = message(kind.descriptionKey)
        }

        /**
         * Custom macros render as `{name}` (or `{name:Param1:Param2}` when parameters are
         * defined). They expand to literal text via [expandCustomMacros] before the typing
         * pipeline sees them, with each `$paramName$` reference in the body substituted by the
         * matching positional argument.
         */
        class Custom(
            val name: String,
            val parameters: List<String>,
            private val descriptionText: String,
        ) : MacroEntry() {
            override fun render(open: String, close: String): String {
                if (parameters.isEmpty()) return "$open$name$close"
                return "$open$name:${parameters.joinToString(":")}$close"
            }
            // The first parameter doubles as the placeholder so a selection-replace insert
            // (insertMacro) drops the highlighted token into the first positional argument slot,
            // matching how built-in placeholders like `Word`/`Namespace` behave.
            override fun placeholder(): String? = parameters.firstOrNull()
            override fun description(): String = descriptionText
        }
    }

    override fun createActions(): Array<Action> =
        arrayOf(enrichAction, clearMacrosAction, startStopAction)

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
                    customMacros = settings.customMacros,
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
                    customMacros = settings.customMacros,
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
        // Enrich + Clear macros live in the south panel as DialogWrapperActions, not in the
        // dialogPanel tree — so the walk above doesn't reach them. Toggle their Action.enabled
        // explicitly; DialogWrapper mirrors that to the rendered button's enabled state.
        enrichAction.isEnabled = enabled
        clearMacrosAction.isEnabled = enabled
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
        /** Stable identifier for this state's CardLayout entry — survives renames. */
        val cardId: String = "tab-${cardIdSeq.incrementAndGet()}"

        /** Set right after the TabButton is built; lets state-side ops drive the UI. */
        var button: TabButton? = null

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

    /**
     * One row of the custom tab strip. Renders `[ label  × ]`, switches the active tab on left
     * click, opens the rename dialog on double-click, and triggers `onClose` when the X is hit.
     * The X is always visible — that's the whole reason we're not using JBTabs.
     */
    private inner class TabButton(
        val state: TabState,
        private val onSelect: () -> Unit,
        private val onClose: () -> Unit,
        private val onRename: () -> Unit,
    ) : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)) {

        private val label = JLabel(state.name).apply {
            toolTipText = message("dialog.rename.tab.tooltip")
            border = JBUI.Borders.empty()
        }
        private val close = JButton(AllIcons.Actions.Close).apply {
            rolloverIcon = AllIcons.Actions.CloseHovered
            isFocusable = false
            isContentAreaFilled = false
            isBorderPainted = false
            margin = JBUI.emptyInsets()
            preferredSize = JBUI.size(16, 16)
            toolTipText = message("dialog.close.tab")
            addActionListener { onClose() }
        }

        init {
            isOpaque = true
            // Vertical padding sized so each tab clearly out-reads the horizontal scrollbar that
            // sits directly underneath in the strip's scroll-pane layout.
            border = JBUI.Borders.empty(7, 12, 7, 6)
            background = INACTIVE_BG
            add(label)
            add(close)
            // Don't grow vertically beyond the natural row height — BoxLayout would otherwise
            // stretch us to fill the strip's height, which exposes the background fill all the way
            // down to the splitter.
            alignmentY = TOP_ALIGNMENT
            val mouse = object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (!SwingUtilities.isLeftMouseButton(e)) return
                    if (e.clickCount == 2) onRename() else onSelect()
                }
            }
            addMouseListener(mouse)
            label.addMouseListener(mouse)
        }

        override fun getMaximumSize(): Dimension {
            // Lock vertical size to the preferred so BoxLayout doesn't stretch us.
            val pref = preferredSize
            return Dimension(pref.width, pref.height)
        }

        fun setActive(active: Boolean) {
            background = if (active) ACTIVE_BG else INACTIVE_BG
            label.foreground = if (active) ACTIVE_FG else null
            repaint()
        }

        fun setTitle(title: String) {
            label.text = title
            revalidate()
            repaint()
        }
    }

    companion object {
        private val cardIdSeq = java.util.concurrent.atomic.AtomicInteger()
        /** Pixel height of a single tab button — keeps the strip taller than the scrollbar. */
        private const val TAB_ROW_HEIGHT = 32
        /** Height reserved for the horizontal scroll bar under the strip. */
        private const val SCROLLBAR_HEIGHT = 14
        private val ACTIVE_BG: Color = com.intellij.ui.JBColor.namedColor(
            "EditorTabs.underlinedTabBackground",
            com.intellij.util.ui.UIUtil.getListSelectionBackground(true),
        )
        private val INACTIVE_BG: Color = com.intellij.ui.JBColor.namedColor(
            "EditorTabs.background",
            com.intellij.util.ui.UIUtil.getPanelBackground(),
        )
        private val ACTIVE_FG: Color = com.intellij.ui.JBColor.namedColor(
            "EditorTabs.underlinedTabForeground",
            com.intellij.util.ui.UIUtil.getListSelectionForeground(true),
        )
    }
}

/**
 * Tab rename popup. `Messages.showInputDialog(component, …)` ignores the parent for placement and
 * always lands on the IDE frame; this DialogWrapper subclass exists solely so we can override
 * [getInitialLocation] and pin the popup over the typewriter dialog instead — same approach
 * SettingsDialog and CustomMacrosDialog use.
 */
private class RenameTabDialog(
    project: Project,
    private val parentComponent: Component,
    initialName: String,
) : DialogWrapper(project, parentComponent, true, IdeModalityType.IDE) {

    private val nameField: JTextField = JTextField(initialName).apply {
        selectAll()
    }

    val newName: String get() = nameField.text

    init {
        title = TypeWriterBundle.message("dialog.rename.tab.title")
        init()
    }

    override fun createCenterPanel(): JComponent =
        JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            preferredSize = JBUI.size(280, 60)
            add(JLabel(TypeWriterBundle.message("dialog.rename.tab.prompt")), BorderLayout.NORTH)
            add(nameField, BorderLayout.CENTER)
        }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun getInitialLocation(): Point? {
        val parentWindow = SwingUtilities.getWindowAncestor(parentComponent) ?: return null
        val pref = preferredSize ?: return null
        val x = parentWindow.x + (parentWindow.width - pref.width) / 2
        val y = parentWindow.y + (parentWindow.height - pref.height) / 2
        return Point(x.coerceAtLeast(0), y.coerceAtLeast(0))
    }
}
