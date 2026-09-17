package org.quicklauncher.host.runtime.theme

import java.util.Collections
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.quicklauncher.contracts.domain.ArgbColor
import org.quicklauncher.contracts.domain.CapabilityId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.contracts.domain.ThemeProfileId
import org.quicklauncher.contracts.ui.ResolvedColorRoles
import org.quicklauncher.contracts.ui.ResolvedFontRole
import org.quicklauncher.contracts.ui.ResolvedFontRoles
import org.quicklauncher.contracts.ui.ResolvedFontSource
import org.quicklauncher.host.data.store.DestinationBackgroundRecord
import org.quicklauncher.host.data.store.ThemeProfileRecord

const val THEME_PROFILE_SCHEMA_VERSION: Int = 1
const val DESTINATION_BACKGROUND_SCHEMA_VERSION: Int = 1

object BuiltInThemeProfiles {
    val SystemId: ThemeProfileId = ThemeProfileId.parse("org.quicklauncher.theme/system")
    val LightId: ThemeProfileId = ThemeProfileId.parse("org.quicklauncher.theme/light")
    val DarkId: ThemeProfileId = ThemeProfileId.parse("org.quicklauncher.theme/dark")

    val System: ThemeProfile = ThemeProfile(
        id = SystemId,
        name = "System",
        mode = ThemeModePolicy.FOLLOW_SYSTEM,
        palette = PaletteDefinition.BuiltIn,
    )
    val Light: ThemeProfile = ThemeProfile(
        id = LightId,
        name = "Light",
        mode = ThemeModePolicy.LIGHT,
        palette = PaletteDefinition.BuiltIn,
    )
    val Dark: ThemeProfile = ThemeProfile(
        id = DarkId,
        name = "Dark",
        mode = ThemeModePolicy.DARK,
        palette = PaletteDefinition.BuiltIn,
    )
    val All: List<ThemeProfile> = listOf(System, Light, Dark)

    fun find(id: ThemeProfileId?): ThemeProfile? = All.firstOrNull { it.id == id }
}

enum class ThemeModePolicy {
    FOLLOW_SYSTEM,
    LIGHT,
    DARK,
}

sealed interface PaletteDefinition {
    data object BuiltIn : PaletteDefinition
    data class MaterialYou(val seed: ArgbColor) : PaletteDefinition
    data class MaterialExpressive(val seed: ArgbColor) : PaletteDefinition
    data class Manual(val colors: ResolvedColorRoles) : PaletteDefinition
}

enum class ThemeFontRole {
    DISPLAY,
    HEADLINE,
    TITLE,
    BODY,
    LABEL,
}

data class FontSelection(
    val assetId: StableKey,
    val weight: Int = 400,
    val italic: Boolean = false,
) {
    init {
        require(weight in 1..1000) { "Font selection weight must be between 1 and 1000" }
    }
}

data class ModuleThemeOverride(
    val contributionId: ContributionId,
    val requiredCapability: CapabilityId,
    val accent: ArgbColor?,
    val font: FontSelection?,
) {
    init {
        require(accent != null || font != null) { "A module theme override must change a bounded value" }
    }
}

enum class IconPackDialect {
    NOVA,
    ADW,
}

