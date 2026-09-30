package com.worldcopy.agentdeck.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

internal val DeckDarkColors = darkColorScheme(
    primary = DeckMint, onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF124D43), onPrimaryContainer = Color(0xFFB7F4E4),
    secondary = Color(0xFFB5C5D9), onSecondary = DeckNavy,
    secondaryContainer = Color(0xFF2C3E55), onSecondaryContainer = Color(0xFFDCE8F8),
    tertiary = Color(0xFFA9C7FF), onTertiary = Color(0xFF16325E),
    background = Color(0xFF0D1724), onBackground = Color(0xFFE7EDF4),
    surface = Color(0xFF0D1724), onSurface = Color(0xFFE7EDF4),
    surfaceVariant = Color(0xFF293746), onSurfaceVariant = Color(0xFFB9C5D1),
    surfaceContainerLowest = Color(0xFF09111B), surfaceContainerLow = Color(0xFF131F2F),
    surfaceContainer = Color(0xFF192738), surfaceContainerHigh = Color(0xFF223144),
    surfaceContainerHighest = Color(0xFF2B3B50),
    outline = Color(0xFF8798AA), outlineVariant = Color(0xFF3A4A5F),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
)
internal val DeckLightColors = lightColorScheme(
    primary = DeckTeal, onPrimary = Color.White,
    primaryContainer = Color(0xFFCBF3E8), onPrimaryContainer = Color(0xFF004C40),
    secondary = Color(0xFF435B75), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDEEBF8), onSecondaryContainer = Color(0xFF243D56),
    tertiary = Color(0xFF345F99), onTertiary = Color.White,
    background = DeckPaper, onBackground = DeckInk,
    surface = DeckPaper, onSurface = DeckInk,
    surfaceVariant = Color(0xFFE2E9ED), onSurfaceVariant = Color(0xFF4C5D68),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF0F4F7),
    surfaceContainer = Color(0xFFEAF0F4), surfaceContainerHigh = Color(0xFFE3EBF1),
    surfaceContainerHighest = Color(0xFFDCE5EC),
    outline = Color(0xFF71828F), outlineVariant = Color(0xFFC2CED7),
)

@Composable
fun AgentDeckTheme(darkTheme: Boolean = isSystemInDarkTheme(), dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DeckDarkColors
        else -> DeckLightColors
    }
    MaterialTheme(colorScheme = colorScheme, typography = Typography,
        shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp)), content = content)
}
