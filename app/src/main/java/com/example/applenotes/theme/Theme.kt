package com.example.applenotes.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = NotesYellow,
    onPrimary = NotesInk,
    primaryContainer = NotesYellowDark,
    onPrimaryContainer = Color.White,
    secondary = NotesYellow,
    onSecondary = NotesInk,
    background = Color(0xFF14110A),
    onBackground = Color(0xFFF5EAD0),
    surface = Color(0xFF1B1709),
    onSurface = Color(0xFFF5EAD0),
    surfaceVariant = Color(0xFF2A2517),
    onSurfaceVariant = Color(0xFFC9BD9C),
)

private val LightColorScheme = lightColorScheme(
    primary = NotesYellow,
    onPrimary = NotesInk,
    primaryContainer = NotesYellowContainer,
    onPrimaryContainer = NotesInk,
    secondary = NotesAmber,
    onSecondary = Color.White,
    secondaryContainer = NotesAmberContainer,
    onSecondaryContainer = NotesInk,
    tertiary = NotesAmberContainer,
    onTertiary = NotesInk,
    background = NotesNeutralLight2,
    onBackground = NotesInk,
    surface = NotesNeutralLight,
    onSurface = NotesInk,
    surfaceVariant = Color(0xFFF3EBD3),
    onSurfaceVariant = NotesInkSoft,
    outline = NotesPaperLine,
    outlineVariant = Color(0xFFEFE6CB),
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is OFF — we want our own warm Notes-y palette to dominate.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
