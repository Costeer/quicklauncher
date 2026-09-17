package org.quicklauncher.host.runtime.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.ui.EditorMode
import org.quicklauncher.contracts.ui.ThemeMode
import org.quicklauncher.contracts.ui.WindowInfo
import org.quicklauncher.contracts.ui.WindowOrientation
import org.quicklauncher.contracts.domain.ArgbColor
import org.quicklauncher.contracts.domain.CapabilityId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.ThemeProfileId
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.host.runtime.composition.CompositionEnvironment

class LauncherThemeResolverTest {
    @Test
    fun `built-in resolver returns a complete deterministic light theme`() {
        val request = ThemeResolutionRequest(
            systemDark = false,
            textScale = 1.25f,
            reducedMotion = true,
        )

        val first = BuiltInLauncherThemeResolver.resolve(request)
        val second = BuiltInLauncherThemeResolver.resolve(request)

        assertEquals(first, second)
        assertEquals(ThemeMode.LIGHT, first.theme.mode)
        assertEquals(0xff161616L, first.theme.foreground.value)
        assertEquals(0xfff5f5f5L, first.theme.background.value)
        assertEquals(0xff2949a3L, first.theme.accent.value)
        assertEquals(1.25f, first.theme.textScale)
        assertEquals(true, first.theme.reducedMotion)
        assertEquals(false, first.backgroundContrast.prefersLightForeground)
    }

    @Test
    fun `built-in resolver returns the dark contract from the same inputs`() {
        val resolved = BuiltInLauncherThemeResolver.resolve(
            ThemeResolutionRequest(systemDark = true, textScale = 1f, reducedMotion = false),
        )

        assertEquals(ThemeMode.DARK, resolved.theme.mode)
        assertEquals(0xfff5f5f5L, resolved.theme.foreground.value)
        assertEquals(0xff161616L, resolved.theme.background.value)
        assertEquals(0xffb5c4ffL, resolved.theme.accent.value)
        assertEquals(true, resolved.backgroundContrast.prefersLightForeground)
    }

    @Test
    fun `resolution request rejects invalid text scales before publication`() {
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { textScale ->
            assertThrows(IllegalArgumentException::class.java) {
                ThemeResolutionRequest(
                    systemDark = false,
                    textScale = textScale,
                    reducedMotion = false,
                )
            }
        }
    }

    @Test
    fun `composition keeps one resolved theme for every contribution state`() {
        val resolved = BuiltInLauncherThemeResolver.resolve(
            ThemeResolutionRequest(systemDark = false, textScale = 1f, reducedMotion = false),
        )

        val environment = CompositionEnvironment(
            resolvedTheme = resolved,
            window = WindowInfo(360, 800, WindowOrientation.PORTRAIT),
            editorMode = EditorMode.BROWSING,
        )

        assertSame(resolved, environment.resolvedTheme)
        assertSame(resolved.theme, environment.theme)
        assertSame(resolved.backgroundContrast, environment.backgroundContrast)
    }

    @Test
    fun `material you and expressive palettes are deterministic and distinct`() {
        val seed = ArgbColor.of(0xff6750a4L)
        val you = ThemeProfile(
            ThemeProfileId.parse("org.quicklauncher.theme/material-you-test"),
            "Material You test",
            mode = ThemeModePolicy.LIGHT,
            palette = PaletteDefinition.MaterialYou(seed),
        )
        val expressive = you.copy(
            id = ThemeProfileId.parse("org.quicklauncher.theme/expressive-test"),
            palette = PaletteDefinition.MaterialExpressive(seed),
        )
        val request = ThemeResolutionRequest(false, 1f, false, you)

        val first = BuiltInLauncherThemeResolver.resolve(request)
        val second = BuiltInLauncherThemeResolver.resolve(request)
        val expressiveResult = BuiltInLauncherThemeResolver.resolve(request.copy(profile = expressive))

        assertEquals(first, second)
        assertEquals(you.id, first.profileId)
        assertEquals(false, first.theme.colors == expressiveResult.theme.colors)
        validatePalette(first.theme.colors)
        validatePalette(expressiveResult.theme.colors)
    }

    @Test
    fun `manual palette failure falls back only at the controller boundary`() {
        val lowContrast = BuiltInLauncherThemeResolver.resolve(
            ThemeResolutionRequest(false, 1f, false),
        ).theme.colors.copy(
            primary = ArgbColor.of(0xfff5f5f5L),
            onPrimary = ArgbColor.of(0xfff4f4f4L),
        )
        val profile = ThemeProfile(
            ThemeProfileId.parse("org.quicklauncher.theme/invalid-manual"),
            "Invalid manual",
            mode = ThemeModePolicy.LIGHT,
            palette = PaletteDefinition.Manual(lowContrast),
        )

        assertThrows(IllegalArgumentException::class.java) {
            BuiltInLauncherThemeResolver.resolve(ThemeResolutionRequest(false, 1f, false, profile))
        }
    }

