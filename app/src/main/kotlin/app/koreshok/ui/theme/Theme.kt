package app.koreshok.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Корешок's own palette, taken from the logo: bottle-green cloth, cream paper, amber and
 * terracotta spines. It is used instead of the wallpaper colors so the app looks like itself.
 */
object Brand {
    val Green = Color(0xFF1E3A34)
    val GreenLight = Color(0xFF2F5A50)
    val Cream = Color(0xFFF6EFE3)
    val Paper = Color(0xFFFBF8F2)
    val Amber = Color(0xFFD9A066)
    val Terracotta = Color(0xFFB4583A)
    val Ink = Color(0xFF1F1B16)
}

private val Light = lightColorScheme(
    primary = Brand.Green,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E6DF),
    onPrimaryContainer = Color(0xFF0E2520),
    secondary = Brand.Terracotta,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF5DCCF),
    onSecondaryContainer = Color(0xFF3D1609),
    tertiary = Color(0xFF8A5A1F),
    tertiaryContainer = Color(0xFFF8E1C2),
    onTertiaryContainer = Color(0xFF2E1A00),
    background = Brand.Paper,
    onBackground = Brand.Ink,
    surface = Brand.Paper,
    onSurface = Brand.Ink,
    surfaceVariant = Color(0xFFEDE5D8),
    onSurfaceVariant = Color(0xFF6B6157),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F2EA),
    surfaceContainer = Color(0xFFF2EBE0),
    surfaceContainerHigh = Color(0xFFECE4D8),
    surfaceContainerHighest = Color(0xFFE6DDD0),
    outline = Color(0xFFA89D90),
    outlineVariant = Color(0xFFDCD2C4),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9FD0C1),
    onPrimary = Color(0xFF00382E),
    primaryContainer = Brand.GreenLight,
    onPrimaryContainer = Color(0xFFD5E6DF),
    secondary = Color(0xFFF0B59C),
    onSecondary = Color(0xFF5A1E08),
    secondaryContainer = Color(0xFF6E2F19),
    onSecondaryContainer = Color(0xFFF5DCCF),
    tertiary = Brand.Amber,
    tertiaryContainer = Color(0xFF5B3D10),
    onTertiaryContainer = Color(0xFFF8E1C2),
    background = Color(0xFF14130F),
    onBackground = Color(0xFFE9E2D6),
    surface = Color(0xFF14130F),
    onSurface = Color(0xFFE9E2D6),
    surfaceVariant = Color(0xFF3A362F),
    onSurfaceVariant = Color(0xFFBDB3A5),
    surfaceContainerLowest = Color(0xFF0F0E0B),
    surfaceContainerLow = Color(0xFF1C1A16),
    surfaceContainer = Color(0xFF211F1A),
    surfaceContainerHigh = Color(0xFF2B2924),
    surfaceContainerHighest = Color(0xFF36332D),
    outline = Color(0xFF877D70),
    outlineVariant = Color(0xFF4A453D),
)

/** Serif for titles gives the bookish voice; body text stays in the system sans for legibility. */
private val Serif = FontFamily.Serif
private val base = Typography()
val KoreshokTypography = Typography(
    displaySmall = base.displaySmall.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
    headlineLarge = base.headlineLarge.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
    headlineMedium = base.headlineMedium.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 30.sp),
    headlineSmall = base.headlineSmall.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

/** Book titles under covers: serif, tight, so two lines fit under a narrow cover. */
val BookTitleStyle = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 17.sp)

private val KoreshokShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun KoreshokTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = KoreshokTypography,
        shapes = KoreshokShapes,
        content = content,
    )
}
