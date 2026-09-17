package org.quicklauncher.host.runtime.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.android.material.color.utilities.DynamicScheme
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.SchemeExpressive
import com.google.android.material.color.utilities.SchemeTonalSpot
import java.util.Collections
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.quicklauncher.contracts.domain.ArgbColor
import org.quicklauncher.contracts.domain.CapabilityId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.contracts.domain.ThemeProfileId
import org.quicklauncher.contracts.ui.BackgroundContrast
import org.quicklauncher.contracts.ui.LauncherTheme
import org.quicklauncher.contracts.ui.ResolvedColorRoles
import org.quicklauncher.contracts.ui.ThemeMode

class ThemeResolutionRequest(
    val systemDark: Boolean,
    val textScale: Float,
    val reducedMotion: Boolean,
    val profile: ThemeProfile = BuiltInThemeProfiles.System,
    registeredCapabilities: Map<ContributionId, Set<CapabilityId>> = emptyMap(),
    availableFontAssets: Set<StableKey> = emptySet(),
    val destinationBackground: BackgroundDefinition? = null,
    availableImageAssets: Set<StableKey> = emptySet(),
) {
    val registeredCapabilities: Map<ContributionId, Set<CapabilityId>> = immutableCapabilities(
        registeredCapabilities,
    )
    val availableFontAssets: Set<StableKey> = Collections.unmodifiableSet(
        LinkedHashSet(availableFontAssets),
    )
    val availableImageAssets: Set<StableKey> = Collections.unmodifiableSet(
        LinkedHashSet(availableImageAssets),
    )

    init {
        require(textScale > 0f && textScale.isFinite() && textScale <= 4f) {
            "Theme text scale must be finite and between zero and four"
        }
    }

    fun copy(
        systemDark: Boolean = this.systemDark,
        textScale: Float = this.textScale,
        reducedMotion: Boolean = this.reducedMotion,
        profile: ThemeProfile = this.profile,
        registeredCapabilities: Map<ContributionId, Set<CapabilityId>> = this.registeredCapabilities,
        availableFontAssets: Set<StableKey> = this.availableFontAssets,
        destinationBackground: BackgroundDefinition? = this.destinationBackground,
        availableImageAssets: Set<StableKey> = this.availableImageAssets,
    ): ThemeResolutionRequest = ThemeResolutionRequest(
        systemDark,
        textScale,
        reducedMotion,
        profile,
        registeredCapabilities,
        availableFontAssets,
        destinationBackground,
        availableImageAssets,
    )
}

data class ResolvedBackground(
    val definition: BackgroundDefinition,
    val representativeColor: ArgbColor,
)

class ResolvedLauncherTheme(
    val profileId: ThemeProfileId,
    val theme: LauncherTheme,
    val background: ResolvedBackground,
    val backgroundContrast: BackgroundContrast,
    moduleThemes: Map<ContributionId, LauncherTheme> = emptyMap(),
    val iconPack: IconPackSelection? = null,
) {
    val moduleThemes: Map<ContributionId, LauncherTheme> =
        Collections.unmodifiableMap(LinkedHashMap(moduleThemes))

    fun themeFor(contributionId: ContributionId): LauncherTheme = moduleThemes[contributionId] ?: theme

    constructor(theme: LauncherTheme, backgroundContrast: BackgroundContrast) : this(
        profileId = BuiltInThemeProfiles.SystemId,
        theme = theme,
        background = ResolvedBackground(BackgroundDefinition.Solid(theme.background), theme.background),
        backgroundContrast = backgroundContrast,
    )

    override fun equals(other: Any?): Boolean = other is ResolvedLauncherTheme &&
        profileId == other.profileId && theme == other.theme && background == other.background &&
        backgroundContrast == other.backgroundContrast && moduleThemes == other.moduleThemes &&
        iconPack == other.iconPack

    override fun hashCode(): Int = listOf(
        profileId, theme, background, backgroundContrast, moduleThemes, iconPack,
    ).hashCode()
}

fun interface LauncherThemeResolver {
    fun resolve(request: ThemeResolutionRequest): ResolvedLauncherTheme
}

