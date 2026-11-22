/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.components

import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.UIManager

/** Windows' folder picker, through Swing's, which can choose folders where AWT's can't. It opens in [start]. */
fun chooseFolder(title: String, start: String = System.getProperty("user.home"), onChosen: (String) -> Unit) {
    SwingUtilities.invokeLater {
        // Windows' own look, so the picker isn't Swing's grey Metal.
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        val chooser = JFileChooser(start).apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            val dir = chooser.selectedFile?.absolutePath ?: return@invokeLater
            onChosen(dir)
        }
    }
}