data class IconPackSelection(
    val packageName: String,
    val dialect: IconPackDialect,
) {
    init {
        require(PACKAGE_NAME.matches(packageName)) { "Icon-pack package name is malformed" }
    }

    override fun toString(): String = "IconPackSelection(dialect=$dialect, package=redacted)"

    private companion object {
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}

sealed interface BackgroundDefinition {
    data class Solid(val color: ArgbColor) : BackgroundDefinition

    class Gradient(
        colors: Collection<ArgbColor>,
        val angleDegrees: Float,
    ) : BackgroundDefinition {
        val colors: List<ArgbColor> = Collections.unmodifiableList(ArrayList(colors))

        init {
            require(this.colors.size in 2..4) { "A theme gradient requires between two and four colors" }
            require(angleDegrees.isFinite() && angleDegrees in 0f..360f) {
                "Gradient angle must be finite and between 0 and 360 degrees"
            }
        }

        override fun equals(other: Any?): Boolean = other is Gradient &&
            colors == other.colors && angleDegrees == other.angleDegrees

        override fun hashCode(): Int = 31 * colors.hashCode() + angleDegrees.hashCode()
        override fun toString(): String = "Gradient(colors=${colors.size}, angleDegrees=$angleDegrees)"
    }

    data class Image(
        val assetId: StableKey,
        val previewId: StableKey,
        val representativeColor: ArgbColor,
        val scrim: ArgbColor,
        val scrimOpacity: Float,
    ) : BackgroundDefinition {
        init {
            require(scrimOpacity.isFinite() && scrimOpacity in 0f..0.85f) {
                "Background scrim opacity must be finite and between 0 and 0.85"
            }
        }
    }
}

data class ImageDerivedTheme(
    val seed: ArgbColor,
    val previewId: StableKey,
)

class ThemeProfile(
    val id: ThemeProfileId,
    val name: String,
    val version: Int = THEME_PROFILE_SCHEMA_VERSION,
    val mode: ThemeModePolicy,
    val palette: PaletteDefinition,
    fonts: Map<ThemeFontRole, FontSelection> = emptyMap(),
    overrides: Collection<ModuleThemeOverride> = emptyList(),
    val iconPack: IconPackSelection? = null,
    val background: BackgroundDefinition? = null,
    val imageDerived: ImageDerivedTheme? = null,
) {
    val fonts: Map<ThemeFontRole, FontSelection> =
        Collections.unmodifiableMap(LinkedHashMap(fonts.toSortedMap()))
    val overrides: List<ModuleThemeOverride> = Collections.unmodifiableList(
        overrides.sortedBy { it.contributionId.value },
    )

    init {
        require(id.value.length <= 200) { "Theme profile identity exceeds the durable bound" }
        require(name.isNotBlank() && name.length <= 80) { "Theme profile name must contain 1 to 80 characters" }
        require(version == THEME_PROFILE_SCHEMA_VERSION) { "Theme profile version is incompatible" }
        require(this.fonts.size <= ThemeFontRole.entries.size) { "Theme profile has too many font roles" }
        require(this.overrides.size <= MAX_MODULE_OVERRIDES) { "Theme profile has too many module overrides" }
        require(this.overrides.distinctBy { it.contributionId }.size == this.overrides.size) {
            "Theme profile has duplicate module overrides"
        }
        if (imageDerived != null && palette !is PaletteDefinition.MaterialYou &&
            palette !is PaletteDefinition.MaterialExpressive
        ) {
            throw IllegalArgumentException("An image-derived theme requires a generated palette")
        }
    }

    fun resolvedFonts(availableAssets: Set<StableKey>): ResolvedFontRoles {
        fun role(value: ThemeFontRole): ResolvedFontRole {
            val selected = fonts[value]?.takeIf { it.assetId in availableAssets }
                ?: return ResolvedFontRole.System
            return ResolvedFontRole(
                source = ResolvedFontSource.IMPORTED,
                assetToken = selected.assetId,
                weight = selected.weight,
                italic = selected.italic,
            )
        }
        return ResolvedFontRoles(
            display = role(ThemeFontRole.DISPLAY),
            headline = role(ThemeFontRole.HEADLINE),
            title = role(ThemeFontRole.TITLE),
            body = role(ThemeFontRole.BODY),
            label = role(ThemeFontRole.LABEL),
        )
    }

    fun copy(
        id: ThemeProfileId = this.id,
        name: String = this.name,
        mode: ThemeModePolicy = this.mode,
        palette: PaletteDefinition = this.palette,
        fonts: Map<ThemeFontRole, FontSelection> = this.fonts,
        overrides: Collection<ModuleThemeOverride> = this.overrides,
        iconPack: IconPackSelection? = this.iconPack,
        background: BackgroundDefinition? = this.background,
        imageDerived: ImageDerivedTheme? = this.imageDerived,
    ): ThemeProfile = ThemeProfile(
        id, name, version, mode, palette, fonts, overrides, iconPack, background, imageDerived,
    )

    override fun equals(other: Any?): Boolean = other is ThemeProfile &&
        id == other.id && name == other.name && version == other.version && mode == other.mode &&
        palette == other.palette && fonts == other.fonts && overrides == other.overrides &&
        iconPack == other.iconPack && background == other.background && imageDerived == other.imageDerived

    override fun hashCode(): Int = listOf(
        id, name, version, mode, palette, fonts, overrides, iconPack, background, imageDerived,
    ).hashCode()

    override fun toString(): String =
        "ThemeProfile(id=$id, nameLength=${name.length}, version=$version, assets=redacted)"

    companion object {
        const val MAX_MODULE_OVERRIDES: Int = 32
    }
}

sealed interface ThemeProfileDecodeResult {
    data class Valid(val profile: ThemeProfile) : ThemeProfileDecodeResult
    data object Invalid : ThemeProfileDecodeResult
}

object ThemeProfileCodec {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false }

    fun encode(profile: ThemeProfile): ThemeProfileRecord = ThemeProfileRecord(
        id = profile.id,
        name = profile.name,
        schemaVersion = profile.version,
        encoded = json.encodeToString(JsonObject.serializer(), profile.toJson()),
    )

    fun decode(record: ThemeProfileRecord): ThemeProfileDecodeResult {
        if (record.schemaVersion != THEME_PROFILE_SCHEMA_VERSION || record.encoded.length > MAX_ENCODED_CHARS) {
            return ThemeProfileDecodeResult.Invalid
        }
        return try {
            val objectValue = json.parseToJsonElement(record.encoded).jsonObject
            val known = setOf(
                "version", "mode", "palette", "fonts", "overrides", "iconPack", "background", "imageDerived",
            )
            require(objectValue.keys.all { it in known })
            val profile = ThemeProfile(
                id = record.id,
                name = record.name,
                version = objectValue.requiredInt("version"),
                mode = ThemeModePolicy.valueOf(objectValue.requiredString("mode")),
                palette = objectValue.requiredObject("palette").toPalette(),
                fonts = objectValue.optionalObject("fonts")?.toFonts().orEmpty(),
                overrides = objectValue.optionalArray("overrides")?.map { it.jsonObject.toOverride() }.orEmpty(),
                iconPack = objectValue.optionalObject("iconPack")?.toIconPack(),
                background = objectValue.optionalObject("background")?.toBackground(),
                imageDerived = objectValue.optionalObject("imageDerived")?.toImageDerived(),
            )
            ThemeProfileDecodeResult.Valid(profile)
        } catch (_: RuntimeException) {
            ThemeProfileDecodeResult.Invalid
        }
    }

    private const val MAX_ENCODED_CHARS = 256 * 1024
}