/** The slice-1 resolver remains the only color construction path for every presentation. */
object BuiltInLauncherThemeResolver : LauncherThemeResolver {
    override fun resolve(request: ThemeResolutionRequest): ResolvedLauncherTheme {
        val profile = request.profile
        val dark = when (profile.mode) {
            ThemeModePolicy.FOLLOW_SYSTEM -> request.systemDark
            ThemeModePolicy.LIGHT -> false
            ThemeModePolicy.DARK -> true
        }
        val colors = when (val palette = profile.palette) {
            PaletteDefinition.BuiltIn -> builtInColors(dark)
            is PaletteDefinition.MaterialYou -> materialColors(palette.seed, dark, expressive = false)
            is PaletteDefinition.MaterialExpressive -> materialColors(palette.seed, dark, expressive = true)
            is PaletteDefinition.Manual -> palette.colors
        }
        validatePalette(colors)
        val mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT
        val requestedBackground =
            request.destinationBackground ?: profile.background ?: BackgroundDefinition.Solid(colors.surface)
        val backgroundDefinition = requestedBackground.takeIf { definition ->
            definition !is BackgroundDefinition.Image ||
                definition.assetId in request.availableImageAssets &&
                definition.previewId in request.availableImageAssets
        } ?: BackgroundDefinition.Solid(colors.surface)
        val background = resolveBackground(backgroundDefinition)
        val contrast = contrastFor(background.representativeColor)
        val base = LauncherTheme(
            mode = mode,
            foreground = if (
                backgroundDefinition is BackgroundDefinition.Solid &&
                backgroundDefinition.color == colors.surface
            ) colors.onSurface else chooseForeground(background.representativeColor),
            background = colors.surface,
            accent = colors.primary,
            textScale = request.textScale,
            reducedMotion = request.reducedMotion,
            colors = colors,
            fonts = profile.resolvedFonts(request.availableFontAssets),
            shapes = org.quicklauncher.contracts.ui.ResolvedShapeRoles.Default,
            spacing = org.quicklauncher.contracts.ui.ResolvedSpacing.Default,
            icons = org.quicklauncher.contracts.ui.ResolvedIconRendering.Default,
            accessibility = org.quicklauncher.contracts.ui.ResolvedAccessibilityValues.Default,
        )
        val moduleThemes = profile.overrides.associate { override ->
            val capabilities = request.registeredCapabilities[override.contributionId].orEmpty()
            require(override.requiredCapability in capabilities) {
                "Theme override target does not register its required capability"
            }
            val overrideFont = override.font?.takeIf { it.assetId in request.availableFontAssets }
            val overridden = base.copy(
                accent = override.accent ?: base.accent,
                colors = override.accent?.let { accent ->
                    require(contrastRatio(accent, base.colors.surface) >= MIN_NON_TEXT_CONTRAST)
                    require(contrastRatio(base.colors.onPrimary, accent) >= MIN_TEXT_CONTRAST)
                    base.colors.copy(primary = accent)
                } ?: base.colors,
                fonts = overrideFont?.let { font ->
                    val role = org.quicklauncher.contracts.ui.ResolvedFontRole(
                        org.quicklauncher.contracts.ui.ResolvedFontSource.IMPORTED,
                        font.assetId,
                        font.weight,
                        font.italic,
                    )
                    base.fonts.copy(body = role)
                } ?: base.fonts,
            )
            override.contributionId to overridden
        }
        return ResolvedLauncherTheme(
            profileId = profile.id,
            theme = base,
            background = background,
            backgroundContrast = contrast,
            moduleThemes = moduleThemes,
            iconPack = profile.iconPack,
        )
    }

    private fun builtInColors(dark: Boolean): ResolvedColorRoles {
        val foreground = ArgbColor.of(if (dark) DARK_FOREGROUND else LIGHT_FOREGROUND)
        val background = ArgbColor.of(if (dark) DARK_BACKGROUND else LIGHT_BACKGROUND)
        val accent = ArgbColor.of(if (dark) DARK_ACCENT else LIGHT_ACCENT)
        return ResolvedColorRoles.basic(foreground, background, accent).copy(
            error = ArgbColor.of(if (dark) 0xffffb4abL else 0xffba1a1aL),
            onError = ArgbColor.of(if (dark) 0xff690005L else 0xffffffffL),
            errorContainer = ArgbColor.of(if (dark) 0xff93000aL else 0xffffdad6L),
            onErrorContainer = ArgbColor.of(if (dark) 0xffffdad6L else 0xff410002L),
        )
    }

