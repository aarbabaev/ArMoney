package com.armoney.android

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignContrastTest {
    @Test fun semanticTextPairsMeetNormalTextContrastInBothThemes() {
        listOf(BankDesign.LightColors, BankDesign.DarkColors).forEachIndexed { index, colors ->
            val pairs = listOf(
                "primary action" to (colors.onPrimary to colors.primary),
                "accent panel" to (colors.onPrimaryContainer to colors.primaryContainer),
                "secondary action" to (colors.secondary to colors.background),
                "status" to (colors.onSecondaryContainer to colors.secondaryContainer),
                "page" to (colors.onBackground to colors.background),
                "card" to (colors.onSurface to colors.surface),
                "guidance on page" to (colors.onSurfaceVariant to colors.background),
                "guidance on card" to (colors.onSurfaceVariant to colors.surface),
                "error" to (colors.error to colors.background),
                "error panel" to (colors.onErrorContainer to colors.errorContainer)
            )
            pairs.forEach { (role, pair) ->
                val ratio = contrast(pair.first, pair.second)
                assertTrue("Theme $index $role contrast $ratio is below 4.5:1", ratio >= 4.5)
            }
        }
    }

    private fun contrast(a: Color, b: Color): Double {
        val first = a.luminance().toDouble()
        val second = b.luminance().toDouble()
        return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
    }
}