sealed interface DestinationBackgroundDecodeResult {
    data class Valid(val background: BackgroundDefinition) : DestinationBackgroundDecodeResult
    data object Invalid : DestinationBackgroundDecodeResult
}

object DestinationBackgroundCodec {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false }

    fun encode(
        destinationId: org.quicklauncher.contracts.domain.DestinationId,
        profileId: ThemeProfileId,
        background: BackgroundDefinition,
    ): DestinationBackgroundRecord = DestinationBackgroundRecord(
        destinationId = destinationId,
        themeProfileId = profileId,
        schemaVersion = DESTINATION_BACKGROUND_SCHEMA_VERSION,
        encoded = json.encodeToString(JsonObject.serializer(), background.toJson()),
    )

    fun decode(record: DestinationBackgroundRecord): DestinationBackgroundDecodeResult {
        if (record.schemaVersion != DESTINATION_BACKGROUND_SCHEMA_VERSION || record.encoded.length > 64 * 1024) {
            return DestinationBackgroundDecodeResult.Invalid
        }
        return try {
            DestinationBackgroundDecodeResult.Valid(
                json.parseToJsonElement(record.encoded).jsonObject.toBackground(),
            )
        } catch (_: RuntimeException) {
            DestinationBackgroundDecodeResult.Invalid
        }
    }
}

private fun ThemeProfile.toJson(): JsonObject = buildJsonObject {
    put("version", version)
    put("mode", mode.name)
    put("palette", palette.toJson())
    put("fonts", buildJsonObject {
        fonts.forEach { (role, selection) ->
            put(role.name, buildJsonObject {
                put("asset", selection.assetId.value)
                put("weight", selection.weight)
                put("italic", selection.italic)
            })
        }
    })
    put("overrides", buildJsonArray {
        overrides.forEach { override ->
            add(buildJsonObject {
                put("contribution", override.contributionId.value)
                put("capability", override.requiredCapability.value)
                put("accent", override.accent?.let { JsonPrimitive(it.hex()) } ?: JsonNull)
                put("font", override.font?.let { font ->
                    buildJsonObject {
                        put("asset", font.assetId.value)
                        put("weight", font.weight)
                        put("italic", font.italic)
                    }
                } ?: JsonNull)
            })
        }
    })
    put("iconPack", iconPack?.let { selection ->
        buildJsonObject {
            put("package", selection.packageName)
            put("dialect", selection.dialect.name)
        }
    } ?: JsonNull)
    put("background", background?.toJson() ?: JsonNull)
    put("imageDerived", imageDerived?.let { derived ->
        buildJsonObject {
            put("seed", derived.seed.hex())
            put("preview", derived.previewId.value)
        }
    } ?: JsonNull)
}

