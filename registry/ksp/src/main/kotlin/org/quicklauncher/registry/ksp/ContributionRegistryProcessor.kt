package org.quicklauncher.registry.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSName
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeArgument
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.KSValueArgument

class ContributionRegistryProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        ContributionRegistryProcessor(
            codeGenerator = environment.codeGenerator,
            logger = environment.logger,
            options = environment.options,
        )
}

class ContributionRegistryProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>,
) : SymbolProcessor {
    private var finished = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (finished) return emptyList()

        val declarations = mutableListOf<KSClassDeclaration>()
        val registrations = mutableListOf<RawRegistration>()
        val aggregations = resolver.getSymbolsWithAnnotation(AGGREGATE_REGISTRY)
            .mapNotNull { symbol ->
                val declaration = symbol as? KSClassDeclaration
                if (declaration == null) {
                    logger.error("[registry.aggregation-target] Registry aggregation requires a class or object", symbol)
                    null
                } else {
                    declaration.findAnnotation(AGGREGATE_REGISTRY)?.let { declaration to it }
                }
            }
            .toList()
        annotationKinds.forEach { (annotationName, kind) ->
            resolver.getSymbolsWithAnnotation(annotationName).forEach { symbol ->
                val declaration = symbol as? KSClassDeclaration
                if (declaration == null) {
                    logger.error("[registry.annotation-target] Registration annotation requires a class or object", symbol)
                } else {
                    declarations += declaration
                    val annotation = declaration.findAnnotation(annotationName)
                    if (annotation != null) {
                        runCatching { declaration.toRawRegistration(annotation, kind, resolver) }
                            .onSuccess(registrations::add)
                            .onFailure { failure ->
                                logger.error(
                                    "[registry.annotation] ${declaration.qualifiedName?.asString()}: " +
                                        "${failure.message ?: "could not read registration metadata"}",
                                    declaration,
                                )
                            }
                    }
                }
            }
        }

        val categories = CategoryCatalog.firstRelease()

        if (declarations.isEmpty() && aggregations.isEmpty()) {
            finished = true
            return emptyList()
        }

        val emitsFragment = OPTION_FRAGMENT_PACKAGE in options || OPTION_FRAGMENT_NAME in options ||
            OPTION_FRAGMENT_ID in options
        when (
            val validation = RegistryValidator.validate(
                categories,
                registrations,
                allowExternalReferences = emitsFragment,
            )
        ) {
            is RegistryValidationResult.Invalid -> {
                val nodes = declarations.associateBy {
                    it.qualifiedName?.asString() ?: it.simpleName.asString()
                }
                validation.issues.forEach { issue ->
                    logger.error(
                        "[${issue.code}] ${issue.targetName}: ${issue.message}",
                        nodes[issue.targetName],
                    )
                }
            }
            is RegistryValidationResult.Valid -> if (declarations.isNotEmpty()) {
                generate(validation, declarations)
            }
        }
        aggregations.forEach { (declaration, annotation) ->
            generateAggregation(declaration, annotation)
        }
        finished = true
        return emptyList()
    }

    private fun generate(
        validation: RegistryValidationResult.Valid,
        declarations: List<KSClassDeclaration>,
    ) {
        val packageName = options[OPTION_FRAGMENT_PACKAGE] ?: options[OPTION_PACKAGE]
            ?: "org.quicklauncher.generated"
        val objectName = options[OPTION_FRAGMENT_NAME] ?: options[OPTION_NAME]
            ?: "GeneratedContributionRegistry"
        val fragmentId = options[OPTION_FRAGMENT_ID]
            ?: "$packageName/${objectName.toKebabCase()}"
        val sourceFiles = linkedSetOf<KSFile>()
        declarations.mapNotNullTo(sourceFiles) { it.containingFile }
        val output = codeGenerator.createNewFile(
            dependencies = Dependencies(aggregating = true, *sourceFiles.toTypedArray()),
            packageName = packageName,
            fileName = objectName,
            extensionName = "kt",
        )
        output.bufferedWriter().use { writer ->
            writer.write(RegistrySourceGenerator.generate(validation, packageName, objectName, fragmentId))
        }
    }

    private fun generateAggregation(
        declaration: KSClassDeclaration,
        annotation: KSAnnotation,
    ) {
        val fragments = annotation.typeDeclarations("fragments").mapNotNull { fragment ->
            if (!fragment.implements(CONTRIBUTION_REGISTRY)) {
                logger.error(
                    "[registry.fragment-contract] ${declaration.qualifiedName?.asString()}: Fragment " +
                        "'${fragment.qualifiedName?.asString()}' must implement ContributionRegistry",
                    declaration,
                )
                return@mapNotNull null
            }
            val manifest = fragment.findAnnotation(FRAGMENT_MANIFEST)
            if (manifest == null) {
                logger.error(
                    "[registry.missing-fragment-manifest] ${declaration.qualifiedName?.asString()}: " +
                        "Fragment '${fragment.qualifiedName?.asString()}' has no generated manifest",
                    declaration,
                )
                null
            } else {
                RawRegistryFragment(
                    declarationName = fragment.qualifiedName?.asString() ?: fragment.simpleName.asString(),
                    fragmentId = manifest.string("fragmentId"),
                    entries = manifest.annotations("entries").map { entry ->
                        RawRegistryFragmentEntry(
                            index = entry.int("index"),
                            contributionId = entry.string("contributionId"),
                            configTypeId = entry.string("configTypeId"),
                            categoryTypeId = entry.string("categoryTypeId"),
                            contractMajor = entry.int("contractMajor"),
                            providedCapabilities = entry.strings("providedCapabilities"),
                            requiredCapabilities = entry.strings("requiredCapabilities"),
                            compatibleSlotTypes = entry.strings("compatibleSlotTypes"),
                            occupiedScrollAxes = entry.strings("occupiedScrollAxes"),
                            childSlots = entry.annotations("childSlots").map { slot ->
                                RawRegistryFragmentSlot(
                                    type = slot.string("type"),
                                    acceptedBlocks = slot.strings("acceptedBlocks"),
                                    requiredCapabilities = slot.strings("requiredCapabilities"),
                                    maximumChildren = slot.int("maximumChildren"),
                                    allowedScrollAxes = slot.strings("allowedScrollAxes"),
                                )
                            },
                            requiredContributions = entry.strings("requiredContributions"),
                        )
                    },
                )
            }
        }
        when (val validation = RegistryAggregationValidator.validate(CategoryCatalog.firstRelease(), fragments)) {
            is AggregationValidationResult.Invalid -> validation.issues.forEach { issue ->
                logger.error(
                    "$issue ${declaration.qualifiedName?.asString()}: cross-fragment registry aggregation failed",
                    declaration,
                )
            }
            is AggregationValidationResult.Valid -> {
                val packageName = annotation.string("packageName")
                val registryName = annotation.string("registryName")
                val sourceFiles = listOfNotNull(declaration.containingFile).toTypedArray()
                val output = codeGenerator.createNewFile(
                    dependencies = Dependencies(aggregating = true, *sourceFiles),
                    packageName = packageName,
                    fileName = registryName,
                    extensionName = "kt",
                )
                output.bufferedWriter().use { writer ->
                    writer.write(AggregatedRegistrySourceGenerator.generate(packageName, registryName, validation))
                }
            }
        }
    }

    private fun KSClassDeclaration.toRawRegistration(
        annotation: KSAnnotation,
        kind: RegistrationKind,
        resolver: Resolver,
    ): RawRegistration {
        val settingsType = annotation.typeName("settings")
        val codecType = annotation.typeDeclaration("codec")
        val testsType = annotation.typeDeclaration("contractTests")
        val codecManifest = codecType?.findAnnotation(CODEC_SPEC)
        val testsManifest = testsType?.findAnnotation(CONTRACT_TEST_SPEC)
        val settings = if (settingsType == MissingNames.SETTINGS) {
            null
        } else {
            val declaration = annotation.typeDeclaration("settings")
            declaration?.toRawSettings(settingsType) ?: RawSettings(settingsType, false, emptyList())
        }
        return RawRegistration(
            targetName = qualifiedName?.asString() ?: simpleName.asString(),
            kind = kind,
            id = annotation.string("id"),
            contractMajor = annotation.int("contractMajor"),
            configTypeId = annotation.string("configTypeId"),
            displayName = annotation.string("displayName"),
            description = annotation.string("description"),
            providedCapabilities = annotation.strings("providedCapabilities"),
            requiredCapabilities = annotation.strings("requiredCapabilities"),
            settings = settings,
            codecName = annotation.typeName("codec"),
            contractTestsName = annotation.typeName("contractTests"),
            targetImplementsContract = implements(kind.contractType),
            targetIsObject = classKind == ClassKind.OBJECT,
            codecImplementsContract = codecType?.implements(CONFIGURATION_CODEC) == true,
            codecIsObject = codecType?.classKind == ClassKind.OBJECT,
            testsImplementContract = testsType?.implements(CONTRACT_TEST_DECLARATION) == true,
            testsAreObject = testsType?.classKind == ClassKind.OBJECT,
            specific = annotation.specific(kind),
            testsImplementCategoryContract = testsType?.implements(kind.contractTestsType) == true,
            targetConfigurationType = configurationTypeFor(resolver, kind.contractType),
            codecConfigurationType = codecType?.configurationTypeFor(resolver, CONFIGURATION_CODEC),
            codecManifestConfigTypeId = codecManifest?.string("configTypeId"),
            codecHasManifest = codecManifest != null,
            contractTestsConfigurationType = testsType?.configurationTypeFor(
                resolver,
                kind.executableContractTestsType,
                kind.contractTestsType,
            ),
            contractTestsCategory = testsManifest?.enumName("category"),
            contractTestsContributionId = testsManifest?.string("contributionId"),
            contractTestsScenarios = testsManifest?.enumNames("scenarios").orEmpty(),
            contractTestsPerformanceMetrics = testsManifest?.enumNames("performanceHooks").orEmpty(),
            contractTestsHaveManifest = testsManifest != null,
        )
    }

    private fun KSClassDeclaration.toRawSettings(name: String): RawSettings {
        val schema = findAnnotation(SETTINGS_SCHEMA)
        return RawSettings(
            declarationName = name,
            hasSchemaAnnotation = schema != null,
            fields = schema?.annotations("fields")?.map { field ->
                RawSetting(
                    key = field.string("key"),
                    label = field.string("label"),
                    kind = field.enumName("kind"),
                    defaultValue = field.string("defaultValue"),
                    minimum = field.int("minimum"),
                    maximum = field.int("maximum"),
                    options = field.strings("options"),
                    allowedFontRoles = field.enumNames("allowedFontRoles"),
                    allowMultiple = field.boolean("allowMultiple"),
                    enabledWhen = field.string("enabledWhen"),
                )
            }.orEmpty(),
        )
    }

    private fun KSAnnotation.specific(kind: RegistrationKind): RawSpecificDescriptor = when (kind) {
        RegistrationKind.LAYOUT -> RawSpecificDescriptor.Layout(annotations("slots").map { it.slot() })
        RegistrationKind.BLOCK -> RawSpecificDescriptor.Block(
            compatibleSlotTypes = strings("compatibleSlotTypes"),
            childSlots = annotations("childSlots").map { it.slot() },
            occupiedScrollAxes = enumNames("occupiedScrollAxes"),
            requiresSearchPresentation = boolean("requiresSearchPresentation"),
        )
        RegistrationKind.SEARCH_PROVIDER -> RawSpecificDescriptor.SearchProvider(
            resultKinds = enumNames("resultKinds"),
            minimumQueryLength = int("minimumQueryLength"),
        )
        RegistrationKind.LAUNCHER_COMMAND -> RawSpecificDescriptor.LauncherCommand(
            contexts = enumNames("contexts"),
            resultKinds = enumNames("resultKinds"),
        )
        RegistrationKind.DESTINATION_TEMPLATE -> RawSpecificDescriptor.DestinationTemplate(
            requiredContributions = strings("requiredContributions"),
            maximumBlocks = int("maximumBlocks"),
        )
    }

    private fun KSAnnotation.slot(): RawSlot = RawSlot(
        id = string("id"),
        type = string("type"),
        acceptedBlocks = strings("acceptedBlocks"),
        requiredCapabilities = strings("requiredCapabilities"),
        maximumChildren = int("maximumChildren"),
        allowedScrollAxes = enumNames("allowedScrollAxes"),
    )

    private fun KSClassDeclaration.implements(qualifiedType: String): Boolean {
        val visited = mutableSetOf<String>()
        fun KSClassDeclaration.walk(): Boolean {
            val name = qualifiedName?.asString() ?: return false
            if (name == qualifiedType) return true
            if (!visited.add(name)) return false
            return superTypes.any { reference ->
                val parent = reference.resolve().declaration as? KSClassDeclaration
                parent?.walk() == true
            }
        }
        return walk()
    }

    private fun KSClassDeclaration.configurationTypeFor(
        resolver: Resolver,
        vararg qualifiedTypes: String,
    ): String? {
        val expected = qualifiedTypes.toSet()
        val visited = mutableSetOf<String>()

        fun concreteTypeName(type: KSType): String? {
            if (type.isError) return null
            val declaration = type.declaration as? KSClassDeclaration ?: return null
            val name = declaration.qualifiedName?.asString() ?: return null
            if (type.arguments.isEmpty()) return name
            val arguments = type.arguments.map { argument ->
                argument.type?.resolve()?.let(::concreteTypeName) ?: return null
            }
            return "$name<${arguments.joinToString()}>"
        }

        fun substitute(
            type: KSType,
            substitutions: Map<KSTypeParameter, KSTypeArgument>,
        ): KSType {
            val parameter = type.declaration as? KSTypeParameter
            if (parameter != null) {
                return substitutions[parameter]?.type?.resolve() ?: type
            }
            if (type.arguments.isEmpty()) return type
            val arguments = type.arguments.map { argument ->
                val argumentType = argument.type?.resolve() ?: return@map argument
                val substituted = substitute(argumentType, substitutions)
                resolver.getTypeArgument(
                    resolver.createKSTypeReferenceFromKSType(substituted),
                    argument.variance,
                )
            }
            return type.replace(arguments)
        }

        fun visit(type: KSType): KSType? {
            val declaration = type.declaration as? KSClassDeclaration ?: return null
            val declarationName = declaration.qualifiedName?.asString() ?: return null
            val key = buildString {
                append(declarationName)
                append('<')
                type.arguments.forEach { argument ->
                    append(argument.type?.resolve()?.let(::concreteTypeName) ?: "*")
                    append(',')
                }
                append('>')
            }
            if (!visited.add(key)) return null
            if (declarationName in expected) return type

            val substitutions = declaration.typeParameters
                .zip(type.arguments)
                .toMap()
            declaration.superTypes.forEach { superType ->
                visit(substitute(superType.resolve(), substitutions))?.let { return it }
            }
            return null
        }

        val contract = superTypes
            .map { it.resolve() }
            .mapNotNull(::visit)
            .firstOrNull()
            ?: return null
        val configuration = contract.arguments.singleOrNull()?.type?.resolve() ?: return null
        return concreteTypeName(configuration)
    }

    private fun KSAnnotated.findAnnotation(name: String): KSAnnotation? = annotations.firstOrNull {
        it.annotationType.resolve().declaration.qualifiedName?.asString() == name
    }

    private fun KSAnnotation.argument(name: String): Any? = arguments
        .firstOrNull { it.name?.asString() == name }
        ?.value

    private fun KSAnnotation.string(name: String): String = argument(name) as String
    private fun KSAnnotation.int(name: String): Int = argument(name) as Int
    private fun KSAnnotation.boolean(name: String): Boolean = argument(name) as Boolean

    private fun KSAnnotation.strings(name: String): List<String> =
        (argument(name) as? List<*>)?.map { it as String }.orEmpty()

    private fun KSAnnotation.annotations(name: String): List<KSAnnotation> =
        (argument(name) as? List<*>)?.map { it as KSAnnotation }.orEmpty()

    private fun KSAnnotation.typeDeclarations(name: String): List<KSClassDeclaration> =
        (argument(name) as? List<*>)
            ?.mapNotNull { (it as? KSType)?.declaration as? KSClassDeclaration }
            .orEmpty()

    private fun KSAnnotation.enumName(name: String): String = enumName(argument(name))

    private fun KSAnnotation.enumNames(name: String): List<String> =
        (argument(name) as? List<*>)?.map(::enumName).orEmpty()

    private fun enumName(value: Any?): String = when (value) {
        is KSName -> value.getShortName()
        is KSType -> value.declaration.simpleName.asString()
        else -> value.toString().substringAfterLast('.')
    }

    private fun KSAnnotation.typeDeclaration(name: String): KSClassDeclaration? =
        (argument(name) as? KSType)?.declaration as? KSClassDeclaration

    private fun KSAnnotation.typeName(name: String): String =
        typeDeclaration(name)?.qualifiedName?.asString()
            ?: error("Annotation argument '$name' is not a class declaration")

    private companion object {
        const val OPTION_PACKAGE = "quicklauncher.registry.package"
        const val OPTION_NAME = "quicklauncher.registry.name"
        const val OPTION_FRAGMENT_PACKAGE = "quicklauncher.registry.fragment.package"
        const val OPTION_FRAGMENT_NAME = "quicklauncher.registry.fragment.name"
        const val OPTION_FRAGMENT_ID = "quicklauncher.registry.fragment.id"
        const val AGGREGATE_REGISTRY =
            "org.quicklauncher.registry.annotations.AggregateContributionRegistry"
        const val FRAGMENT_MANIFEST =
            "org.quicklauncher.registry.annotations.ContributionRegistryFragmentManifest"
        const val SETTINGS_SCHEMA = "org.quicklauncher.registry.annotations.SettingsSchemaSpec"
        const val CODEC_SPEC = "org.quicklauncher.registry.annotations.ConfigurationCodecSpec"
        const val CONTRACT_TEST_SPEC = "org.quicklauncher.registry.annotations.ContractTestSpec"
        const val CONFIGURATION_CODEC =
            "org.quicklauncher.contracts.contribution.ConfigurationCodec"
        const val CONTRACT_TEST_DECLARATION =
            "org.quicklauncher.contracts.contribution.ContributionContractDeclaration"
        const val CONTRIBUTION_REGISTRY =
            "org.quicklauncher.contracts.contribution.ContributionRegistry"

        val annotationKinds = RegistrationKind.entries.associateBy { it.annotationType }
    }
}

private fun String.toKebabCase(): String = replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").lowercase()
