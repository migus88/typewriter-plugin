package games.engineroom.typewriter

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import games.engineroom.typewriter.TypeWriterBundle.message
import java.awt.Dimension
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JSpinner
import javax.swing.JTable
import javax.swing.SpinnerNumberModel
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer
import javax.swing.DefaultCellEditor

/**
 * Configure and apply enrichment for the active tab. Mutates [preset] in place — the caller's
 * [TypeWriterSettings] receives the changes via the shared reference. On OK the dialog reports
 * the chosen mode; the caller then re-runs [enrichText] against the active tab's text.
 */
class EnrichDialog(
    project: Project,
    private val languageDisplayName: String,
    private val preset: EnrichmentPreset,
    initialMode: EnrichmentMode,
) : DialogWrapper(project, true) {

    private val tableModel = KeywordTableModel(preset.keywords)
    private val table = JBTable(tableModel).apply {
        rowHeight = JBUI.scale(24)
        setShowGrid(false)
        autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN

        columnModel.getColumn(KeywordTableModel.COL_ENABLED).apply {
            preferredWidth = JBUI.scale(48)
            maxWidth = JBUI.scale(64)
            cellRenderer = BooleanCellRenderer()
        }
        columnModel.getColumn(KeywordTableModel.COL_WORD).apply {
            preferredWidth = JBUI.scale(220)
            cellRenderer = WordCellRenderer()
        }
        columnModel.getColumn(KeywordTableModel.COL_MIN).apply {
            preferredWidth = JBUI.scale(72)
            maxWidth = JBUI.scale(96)
            cellRenderer = NumberCellRenderer()
            cellEditor = SpinnerCellEditor(min = 1, max = 99)
        }
        columnModel.getColumn(KeywordTableModel.COL_MAX).apply {
            preferredWidth = JBUI.scale(72)
            maxWidth = JBUI.scale(96)
            cellRenderer = NumberCellRenderer()
            cellEditor = SpinnerCellEditor(min = 1, max = 99)
        }
    }

    private val addField: JBTextField = JBTextField().apply {
        emptyText.text = message("enrich.add.placeholder")
    }
    private val addButton: JButton = JButton(message("enrich.add")).apply {
        addActionListener { addCurrentInput() }
    }
    private val removeButton: JButton = JButton(message("enrich.remove")).apply {
        toolTipText = message("enrich.remove.tooltip")
        isEnabled = false
        addActionListener { removeSelected() }
    }

    private val modeAll = JRadioButton(message("enrich.mode.all"))
    private val modeHeavy = JRadioButton(message("enrich.mode.heavy"))
    private val modeLight = JRadioButton(message("enrich.mode.light"))

    private val enrichAction: Action = object : DialogWrapperAction(message("enrich.button")) {
        override fun doAction(e: ActionEvent) {
            // Stop editing so any pending spinner edit is committed before we read state.
            if (table.isEditing) table.cellEditor?.stopCellEditing()
            close(OK_EXIT_CODE)
        }
    }

    init {
        title = message("enrich.dialog.title")
        isModal = true

        // Mutually-exclusive selection is enforced by the DSL's buttonsGroup wrapper around the
        // radio row in createCenterPanel; we just need to set the initial selection here.
        when (initialMode) {
            EnrichmentMode.ALL -> modeAll.isSelected = true
            EnrichmentMode.HEAVY -> modeHeavy.isSelected = true
            EnrichmentMode.LIGHT -> modeLight.isSelected = true
        }

        addField.addActionListener { addCurrentInput() }
        table.selectionModel.addListSelectionListener {
            val row = table.selectedRow
            removeButton.isEnabled = row >= 0 && !preset.keywords[row].builtin
        }

        init()
    }

    val selectedMode: EnrichmentMode
        get() = when {
            modeAll.isSelected -> EnrichmentMode.ALL
            modeLight.isSelected -> EnrichmentMode.LIGHT
            else -> EnrichmentMode.HEAVY
        }

    override fun createCenterPanel(): JComponent {
        val tableScroll = JBScrollPane(table).apply {
            preferredSize = Dimension(JBUI.scale(520), JBUI.scale(360))
        }

        val addPanel = JPanel().apply {
            layout = java.awt.BorderLayout(JBUI.scale(4), 0)
            add(addField, java.awt.BorderLayout.CENTER)
            val buttons = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
                add(addButton)
                add(removeButton)
            }
            add(buttons, java.awt.BorderLayout.EAST)
        }

        return panel {
            row {
                label(message("enrich.language", languageDisplayName))
            }
            buttonsGroup {
                row(message("enrich.mode")) {
                    cell(modeAll).gap(RightGap.SMALL)
                    cell(modeHeavy).gap(RightGap.SMALL)
                    cell(modeLight)
                }
            }
            row {
                cell(tableScroll).align(Align.FILL).resizableColumn()
            }.resizableRow()
            row {
                cell(addPanel).align(Align.FILL).resizableColumn()
            }
            if (preset.keywords.isEmpty()) {
                row {
                    cell(JBLabel(message("enrich.empty")).apply {
                        foreground = com.intellij.util.ui.UIUtil.getInactiveTextColor()
                    })
                }
            }
        }
    }

    override fun createActions(): Array<Action> = arrayOf(enrichAction, cancelAction)

    override fun getPreferredFocusedComponent(): JComponent = table

    private fun addCurrentInput() {
        val raw = addField.text.trim()
        if (raw.isEmpty()) return
        // Reject duplicates (case-sensitive — keywords like `True` and `true` are both valid in
        // Python). If the entry already exists as a disabled built-in, just re-enable it.
        val existing = preset.keywords.indexOfFirst { it.word == raw }
        if (existing >= 0) {
            preset.keywords[existing].enabled = true
            tableModel.fireTableRowsUpdated(existing, existing)
            table.setRowSelectionInterval(existing, existing)
        } else {
            preset.keywords += EnrichmentKeyword().apply {
                word = raw
                enabled = true
                min = 1
                max = (raw.length - 1).coerceAtLeast(1)
                builtin = false
            }
            val newRow = preset.keywords.size - 1
            tableModel.fireTableRowsInserted(newRow, newRow)
            table.setRowSelectionInterval(newRow, newRow)
            table.scrollRectToVisible(table.getCellRect(newRow, 0, true))
        }
        addField.text = ""
        addField.requestFocusInWindow()
    }

    private fun removeSelected() {
        if (table.isEditing) table.cellEditor?.stopCellEditing()
        val row = table.selectedRow
        if (row < 0) return
        val kw = preset.keywords[row]
        if (kw.builtin) return
        preset.keywords.removeAt(row)
        tableModel.fireTableRowsDeleted(row, row)
        if (preset.keywords.isNotEmpty()) {
            val nextRow = row.coerceAtMost(preset.keywords.size - 1)
            table.setRowSelectionInterval(nextRow, nextRow)
        } else {
            removeButton.isEnabled = false
        }
    }
}