    @Suppress("RestrictedApi")
    private fun materialColors(seed: ArgbColor, dark: Boolean, expressive: Boolean): ResolvedColorRoles {
        val scheme: DynamicScheme = if (expressive) {
            SchemeExpressive(Hct.fromInt(seed.value.toInt()), dark, 0.0)
        } else {
            SchemeTonalSpot(Hct.fromInt(seed.value.toInt()), dark, 0.0)
        }
        fun color(value: Int) = ArgbColor.of(value.toLong() and 0xffffffffL)
        return ResolvedColorRoles(
            color(scheme.primary), color(scheme.onPrimary),
            color(scheme.primaryContainer), color(scheme.onPrimaryContainer),
            color(scheme.secondary), color(scheme.onSecondary),
            color(scheme.secondaryContainer), color(scheme.onSecondaryContainer),
            color(scheme.tertiary), color(scheme.onTertiary),
            color(scheme.tertiaryContainer), color(scheme.onTertiaryContainer),
            color(scheme.error), color(scheme.onError), color(scheme.errorContainer),
            color(scheme.onErrorContainer), color(scheme.surface), color(scheme.onSurface),
            color(scheme.surfaceVariant), color(scheme.onSurfaceVariant), color(scheme.outline),
            color(scheme.outlineVariant), color(scheme.inverseSurface), color(scheme.inverseOnSurface),
            color(scheme.inversePrimary), color(scheme.scrim),
        )
    }

    private const val DARK_FOREGROUND = 0xfff5f5f5L
    private const val DARK_BACKGROUND = 0xff161616L
    private const val DARK_ACCENT = 0xffb5c4ffL
    private const val LIGHT_FOREGROUND = 0xff161616L
    private const val LIGHT_BACKGROUND = 0xfff5f5f5L
    private const val LIGHT_ACCENT = 0xff2949a3L
}

fun validatePalette(colors: ResolvedColorRoles) {
    val textPairs = listOf(
        colors.onPrimary to colors.primary,
        colors.onPrimaryContainer to colors.primaryContainer,
        colors.onSecondary to colors.secondary,
        colors.onSecondaryContainer to colors.secondaryContainer,
        colors.onTertiary to colors.tertiary,
        colors.onTertiaryContainer to colors.tertiaryContainer,
        colors.onError to colors.error,
        colors.onErrorContainer to colors.errorContainer,
        colors.onSurface to colors.surface,
        colors.onSurfaceVariant to colors.surfaceVariant,
        colors.inverseOnSurface to colors.inverseSurface,
    )
    require(textPairs.all { (foreground, background) ->
        isOpaque(foreground) && isOpaque(background) &&
            contrastRatio(foreground, background) >= MIN_TEXT_CONTRAST
    }) { "Theme palette contains an incomplete, transparent, or low-contrast text pair" }
    require(
        isOpaque(colors.outline) && isOpaque(colors.outlineVariant) && isOpaque(colors.scrim) &&
            contrastRatio(colors.primary, colors.surface) >= MIN_NON_TEXT_CONTRAST,
    ) { "Theme palette contains an incomplete, transparent, or low-contrast non-text role" }
}

fun contrastRatio(first: ArgbColor, second: ArgbColor): Float {
    val firstLuminance = relativeLuminance(first)
    val secondLuminance = relativeLuminance(second)
    return ((max(firstLuminance, secondLuminance) + 0.05) /
        (min(firstLuminance, secondLuminance) + 0.05)).toFloat()
}

private fun resolveBackground(definition: BackgroundDefinition): ResolvedBackground {
    val representative = when (definition) {
        is BackgroundDefinition.Solid -> definition.color
        is BackgroundDefinition.Gradient -> {
            require(definition.colors.all(::isOpaque)) { "Gradient colors must be opaque" }
            val average = average(definition.colors)
            val foreground = chooseForeground(average)
            require(definition.colors.all { contrastRatio(foreground, it) >= MIN_TEXT_CONTRAST }) {
                "Gradient does not have one safe foreground across all stops"
            }
            average
        }
        is BackgroundDefinition.Image -> composite(
            definition.representativeColor,
            definition.scrim,
            definition.scrimOpacity,
        ).also {
            require(isOpaque(definition.representativeColor) && isOpaque(definition.scrim)) {
                "Image background colors must be opaque"
            }
        }
    }
    require(isOpaque(representative)) { "Resolved background must be opaque" }
    return ResolvedBackground(definition, representative)
}

private fun contrastFor(background: ArgbColor): BackgroundContrast {
    val white = ArgbColor.of(0xffffffffL)
    val black = ArgbColor.of(0xff000000L)
    val whiteContrast = contrastRatio(white, background)
    val blackContrast = contrastRatio(black, background)
    val selected = max(whiteContrast, blackContrast)
    return BackgroundContrast(
        selected.coerceIn(1f, 21f),
        selected.coerceIn(1f, 21f),
        whiteContrast >= blackContrast,
    )
}

