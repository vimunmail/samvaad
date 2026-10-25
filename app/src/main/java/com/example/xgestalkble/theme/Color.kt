package com.example.xgestalkble.theme

import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────────────────────
// BOLD CLAYMORPHIC PALETTE
// Soft inflated surfaces + vivid candy accents. Surfaces stay light/clean so the
// chunky dual-shadow "clay" depth reads clearly; accents are pushed saturated and
// bold. Semantic getter names are kept identical so no call-site has to change.
// ─────────────────────────────────────────────────────────────────────────────

// --- LIGHT THEME COLOR TOKENS ---
val LightBg = Color(0xFFF1F0FE)          // Soft periwinkle clay backdrop start
val LightBgEnd = Color(0xFFE7E9FB)       // Soft periwinkle clay backdrop end
val LightCardBg = Color(0xFFFFFFFF)      // Puffy white clay surface
val LightCardBorder = Color(0xFFE6E8FA)  // Barely-there clay edge
val LightElectricCyan = Color(0xFF3D6BFF)// Bold electric indigo-blue
val LightNeonBlue = Color(0xFF2B4ED8)    // Deep bold blue
val LightAuroraGreen = Color(0xFF10C079) // Bold mint clay green
val LightCrimsonRed = Color(0xFFFF5560)  // Bold coral red
val LightSlateText = Color(0xFF6E7390)   // Muted body slate
val LightAmberCalibrate = Color(0xFFFFA51F) // Bold amber
val LightVioletAccent = Color(0xFF7C4DFF)// Bold electric violet
val LightSkyBlue = Color(0xFF1FA8E6)     // Bright sky blue accent
val LightSubtleBg = Color(0xFFEFF1FC)    // Recessed clay inset surface

// --- DARK THEME COLOR TOKENS ---
val DarkBg = Color(0xFF13141D)           // Deep clay night start
val DarkBgEnd = Color(0xFF1B1D2B)        // Deep clay night end
val DarkCardBg = Color(0xFF222637)       // Raised clay surface
val DarkCardBorder = Color(0xFF31374C)   // Soft metallic clay edge
val DarkElectricCyan = Color(0xFF6E9CFF) // Bright electric blue
val DarkNeonBlue = Color(0xFF5685FF)     // Focus highlight blue
val DarkAuroraGreen = Color(0xFF3BE0A0)  // Bright mint green
val DarkCrimsonRed = Color(0xFFFF7682)   // Bright coral
val DarkSlateTextDark = Color(0xFFA3ADC6)// Muted slate text
val DarkAmberCalibrate = Color(0xFFFFC24D) // Bright amber
val DarkVioletAccent = Color(0xFFB18BFF) // Bright violet
val DarkSkyBlue = Color(0xFF74C7FF)      // Bright sky blue accent
val DarkSubtleBg = Color(0xFF2A3043)     // Recessed clay inset surface

// Additional Light/Dark Text Colors
val LightTitleText = Color(0xFF1A1838)   // Deep ink for light titles
val DarkTitleText = Color(0xFFF3F5FC)    // Soft white for dark titles

// --- CLAYMORPHIC DEPTH TOKENS ---
// Dual-shadow recipe: a dark halo bottom-right + a light highlight top-left make
// surfaces look puffed up like clay. Tuned per theme so depth stays believable.
val LightClayShadow = Color(0x33232C66)  // Soft indigo drop shadow
val LightClayHighlight = Color(0xF2FFFFFF) // Bright top-left sheen
val DarkClayShadow = Color(0x80000000)   // Deep shadow on dark clay
val DarkClayHighlight = Color(0x1FFFFFFF) // Subtle white sheen

// --- DYNAMIC SEAMLESS TOGGLE GETTERS ---
val ObsidianBg: Color
    get() = if (ThemeConfig.isDarkTheme) DarkBg else LightBg

val MidnightBg: Color
    get() = if (ThemeConfig.isDarkTheme) DarkBgEnd else LightBgEnd

val CardBg: Color
    get() = if (ThemeConfig.isDarkTheme) DarkCardBg else LightCardBg

val CardBorder: Color
    get() = if (ThemeConfig.isDarkTheme) DarkCardBorder else LightCardBorder

val ElectricCyan: Color
    get() = if (ThemeConfig.isDarkTheme) DarkElectricCyan else LightElectricCyan

val NeonBlue: Color
    get() = if (ThemeConfig.isDarkTheme) DarkNeonBlue else LightNeonBlue

val AuroraGreen: Color
    get() = if (ThemeConfig.isDarkTheme) DarkAuroraGreen else LightAuroraGreen

val CrimsonRed: Color
    get() = if (ThemeConfig.isDarkTheme) DarkCrimsonRed else LightCrimsonRed

val SlateText: Color
    get() = if (ThemeConfig.isDarkTheme) DarkSlateTextDark else LightSlateText

val AmberCalibrate: Color
    get() = if (ThemeConfig.isDarkTheme) DarkAmberCalibrate else LightAmberCalibrate

val DarkSlateText: Color
    get() = if (ThemeConfig.isDarkTheme) DarkTitleText else LightTitleText

val VioletAccent: Color
    get() = if (ThemeConfig.isDarkTheme) DarkVioletAccent else LightVioletAccent

val SkyBlue: Color
    get() = if (ThemeConfig.isDarkTheme) DarkSkyBlue else LightSkyBlue

val SubtleBg: Color
    get() = if (ThemeConfig.isDarkTheme) DarkSubtleBg else LightSubtleBg

// Claymorphic depth getters
val ClayShadow: Color
    get() = if (ThemeConfig.isDarkTheme) DarkClayShadow else LightClayShadow

val ClayHighlight: Color
    get() = if (ThemeConfig.isDarkTheme) DarkClayHighlight else LightClayHighlight

// Basic M3 Palette Overrides
val PrimaryDark: Color
    get() = ElectricCyan

val SecondaryDark: Color
    get() = NeonBlue

val BackgroundDark: Color
    get() = ObsidianBg

val SurfaceDark: Color
    get() = CardBg
