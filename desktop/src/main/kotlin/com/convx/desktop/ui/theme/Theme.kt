/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.theme

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The phone app's Apple-dark palette (AppleTokens / iOS system colours). */
object ConvxColors {
    val AccentRed = Color(0xFFFA2D48)

    val Bg = Color(0xFF121212)
    val BgElevated = Color(0xFF1A1A1A)
    val Card = Color(0xFF1C1C1E)
    val CardSecondary = Color(0xFF2C2C2E)
    val CardTertiary = Color(0xFF3A3A3C)
    val Panel = Color(0xFF0A0A0A)

    val Label = Color.White
    val SecondaryLabel = Color(0x99EBEBF5)
    val TertiaryLabel = Color(0x4DEBEBF5)
    val SystemGray = Color(0xFF8E8E93)
    val Separator = Color(0x29FFFFFF)
    val Fill = Color(0x1FFFFFFF)
    val FillStrong = Color(0x33FFFFFF)
}

/** Corner radii — the phone's four corners plus the side panel's. */
object ConvxShapes {
    val Artwork = RoundedCornerShape(8.dp)
    val Control = RoundedCornerShape(8.dp)
    val Card = RoundedCornerShape(14.dp)
    val CardLarge = RoundedCornerShape(18.dp)
    val Panel = RoundedCornerShape(20.dp)
    val Capsule = RoundedCornerShape(percent = 50)
}

object ConvxFonts {
    private fun bytes(path: String) = ConvxFonts::class.java.classLoader.getResourceAsStream(path)!!.use { it.readBytes() }

    private fun font(path: String, weight: FontWeight) = Font(identity = path, data = bytes(path), weight = weight)

    /** A variable font at the weights the phone sets on it, with any other axes it sets. */
    private fun variable(path: String, vararg axes: FontVariation.Setting): FontFamily {
        val data = bytes(path)
        return FontFamily(
            listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.Bold).map { weight ->
                Font(
                    identity = path,
                    data = data,
                    weight = weight,
                    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight), *axes),
                )
            },
        )
    }

    /** SF Pro Text — the phone's "System Default" app font. */
    val SfPro: FontFamily by lazy {
        FontFamily(
            font("fonts/sf_pro_text_regular.otf", FontWeight.Normal),
            font("fonts/sf_pro_text_medium.otf", FontWeight.Medium),
            font("fonts/sf_pro_text_semibold.otf", FontWeight.SemiBold),
            font("fonts/sf_pro_text_bold.otf", FontWeight.Bold),
        )
    }

    /** Plus Jakarta Sans — the phone's default app font. */
    val PlusJakartaSans: FontFamily by lazy { variable("fonts/plus_jakarta_sans.ttf") }
    val GoogleSans: FontFamily by lazy { variable("fonts/google_sans_flex.ttf", FontVariation.width(100f), FontVariation.Setting("ROND", 100f)) }
    val SansFlex: FontFamily by lazy { variable("fonts/sans_flex.ttf", FontVariation.width(100f), FontVariation.Setting("ROND", 100f)) }
    val Outfit: FontFamily by lazy { variable("fonts/outfit.ttf") }

    /** Chillax — the brand display face (wordmark). */
    val Chillax: FontFamily by lazy {
        FontFamily(
            font("fonts/chillax_light.otf", FontWeight.Light),
            font("fonts/chillax_regular.otf", FontWeight.Normal),
            font("fonts/chillax_medium.otf", FontWeight.Medium),
            font("fonts/chillax_semibold.otf", FontWeight.SemiBold),
            font("fonts/chillax_bold.otf", FontWeight.Bold),
        )
    }
}

/** The phone's app fonts (its AppFont), as its Font page lists them. */
enum class AppFont(val id: String, val label: String, val description: String) {
    System("system", "System Default", "Use the default system typeface."),
    GoogleSans("google_sans", "Google Sans", "Google's signature geometric sans-serif."),
    SansFlex("sans_flex", "Sans Flex", "A modern versatile flexible sans-serif."),
    Outfit("outfit", "Outfit", "A clean and modern geometric sans-serif."),
    PlusJakartaSans("plus_jakarta_sans", "Plus Jakarta Sans", "A premium warm geometric sans-serif typeface."),
    ;

    /** The phone draws "System Default" in SF Pro, not the platform's own face. */
    val family: FontFamily
        get() = when (this) {
            System -> ConvxFonts.SfPro
            GoogleSans -> ConvxFonts.GoogleSans
            SansFlex -> ConvxFonts.SansFlex
            Outfit -> ConvxFonts.Outfit
            PlusJakartaSans -> ConvxFonts.PlusJakartaSans
        }

    companion object {
        /** The phone's default. */
        val Default = PlusJakartaSans

        fun of(id: String) = entries.firstOrNull { it.id == id } ?: System
    }
}

/** Design-pinned text styles from AppleTokens, in the app font; sizes carry their own line heights. */
object ConvxType {
    private class Styles(val family: FontFamily) {
        private fun style(size: TextUnit, line: TextUnit, weight: FontWeight, tracking: TextUnit = 0.sp) = TextStyle(
            fontFamily = family,
            fontSize = size,
            lineHeight = line,
            fontWeight = weight,
            letterSpacing = tracking,
        )