private fun PaletteDefinition.toJson(): JsonObject = buildJsonObject {
    when (this@toJson) {
        PaletteDefinition.BuiltIn -> put("kind", "BUILT_IN")
        is PaletteDefinition.MaterialYou -> {
            put("kind", "MATERIAL_YOU")
            put("seed", seed.hex())
        }
        is PaletteDefinition.MaterialExpressive -> {
            put("kind", "MATERIAL_EXPRESSIVE")
            put("seed", seed.hex())
        }
        is PaletteDefinition.Manual -> {
            put("kind", "MANUAL")
            put("colors", colors.toJson())
        }
    }
}

private fun ResolvedColorRoles.toJson(): JsonObject = buildJsonObject {
    colorRoleEntries(this@toJson).forEach { (name, color) ->
        put(name, requireNotNull(color).hex())
    }
}

private fun BackgroundDefinition.toJson(): JsonObject = buildJsonObject {
    when (this@toJson) {
        is BackgroundDefinition.Solid -> {
            put("kind", "SOLID")
            put("color", color.hex())
        }
        is BackgroundDefinition.Gradient -> {
            put("kind", "GRADIENT")
            put("colors", buildJsonArray { colors.forEach { add(JsonPrimitive(it.hex())) } })
            put("angle", angleDegrees.toDouble())
        }
        is BackgroundDefinition.Image -> {
            put("kind", "IMAGE")
            put("asset", assetId.value)
            put("preview", previewId.value)
            put("representativeColor", representativeColor.hex())
            put("scrim", scrim.hex())
            put("scrimOpacity", scrimOpacity.toDouble())
        }
    }
}

private fun JsonObject.toPalette(): PaletteDefinition {
    require(keys.all { it in setOf("kind", "seed", "colors") })
    return when (requiredString("kind")) {
        "BUILT_IN" -> PaletteDefinition.BuiltIn
        "MATERIAL_YOU" -> PaletteDefinition.MaterialYou(requiredColor("seed"))
        "MATERIAL_EXPRESSIVE" -> PaletteDefinition.MaterialExpressive(requiredColor("seed"))
        "MANUAL" -> PaletteDefinition.Manual(requiredObject("colors").toColors())
        else -> error("Unknown palette kind")
    }
}

private fun JsonObject.toColors(): ResolvedColorRoles {
    val names = colorRoleEntries(null).map { it.first }.toSet()
    require(keys == names)
    fun color(name: String) = requiredColor(name)
    return ResolvedColorRoles(
        color("primary"), color("onPrimary"), color("primaryContainer"), color("onPrimaryContainer"),
        color("secondary"), color("onSecondary"), color("secondaryContainer"), color("onSecondaryContainer"),
        color("tertiary"), color("onTertiary"), color("tertiaryContainer"), color("onTertiaryContainer"),
        color("error"), color("onError"), color("errorContainer"), color("onErrorContainer"),
        color("surface"), color("onSurface"), color("surfaceVariant"), color("onSurfaceVariant"),
        color("outline"), color("outlineVariant"), color("inverseSurface"), color("inverseOnSurface"),
        color("inversePrimary"), color("scrim"),
    )
}

private fun JsonObject.toFonts(): Map<ThemeFontRole, FontSelection> {
    require(size <= ThemeFontRole.entries.size)
    return entries.associate { (key, value) ->
        val role = ThemeFontRole.valueOf(key)
        role to value.jsonObject.toFontSelection()
    }
}

private fun JsonObject.toFontSelection(): FontSelection {
    require(keys == setOf("asset", "weight", "italic"))
    return FontSelection(
        StableKey.parse(requiredString("asset")),
        requiredInt("weight"),
        requiredBoolean("italic"),
    )
}

private fun JsonObject.toOverride(): ModuleThemeOverride {
    require(keys == setOf("contribution", "capability", "accent", "font"))
    return ModuleThemeOverride(
        contributionId = ContributionId.parse(requiredString("contribution")),
        requiredCapability = CapabilityId.parse(requiredString("capability")),
        accent = optionalString("accent")?.parseColor(),
        font = optionalObject("font")?.toFontSelection(),
    )
}

private fun JsonObject.toIconPack(): IconPackSelection {
    require(keys == setOf("package", "dialect"))
    return IconPackSelection(requiredString("package"), IconPackDialect.valueOf(requiredString("dialect")))
}

