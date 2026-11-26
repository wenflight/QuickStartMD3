package com.quickstart.md3.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.quickstart.md3.data.AppSettings
import com.quickstart.md3.data.ThemeMode

data class SeedPalette(
    val name: String,
    val primaryLight: Color,
    val primaryDark: Color,
    val secondaryLight: Color,
    val secondaryDark: Color,
    val tertiaryLight: Color,
    val tertiaryDark: Color,
    val containerLight: Color,
    val containerDark: Color
)

val SeedPalettes = listOf(
    SeedPalette(
        name = "紫藤蓝 (MD3 默认)",
        primaryLight = Color(0xFF6750A4),
        primaryDark = Color(0xFFD0BCFF),
        secondaryLight = Color(0xFF625B71),
        secondaryDark = Color(0xFFCCC2DC),
        tertiaryLight = Color(0xFF7D5260),
        tertiaryDark = Color(0xFFEFB8C8),
        containerLight = Color(0xFFEADDFF),
        containerDark = Color(0xFF4F378B)
    ),
    SeedPalette(
        name = "晴空青",
        primaryLight = Color(0xFF0061A4),
        primaryDark = Color(0xFF9ECAFF),
        secondaryLight = Color(0xFF535F70),
        secondaryDark = Color(0xFFBBC7DB),
        tertiaryLight = Color(0xFF6B5778),
        tertiaryDark = Color(0xFFD6BEE4),
        containerLight = Color(0xFFD1E4FF),
        containerDark = Color(0xFF00497D)
    ),
    SeedPalette(
        name = "森林绿",
        primaryLight = Color(0xFF146C2E),
        primaryDark = Color(0xFF81DA8E),
        secondaryLight = Color(0xFF526350),
        secondaryDark = Color(0xFFB8CCB5),
        tertiaryLight = Color(0xFF39656B),
        tertiaryDark = Color(0xFFA1CED5),
        containerLight = Color(0xFF9DF7A8),
        containerDark = Color(0xFF00531F)
    ),
    SeedPalette(
        name = "琥珀橙",
        primaryLight = Color(0xFF984715),
        primaryDark = Color(0xFFFFB690),
        secondaryLight = Color(0xFF765848),
        secondaryDark = Color(0xFFE6BEAA),
        tertiaryLight = Color(0xFF646032),
        tertiaryDark = Color(0xFFCFC890),
        containerLight = Color(0xFFFFDBCA),
        containerDark = Color(0xFF7A2F00)
    ),
    SeedPalette(
        name = "绯红玫",
        primaryLight = Color(0xFFB3261E),
        primaryDark = Color(0xFFFFB4AB),
        secondaryLight = Color(0xFF775652),
        secondaryDark = Color(0xFFE7BDB7),
        tertiaryLight = Color(0xFF715B2E),
        tertiaryDark = Color(0xFFE0C38C),
        containerLight = Color(0xFFFFDAD6),
        containerDark = Color(0xFF93000A)
    ),
    SeedPalette(
        name = "青黛蓝",
        primaryLight = Color(0xFF006874),
        primaryDark = Color(0xFF4FD8EB),
        secondaryLight = Color(0xFF4A6267),
        secondaryDark = Color(0xFFB1CBD0),
        tertiaryLight = Color(0xFF525E7D),
        tertiaryDark = Color(0xFFBAC6EA),
        containerLight = Color(0xFF97F0FF),
        containerDark = Color(0xFF004F58)
    )
)

private val QuickStartShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun QuickStartMD3Theme(
    settings: AppSettings,
    content: @Composable () -> Unit
) {
    val isDark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current
    val colorScheme: ColorScheme = if (
        settings.useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    ) {
        if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        val p = SeedPalettes[settings.seedColorIndex.coerceIn(0, SeedPalettes.lastIndex)]
        if (isDark) {
            darkColorScheme(
                primary = p.primaryDark,
                onPrimary = Color(0xFF1C1B1F),
                primaryContainer = p.containerDark,
                onPrimaryContainer = p.containerLight,
                secondary = p.secondaryDark,
                tertiary = p.tertiaryDark
            )
        } else {
            lightColorScheme(
                primary = p.primaryLight,
                onPrimary = Color.White,
                primaryContainer = p.containerLight,
                onPrimaryContainer = p.containerDark,
                secondary = p.secondaryLight,
                tertiary = p.tertiaryLight
            )
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        shapes = QuickStartShapes,
        content = content
    )
}