private class KeywordTableModel(private val rows: MutableList<EnrichmentKeyword>) : AbstractTableModel() {
    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = 4

    override fun getColumnName(column: Int): String = when (column) {
        COL_ENABLED -> TypeWriterBundle.message("enrich.column.enabled")
        COL_WORD -> TypeWriterBundle.message("enrich.column.word")
        COL_MIN -> TypeWriterBundle.message("enrich.column.min")
        COL_MAX -> TypeWriterBundle.message("enrich.column.max")
        else -> ""
    }

    override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
        COL_ENABLED -> Boolean::class.javaObjectType
        COL_WORD -> String::class.java
        COL_MIN, COL_MAX -> Int::class.javaObjectType
        else -> Any::class.java
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = when (columnIndex) {
        COL_ENABLED, COL_MIN, COL_MAX -> true
        else -> false
    }

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val r = rows[rowIndex]
        return when (columnIndex) {
            COL_ENABLED -> r.enabled
            COL_WORD -> r.word
            COL_MIN -> r.min
            COL_MAX -> r.max
            else -> ""
        }
    }

    override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
        val r = rows[rowIndex]
        when (columnIndex) {
            COL_ENABLED -> r.enabled = (value as? Boolean) == true
            COL_MIN -> {
                val v = (value as? Number)?.toInt() ?: return
                r.min = v.coerceAtLeast(1)
                if (r.max < r.min) r.max = r.min
                fireTableRowsUpdated(rowIndex, rowIndex)
                return
            }
            COL_MAX -> {
                val v = (value as? Number)?.toInt() ?: return
                r.max = v.coerceAtLeast(1)
                if (r.min > r.max) r.min = r.max
                fireTableRowsUpdated(rowIndex, rowIndex)
                return
            }
        }
        fireTableCellUpdated(rowIndex, columnIndex)
    }

    companion object {
        const val COL_ENABLED = 0
        const val COL_WORD = 1
        const val COL_MIN = 2
        const val COL_MAX = 3
    }
}

private class BooleanCellRenderer : TableCellRenderer {
    private val checkbox = javax.swing.JCheckBox().apply {
        horizontalAlignment = SwingConstants.CENTER
        isBorderPainted = false
    }

    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
    ): java.awt.Component {
        checkbox.isSelected = (value as? Boolean) == true
        checkbox.background = if (isSelected) table.selectionBackground else table.background
        checkbox.foreground = if (isSelected) table.selectionForeground else table.foreground
        checkbox.isOpaque = true
        return checkbox
    }
}

private class WordCellRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
    ): java.awt.Component {
        val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, font.size)
        return c
    }
}

private class NumberCellRenderer : DefaultTableCellRenderer() {
    init { horizontalAlignment = SwingConstants.RIGHT }
}

/**
 * Plain spinner-backed cell editor — Swing's stock cell editors don't ship with one for integers,
 * but JSpinner handles +/- buttons and arrow-key adjustments out of the box.
 */
private class SpinnerCellEditor(min: Int, max: Int) : DefaultCellEditor(JBTextField()), TableCellEditor {
    private val spinner = JSpinner(SpinnerNumberModel(1, min, max, 1))

    init {
        clickCountToStart = 1
    }

    override fun getCellEditorValue(): Any = spinner.value

    override fun getTableCellEditorComponent(
        table: JTable, value: Any?, isSelected: Boolean, row: Int, column: Int,
    ): java.awt.Component {
        spinner.value = (value as? Number)?.toInt() ?: 1
        return spinner
    }

    override fun stopCellEditing(): Boolean {
        return try {
            spinner.commitEdit()
            super.stopCellEditing()
        } catch (_: java.text.ParseException) {
            false
        }
    }
}