private fun JsonObject.toBackground(): BackgroundDefinition {
    return when (requiredString("kind")) {
        "SOLID" -> {
            require(keys == setOf("kind", "color"))
            BackgroundDefinition.Solid(requiredColor("color"))
        }
        "GRADIENT" -> {
            require(keys == setOf("kind", "colors", "angle"))
            BackgroundDefinition.Gradient(
                requiredArray("colors").map { it.jsonPrimitive.content.parseColor() },
                requiredDouble("angle").toFloat(),
            )
        }
        "IMAGE" -> {
            require(
                keys == setOf(
                    "kind", "asset", "preview", "representativeColor", "scrim", "scrimOpacity",
                ),
            )
            BackgroundDefinition.Image(
                StableKey.parse(requiredString("asset")),
                StableKey.parse(requiredString("preview")),
                requiredColor("representativeColor"),
                requiredColor("scrim"),
                requiredDouble("scrimOpacity").toFloat(),
            )
        }
        else -> error("Unknown background kind")
    }
}

private fun JsonObject.toImageDerived(): ImageDerivedTheme {
    require(keys == setOf("seed", "preview"))
    return ImageDerivedTheme(requiredColor("seed"), StableKey.parse(requiredString("preview")))
}

private fun colorRoleEntries(colors: ResolvedColorRoles?): List<Pair<String, ArgbColor?>> = listOf(
    "primary" to colors?.primary,
    "onPrimary" to colors?.onPrimary,
    "primaryContainer" to colors?.primaryContainer,
    "onPrimaryContainer" to colors?.onPrimaryContainer,
    "secondary" to colors?.secondary,
    "onSecondary" to colors?.onSecondary,
    "secondaryContainer" to colors?.secondaryContainer,
    "onSecondaryContainer" to colors?.onSecondaryContainer,
    "tertiary" to colors?.tertiary,
    "onTertiary" to colors?.onTertiary,
    "tertiaryContainer" to colors?.tertiaryContainer,
    "onTertiaryContainer" to colors?.onTertiaryContainer,
    "error" to colors?.error,
    "onError" to colors?.onError,
    "errorContainer" to colors?.errorContainer,
    "onErrorContainer" to colors?.onErrorContainer,
    "surface" to colors?.surface,
    "onSurface" to colors?.onSurface,
    "surfaceVariant" to colors?.surfaceVariant,
    "onSurfaceVariant" to colors?.onSurfaceVariant,
    "outline" to colors?.outline,
    "outlineVariant" to colors?.outlineVariant,
    "inverseSurface" to colors?.inverseSurface,
    "inverseOnSurface" to colors?.inverseOnSurface,
    "inversePrimary" to colors?.inversePrimary,
    "scrim" to colors?.scrim,
)

private fun ArgbColor.hex(): String = value.toString(16).padStart(8, '0')
private fun String.parseColor(): ArgbColor {
    require(length == 8 && all { it.isDigit() || it.lowercaseChar() in 'a'..'f' })
    return ArgbColor.of(toLong(16))
}

private fun JsonObject.requiredString(name: String): String =
    get(name)?.jsonPrimitive?.contentOrNull ?: error("Missing string")
private fun JsonObject.optionalString(name: String): String? = when (val value = get(name)) {
    null, JsonNull -> null
    else -> value.jsonPrimitive.contentOrNull ?: error("Invalid string")
}
private fun JsonObject.requiredInt(name: String): Int =
    get(name)?.jsonPrimitive?.intOrNull ?: error("Missing integer")
private fun JsonObject.requiredDouble(name: String): Double =
    get(name)?.jsonPrimitive?.doubleOrNull ?: error("Missing number")
private fun JsonObject.requiredBoolean(name: String): Boolean =
    get(name)?.jsonPrimitive?.booleanOrNull ?: error("Missing boolean")
private fun JsonObject.requiredColor(name: String): ArgbColor = requiredString(name).parseColor()
private fun JsonObject.requiredObject(name: String): JsonObject = get(name)?.jsonObject ?: error("Missing object")
private fun JsonObject.optionalObject(name: String): JsonObject? = when (val value = get(name)) {
    null, JsonNull -> null
    else -> value.jsonObject
}
private fun JsonObject.requiredArray(name: String): JsonArray = get(name)?.jsonArray ?: error("Missing array")
private fun JsonObject.optionalArray(name: String): JsonArray? = when (val value: JsonElement? = get(name)) {
    null, JsonNull -> null
    else -> value.jsonArray
}
