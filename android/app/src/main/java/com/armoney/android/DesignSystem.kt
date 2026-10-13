package com.armoney.android

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** ArMoney's fixed palette deliberately does not adopt wallpaper-derived colors. */
internal object BankDesign {
    val Shark = Color(0xFF191C1F)
    val White = Color(0xFFFFFFFF)
    val Cornflower = Color(0xFF7F84F6)
    val LightColors = lightColorScheme(
        primary = Shark, onPrimary = White,
        primaryContainer = Cornflower, onPrimaryContainer = Shark,
        secondary = Color(0xFF4448A5), onSecondary = White,
        secondaryContainer = Color(0xFFE7E7FF), onSecondaryContainer = Shark,
        background = Color(0xFFF5F6F8), onBackground = Shark,
        surface = White, onSurface = Shark,
        surfaceVariant = Color(0xFFEBEDF0), onSurfaceVariant = Color(0xFF51565F),
        surfaceContainer = White, outline = Color(0xFF707680),
        error = Color(0xFFAB2131), onError = White,
        errorContainer = Color(0xFFFFDADF), onErrorContainer = Color(0xFF670014)
    )
    val DarkColors = darkColorScheme(
        primary = Cornflower, onPrimary = Shark,
        primaryContainer = Cornflower, onPrimaryContainer = Shark,
        secondary = Color(0xFFC3C4FF), onSecondary = Shark,
        secondaryContainer = Color(0xFF343650), onSecondaryContainer = White,
        background = Color(0xFF101214), onBackground = White,
        surface = Shark, onSurface = White,
        surfaceVariant = Color(0xFF2C3036), onSurfaceVariant = Color(0xFFC1C5CD),
        surfaceContainer = Shark, outline = Color(0xFF939AA6),
        error = Color(0xFFFFB2BC), onError = Color(0xFF670014),
        errorContainer = Color(0xFF650D20), onErrorContainer = Color(0xFFFFDADF)
    )
    val Gap = 8.dp
    val Space = 16.dp
    val Page = 24.dp
    val Touch = 48.dp
    val CardShape = RoundedCornerShape(24.dp)
    val Pill = RoundedCornerShape(50)
    val Type = Typography(
        displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 44.sp),
        headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp),
        headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp),
        titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp),
        titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
        bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
        labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp)
    )
}

@Composable internal fun ArMoneyTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) BankDesign.DarkColors else BankDesign.LightColors,
        typography = BankDesign.Type,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
            large = BankDesign.CardShape, extraLarge = RoundedCornerShape(28.dp)), content = content)
}

@Composable internal fun PrimaryAction(onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    Button(onClick, modifier.fillMaxWidth().heightIn(min = BankDesign.Touch), enabled = enabled,
        shape = BankDesign.Pill, contentPadding = PaddingValues(BankDesign.Space), content = content)
}

@Composable internal fun SecondaryAction(onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    OutlinedButton(onClick, modifier.fillMaxWidth().heightIn(min = BankDesign.Touch), enabled = enabled,
        shape = BankDesign.Pill, contentPadding = PaddingValues(BankDesign.Space), content = content)
}

@Composable internal fun BankCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), shape = BankDesign.CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), content = content)
}

/** Status always includes words; financial outcomes are never inferred from color. */
@Composable internal fun StatusLabel(text: String, attention: Boolean = false) {
    Surface(shape = RoundedCornerShape(12.dp),
        color = if (attention) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (attention) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = BankDesign.Gap), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable internal fun Guidance(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