private fun chooseForeground(background: ArgbColor): ArgbColor =
    if (contrastFor(background).prefersLightForeground) ArgbColor.of(0xffffffffL)
    else ArgbColor.of(0xff000000L)

private fun average(colors: List<ArgbColor>): ArgbColor {
    fun channel(shift: Int): Long = colors.sumOf { (it.value shr shift) and 0xffL } / colors.size
    return ArgbColor.of(0xff000000L or (channel(16) shl 16) or (channel(8) shl 8) or channel(0))
}

private fun composite(base: ArgbColor, overlay: ArgbColor, opacity: Float): ArgbColor {
    fun channel(shift: Int): Long {
        val bottom = ((base.value shr shift) and 0xffL).toFloat()
        val top = ((overlay.value shr shift) and 0xffL).toFloat()
        return (bottom * (1f - opacity) + top * opacity).toLong().coerceIn(0L, 255L)
    }
    return ArgbColor.of(0xff000000L or (channel(16) shl 16) or (channel(8) shl 8) or channel(0))
}

private fun relativeLuminance(color: ArgbColor): Double {
    fun channel(shift: Int): Double {
        val encoded = ((color.value shr shift) and 0xffL) / 255.0
        return if (encoded <= 0.04045) encoded / 12.92 else ((encoded + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
}

private fun isOpaque(color: ArgbColor): Boolean = (color.value ushr 24) == 0xffL

private fun immutableCapabilities(
    source: Map<ContributionId, Set<CapabilityId>>,
): Map<ContributionId, Set<CapabilityId>> = Collections.unmodifiableMap(
    source.entries.associateTo(LinkedHashMap()) { (id, capabilities) ->
        id to Collections.unmodifiableSet(LinkedHashSet(capabilities))
    },
)

private const val MIN_TEXT_CONTRAST = 4.5f
private const val MIN_NON_TEXT_CONTRAST = 3f

@Composable
fun LauncherMaterialTheme(
    resolved: ResolvedLauncherTheme,
    typography: Typography? = null,
    content: @Composable () -> Unit,
) {
    val colors = remember(resolved.theme.colors, resolved.theme.mode) {
        val roles = resolved.theme.colors
        val base = if (resolved.theme.mode == ThemeMode.DARK) darkColorScheme() else lightColorScheme()
        base.copy(
            primary = Color(roles.primary.value), onPrimary = Color(roles.onPrimary.value),
            primaryContainer = Color(roles.primaryContainer.value),
            onPrimaryContainer = Color(roles.onPrimaryContainer.value),
            secondary = Color(roles.secondary.value), onSecondary = Color(roles.onSecondary.value),
            secondaryContainer = Color(roles.secondaryContainer.value),
            onSecondaryContainer = Color(roles.onSecondaryContainer.value),
            tertiary = Color(roles.tertiary.value), onTertiary = Color(roles.onTertiary.value),
            tertiaryContainer = Color(roles.tertiaryContainer.value),
            onTertiaryContainer = Color(roles.onTertiaryContainer.value),
            error = Color(roles.error.value), onError = Color(roles.onError.value),
            errorContainer = Color(roles.errorContainer.value),
            onErrorContainer = Color(roles.onErrorContainer.value),
            background = Color(roles.surface.value), onBackground = Color(roles.onSurface.value),
            surface = Color(roles.surface.value), onSurface = Color(roles.onSurface.value),
            surfaceVariant = Color(roles.surfaceVariant.value),
            onSurfaceVariant = Color(roles.onSurfaceVariant.value),
            outline = Color(roles.outline.value), outlineVariant = Color(roles.outlineVariant.value),
            inverseSurface = Color(roles.inverseSurface.value),
            inverseOnSurface = Color(roles.inverseOnSurface.value),
            inversePrimary = Color(roles.inversePrimary.value), scrim = Color(roles.scrim.value),
        )
    }
    val shapes = remember(resolved.theme.shapes) {
        val corners = resolved.theme.shapes.corners
        Shapes(
            extraSmall = RoundedCornerShape(corners.extraSmallDp.dp),
            small = RoundedCornerShape(corners.smallDp.dp),
            medium = RoundedCornerShape(corners.mediumDp.dp),
            large = RoundedCornerShape(corners.largeDp.dp),
            extraLarge = RoundedCornerShape(corners.extraLargeDp.dp),
        )
    }
    MaterialTheme(
        colorScheme = colors,
        typography = typography ?: Typography(),
        shapes = shapes,
        content = content,
    )
}
