package games.engineroom.typewriter

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.DialogWrapper.IdeModalityType
import com.intellij.ui.ColorPanel
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import games.engineroom.typewriter.TypeWriterBundle.message
import java.awt.Color
import java.awt.Component
import java.awt.Point
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * Modal popup hosting the cross-tab knobs (typing pace, jitter, marker tokens, completion popup
 * dwell, macro highlight color). Mutates a [TypeWriterSettings] reference in place on OK.
 *
 * The dialog is parented to [parent] so it centers over the typewriter window rather than the
 * main IDE frame.
 */
class SettingsDialog(
    project: Project,
    private val parentComponent: Component,
    private val settings: TypeWriterSettings,
) : DialogWrapper(project, parentComponent, true, IdeModalityType.IDE) {

    var delay: Int = settings.delay
    var jitter: Int = settings.jitter
    var openingSequence: String = settings.openingSequence
    var closingSequence: String = settings.closingSequence
    var completionDelay: Int = settings.completionDelay

    private val colorPanel: ColorPanel = ColorPanel().apply {
        selectedColor = Color(settings.macroColor)
    }
    private val argColorPanel: ColorPanel = ColorPanel().apply {
        selectedColor = Color(settings.macroArgColor)
    }

    private lateinit var dialogPanel: DialogPanel

    init {
        title = message("dialog.settings.title")
        isModal = true
        init()
    }

    override fun createCenterPanel(): JComponent {
        dialogPanel = panel {
            row {
                intTextField(IntRange(1, 2000), 4)
                    .label(message("dialog.delay"))
                    .bindIntText(::delay)
                    .gap(RightGap.SMALL)
                @Suppress("DialogTitleCapitalization")
                label(message("dialog.ms"))
            }
            row {
                intTextField(IntRange(0, 2000), 4)
                    .label(message("dialog.jitter"))
                    .bindIntText(::jitter)
                    .gap(RightGap.SMALL)
                @Suppress("DialogTitleCapitalization")
                label(message("dialog.ms"))
            }
            row(message("dialog.macro.markers")) {
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
            row(message("dialog.macro.color")) {
                cell(colorPanel)
            }
            row(message("dialog.macro.arg.color")) {
                cell(argColorPanel)
            }
        }
        return dialogPanel
    }

    override fun doOKAction() {
        dialogPanel.apply()
        settings.delay = delay
        settings.jitter = jitter
        settings.openingSequence = openingSequence
        settings.closingSequence = closingSequence
        settings.completionDelay = completionDelay
        colorPanel.selectedColor?.rgb?.let { settings.macroColor = it }
        argColorPanel.selectedColor?.rgb?.let { settings.macroArgColor = it }
        super.doOKAction()
    }

    /**
     * DialogWrapper's `(project, parentComponent, …)` constructor sets the parent for window
     * ownership but the project-aware code path still places the dialog at the IDE frame's
     * centre. Compute the centre manually so the popup lands on top of the typewriter window.
     */
    override fun getInitialLocation(): Point? {
        val parentWindow = SwingUtilities.getWindowAncestor(parentComponent) ?: return null
        val pref = preferredSize ?: return null
        val x = parentWindow.x + (parentWindow.width - pref.width) / 2
        val y = parentWindow.y + (parentWindow.height - pref.height) / 2
        return Point(x.coerceAtLeast(0), y.coerceAtLeast(0))
    }
}