        val LargeTitle = style(34.sp, 41.sp, FontWeight.Bold, (-0.6).sp)
        val Hero = style(56.sp, 60.sp, FontWeight.Bold, (-1.2).sp)
        val Title1 = style(28.sp, 34.sp, FontWeight.Bold, (-0.4).sp)
        val Title2 = style(22.sp, 28.sp, FontWeight.Bold, (-0.3).sp)
        val Title3 = style(20.sp, 25.sp, FontWeight.SemiBold, (-0.2).sp)
        val Headline = style(17.sp, 22.sp, FontWeight.SemiBold, (-0.43).sp)
        val Body = style(15.sp, 20.sp, FontWeight.Normal, (-0.2).sp)
        val BodyStrong = style(15.sp, 20.sp, FontWeight.SemiBold, (-0.2).sp)
        val Subhead = style(13.sp, 18.sp, FontWeight.Normal, (-0.08).sp)
        val SubheadStrong = style(13.sp, 18.sp, FontWeight.SemiBold, (-0.08).sp)
        val Caption = style(12.sp, 16.sp, FontWeight.Normal)
        val CaptionStrong = style(12.sp, 16.sp, FontWeight.SemiBold)
        val Footnote = style(11.sp, 13.sp, FontWeight.Medium, 0.06.sp)
        val Lyrics = style(34.sp, 42.sp, FontWeight.Bold, (-0.5).sp)
        val LyricsCompact = style(22.sp, 29.sp, FontWeight.Bold, (-0.3).sp)
    }

    private val default by lazy { Styles(AppFont.Default.family) }

    // State, so text drawn with these styles follows a new font straight away.
    private var chosen by mutableStateOf<Styles?>(null)
    private val styles get() = chosen ?: default

    /** The app font chosen in Settings, for text that sets its own sizes. */
    val family: FontFamily get() = styles.family

    /** Draws every style in [family] from now on. */
    fun use(family: FontFamily) {
        if (family != styles.family) chosen = Styles(family)
    }

    val LargeTitle get() = styles.LargeTitle
    val Hero get() = styles.Hero
    val Title1 get() = styles.Title1
    val Title2 get() = styles.Title2
    val Title3 get() = styles.Title3
    val Headline get() = styles.Headline
    val Body get() = styles.Body
    val BodyStrong get() = styles.BodyStrong
    val Subhead get() = styles.Subhead
    val SubheadStrong get() = styles.SubheadStrong
    val Caption get() = styles.Caption
    val CaptionStrong get() = styles.CaptionStrong
    val Footnote get() = styles.Footnote
    val Lyrics get() = styles.Lyrics
    val LyricsCompact get() = styles.LyricsCompact
}

/** The user's accent; only interactive highlights take it, like the phone. */
val LocalAccent = compositionLocalOf { ConvxColors.AccentRed }

/** Colours a hero page derived from its artwork; null outside one. */
val LocalHeroPalette = staticCompositionLocalOf<HeroColors?> { null }

@Composable
fun ConvxTheme(accent: Color, content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Color.White,
        onPrimary = Color.Black,
        secondary = ConvxColors.SecondaryLabel,
        background = ConvxColors.Bg,
        onBackground = Color.White,
        surface = ConvxColors.Bg,
        onSurface = Color.White,
        onSurfaceVariant = ConvxColors.SecondaryLabel,
        surfaceVariant = ConvxColors.CardSecondary,
        surfaceContainerLowest = ConvxColors.Bg,
        surfaceContainerLow = ConvxColors.BgElevated,
        surfaceContainer = Color(0xFF252527),
        surfaceContainerHigh = ConvxColors.CardSecondary,
        surfaceContainerHighest = ConvxColors.CardTertiary,
        outline = ConvxColors.Separator,
        outlineVariant = ConvxColors.Separator,
        error = Color(0xFFFF453A),
    )
    val family = ConvxType.family
    val typography = remember(family) {
        val base = TextStyle(fontFamily = family)
        val t = Typography()
        Typography(
            displayLarge = t.displayLarge.merge(base), displayMedium = t.displayMedium.merge(base), displaySmall = t.displaySmall.merge(base),
            headlineLarge = t.headlineLarge.merge(base), headlineMedium = t.headlineMedium.merge(base), headlineSmall = t.headlineSmall.merge(base),
            titleLarge = t.titleLarge.merge(base), titleMedium = t.titleMedium.merge(base), titleSmall = t.titleSmall.merge(base),
            bodyLarge = t.bodyLarge.merge(base), bodyMedium = t.bodyMedium.merge(base), bodySmall = t.bodySmall.merge(base),
            labelLarge = t.labelLarge.merge(base), labelMedium = t.labelMedium.merge(base), labelSmall = t.labelSmall.merge(base),
        )
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = typography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(12.dp),
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(12.dp),
            large = RoundedCornerShape(14.dp),
            extraLarge = RoundedCornerShape(18.dp),
        ),
    ) {
        CompositionLocalProvider(
            LocalAccent provides accent,
            LocalContentColor provides Color.White,
            // A click never blooms: no ripple spreading from the pointer, on anything.
            LocalRippleConfiguration provides null,
            LocalTextSelectionColors provides TextSelectionColors(accent, accent.copy(alpha = 0.35f)),
            LocalScrollbarStyle provides ScrollbarStyle(
                minimalHeight = 32.dp,
                thickness = 8.dp,
                shape = RoundedCornerShape(4.dp),
                hoverDurationMillis = 250,
                unhoverColor = Color.White.copy(alpha = 0.16f),
                hoverColor = Color.White.copy(alpha = 0.38f),
            ),
            content = content,
        )
    }
}
