package art.graphite.vpn.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Dark = darkColorScheme(
    primary = Color(0xFFE3E2DE), onPrimary = Color(0xFF161718),
    primaryContainer = Color(0xFF454648), onPrimaryContainer = Color(0xFFE3E2DE),
    secondaryContainer = Color(0xFF454648), onSecondaryContainer = Color(0xFFE3E2DE),
    onSecondary = Color(0xFF161718), onTertiary = Color(0xFF161718),
    tertiaryContainer = Color(0xFF303133), onTertiaryContainer = Color(0xFFE3E2DE),
    errorContainer = Color(0xFF454648), onErrorContainer = Color(0xFFE3E2DE), onError = Color(0xFF161718),
    background = Color(0xFF141516), onBackground = Color(0xFFE3E2DE),
    surface = Color(0xFF202122), onSurface = Color(0xFFE3E2DE),
    surfaceVariant = Color(0xFF303133), onSurfaceVariant = Color(0xFFB7B7B1),
    surfaceTint = Color(0xFFE3E2DE), surfaceContainer = Color(0xFF202122), surfaceContainerLow = Color(0xFF1B1C1D), surfaceContainerHigh = Color(0xFF292A2B), surfaceContainerHighest = Color(0xFF303133), surfaceContainerLowest = Color(0xFF101112), tertiary = Color(0xFFB7B7B1), error = Color(0xFFE3E2DE),
    outline = Color(0xFF797B78), secondary = Color(0xFFB7B7B1)
)
private val Light = lightColorScheme(
    primary = Color(0xFF292A29), onPrimary = Color(0xFFF5F2EA),
    primaryContainer = Color(0xFFD7D4CC), onPrimaryContainer = Color(0xFF242524),
    secondaryContainer = Color(0xFFD7D4CC), onSecondaryContainer = Color(0xFF242524),
    onSecondary = Color(0xFFF5F2EA), onTertiary = Color(0xFFF5F2EA),
    tertiaryContainer = Color(0xFFD7D4CC), onTertiaryContainer = Color(0xFF242524),
    errorContainer = Color(0xFFD7D4CC), onErrorContainer = Color(0xFF242524), onError = Color(0xFFF5F2EA),
    background = Color(0xFFF5F2EA), onBackground = Color(0xFF242524),
    surface = Color(0xFFE9E6DE), onSurface = Color(0xFF242524),
    surfaceVariant = Color(0xFFD7D4CC), onSurfaceVariant = Color(0xFF555650),
    surfaceTint = Color(0xFF292A29), surfaceContainer = Color(0xFFE9E6DE), surfaceContainerLow = Color(0xFFF0EDE5), surfaceContainerHigh = Color(0xFFDFDCD4), surfaceContainerHighest = Color(0xFFD7D4CC), surfaceContainerLowest = Color(0xFFFAF7F0), tertiary = Color(0xFF555650), error = Color(0xFF292A29),
    outline = Color(0xFF777971), secondary = Color(0xFF555650)
)
@Composable fun GraphiteTheme(light: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (light) Light else Dark, typography = Typography(
        headlineLarge = TextStyle(fontFamily=FontFamily.SansSerif, fontWeight=FontWeight.SemiBold, fontSize=30.sp, lineHeight=36.sp),
        headlineSmall = TextStyle(fontWeight=FontWeight.SemiBold, fontSize=24.sp, lineHeight=30.sp),
        titleLarge = TextStyle(fontWeight=FontWeight.SemiBold, fontSize=22.sp, lineHeight=28.sp),
        titleMedium = TextStyle(fontWeight=FontWeight.SemiBold, fontSize=16.sp, lineHeight=22.sp),
        bodyLarge = TextStyle(fontSize=16.sp, lineHeight=23.sp),
        bodyMedium = TextStyle(fontSize=14.sp, lineHeight=20.sp),
        labelSmall = TextStyle(fontSize=11.sp, lineHeight=15.sp),
        labelMedium = TextStyle(fontSize=12.sp, lineHeight=16.sp)
    ), content=content)
}