    @Test
    fun `module override requires the registered capability and stays bounded`() {
        val module = ContributionId.parse("org.quicklauncher.block/test")
        val capability = CapabilityId.parse("org.quicklauncher.capability/theme-accent")
        val override = ModuleThemeOverride(module, capability, ArgbColor.of(0xff2949a3L), null)
        val profile = ThemeProfile(
            ThemeProfileId.parse("org.quicklauncher.theme/override-test"),
            "Override test",
            mode = ThemeModePolicy.LIGHT,
            palette = PaletteDefinition.BuiltIn,
            overrides = listOf(override),
        )

        assertThrows(IllegalArgumentException::class.java) {
            BuiltInLauncherThemeResolver.resolve(ThemeResolutionRequest(false, 1f, false, profile))
        }
        val resolved = BuiltInLauncherThemeResolver.resolve(
            ThemeResolutionRequest(
                false,
                1f,
                false,
                profile,
                mapOf(module to setOf(capability)),
            ),
        )
        assertEquals(override.accent, resolved.themeFor(module).accent)
    }

    @Test
    fun `profile codec round trips and rejects malformed or incomplete records atomically`() {
        val profile = ThemeProfile(
            ThemeProfileId.parse("org.quicklauncher.theme/round-trip"),
            "Round trip",
            mode = ThemeModePolicy.FOLLOW_SYSTEM,
            palette = PaletteDefinition.MaterialExpressive(ArgbColor.of(0xff006875L)),
            background = BackgroundDefinition.Gradient(
                listOf(ArgbColor.of(0xff001f24L), ArgbColor.of(0xff006875L)),
                45f,
            ),
        )
        val encoded = ThemeProfileCodec.encode(profile)

        assertEquals(ThemeProfileDecodeResult.Valid(profile), ThemeProfileCodec.decode(encoded))
        assertEquals(
            ThemeProfileDecodeResult.Invalid,
            ThemeProfileCodec.decode(encoded.copy(encoded = "{\"version\":1}")),
        )
        assertEquals(
            ThemeProfileDecodeResult.Invalid,
            ThemeProfileCodec.decode(encoded.copy(schemaVersion = 99)),
        )
    }

    @Test
    fun `destination background overrides launcher background and recomputes contrast atomically`() {
        val profile = ThemeProfile(
            ThemeProfileId.parse("org.quicklauncher.theme/background-test"),
            "Background test",
            mode = ThemeModePolicy.LIGHT,
            palette = PaletteDefinition.BuiltIn,
            background = BackgroundDefinition.Gradient(
                listOf(ArgbColor.of(0xff101010L), ArgbColor.of(0xff202020L)),
                120f,
            ),
        )
        val launcher = BuiltInLauncherThemeResolver.resolve(
            ThemeResolutionRequest(false, 1f, false, profile),
        )
        val destination = BuiltInLauncherThemeResolver.resolve(
            ThemeResolutionRequest(
                false,
                1f,
                false,
                profile,
                destinationBackground = BackgroundDefinition.Solid(ArgbColor.of(0xfffefefeL)),
            ),
        )

        assertTrue(launcher.background.definition is BackgroundDefinition.Gradient)
        assertTrue(launcher.backgroundContrast.prefersLightForeground)
        assertEquals(false, destination.backgroundContrast.prefersLightForeground)
        assertEquals(0xff000000L, destination.theme.foreground.value)
    }

    @Test
    fun `missing imported font falls back without exposing an asset token`() {
        val asset = StableKey.parse("font-private-token")
        val profile = ThemeProfile(
            ThemeProfileId.parse("org.quicklauncher.theme/font-test"),
            "Font test",
            mode = ThemeModePolicy.LIGHT,
            palette = PaletteDefinition.BuiltIn,
            fonts = mapOf(ThemeFontRole.BODY to FontSelection(asset, 650, true)),
        )

        val missing = BuiltInLauncherThemeResolver.resolve(
            ThemeResolutionRequest(false, 1f, false, profile),
        )
        val available = BuiltInLauncherThemeResolver.resolve(
            ThemeResolutionRequest(false, 1f, false, profile, availableFontAssets = setOf(asset)),
        )

        assertEquals(null, missing.theme.fonts.body.assetToken)
        assertEquals(asset, available.theme.fonts.body.assetToken)
        assertEquals(650, available.theme.fonts.body.weight)
    }
}
