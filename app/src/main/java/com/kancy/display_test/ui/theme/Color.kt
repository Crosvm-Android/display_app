package com.kancy.display_test.ui.theme

import androidx.compose.ui.graphics.Color

// ── M3 dark baseline tokens (mirrors display_app/ui_redesign.html :root) ──────────
// Purple80-based scheme so the app matches the design mockup even without dynamic color.

val Primary = Color(0xFFD0BCFF)
val OnPrimary = Color(0xFF381E72)
val PrimaryContainer = Color(0xFF4F378B)
val OnPrimaryContainer = Color(0xFFEADDFF)

val Secondary = Color(0xFFCCC2DC)
val OnSecondary = Color(0xFF332D41)
val SecondaryContainer = Color(0xFF4A4458)
val OnSecondaryContainer = Color(0xFFE8DEF8)

val Tertiary = Color(0xFFEFB8C8)
val OnTertiary = Color(0xFF492532)
val TertiaryContainer = Color(0xFF633B48)
val OnTertiaryContainer = Color(0xFFFFD8E4)

val SurfaceDark = Color(0xFF141218)
val OnSurfaceDark = Color(0xFFE6E0E9)
val SurfaceVariantDark = Color(0xFF49454F)
val OnSurfaceVariantDark = Color(0xFFCAC4D0)
val OutlineDark = Color(0xFF938F99)
val OutlineVariantDark = Color(0xFF49454F)

val ErrorDark = Color(0xFFF2B8B5)
val OnErrorDark = Color(0xFF601410)
val ErrorContainerDark = Color(0xFF8C1D18)
val OnErrorContainerDark = Color(0xFFF9DEDC)

// Surface container shades (not all exposed by older ColorScheme APIs, so kept explicit
// and used directly for card/nav backgrounds).
val SurfaceContainerLowest = Color(0xFF0F0D13)
val SurfaceContainerLow = Color(0xFF1D1B20)
val SurfaceContainer = Color(0xFF211F26)
val SurfaceContainerHigh = Color(0xFF2B2930)
val SurfaceContainerHighest = Color(0xFF36343B)

// Semantic status colors (no M3 ColorScheme slot — referenced via AppColors).
val StatusOk = Color(0xFF7FD89C)
val StatusWarn = Color(0xFFF5CE68)
val StatusInfo = Color(0xFF9ECAFF)

/** Glass background for immersive overlays (matches .glass in the mockup). */
val GlassScrim = Color(0xE60F0D13) // ~0.72 alpha over surface-container-lowest

// Legacy names kept so any older references still compile.
val Purple80 = Primary
val PurpleGrey80 = Secondary
val Pink80 = Tertiary
val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)
