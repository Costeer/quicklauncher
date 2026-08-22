package org.quicklauncher.contracts.domain

private object StableIdentityFormat {
    private val namespaced = Regex(
        "(?:[a-z][a-z0-9]*\\.)+[a-z][a-z0-9]*/[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*",
    )
    private val local = Regex("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*")

    fun requireNamespaced(type: String, value: String): String {
        require(namespaced.matches(value)) {
            "$type must use a lowercase reverse-DNS namespace and local name, for example " +
                "'org.quicklauncher.sample/item'; received '$value'"
        }
        return value
    }

    fun requireLocal(type: String, value: String): String {
        require(local.matches(value)) {
            "$type must start with a lowercase letter and contain only lowercase letters, digits, " +
                "periods, underscores, or hyphens; received '$value'"
        }
        return value
    }
}

@JvmInline
value class ContributionId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun parse(value: String): ContributionId =
            ContributionId(StableIdentityFormat.requireNamespaced("ContributionId", value))
    }
}

@JvmInline
value class ContributionTypeId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun parse(value: String): ContributionTypeId =
            ContributionTypeId(StableIdentityFormat.requireNamespaced("ContributionTypeId", value))
    }
}

@JvmInline
value class CapabilityId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun parse(value: String): CapabilityId =
            CapabilityId(StableIdentityFormat.requireNamespaced("CapabilityId", value))
    }
}

@JvmInline
value class SlotTypeId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun parse(value: String): SlotTypeId =
            SlotTypeId(StableIdentityFormat.requireNamespaced("SlotTypeId", value))
    }
}

@JvmInline
value class ConfigTypeId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun parse(value: String): ConfigTypeId =
            ConfigTypeId(StableIdentityFormat.requireNamespaced("ConfigTypeId", value))
    }
}

@JvmInline
value class ModuleInstanceId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun parse(value: String): ModuleInstanceId =
            ModuleInstanceId(StableIdentityFormat.requireNamespaced("ModuleInstanceId", value))
    }
}

@JvmInline
value class DestinationId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun parse(value: String): DestinationId =
            DestinationId(StableIdentityFormat.requireNamespaced("DestinationId", value))
    }
}

@JvmInline
value class ContentItemId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun parse(value: String): ContentItemId =
            ContentItemId(StableIdentityFormat.requireNamespaced("ContentItemId", value))
    }
}

@JvmInline
value class SearchResultId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun parse(value: String): SearchResultId =
            SearchResultId(StableIdentityFormat.requireNamespaced("SearchResultId", value))
    }
}

@JvmInline
value class SearchActionId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun parse(value: String): SearchActionId =
            SearchActionId(StableIdentityFormat.requireNamespaced("SearchActionId", value))
    }
}

@JvmInline
value class CommandInvocationId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun parse(value: String): CommandInvocationId =
            CommandInvocationId(StableIdentityFormat.requireNamespaced("CommandInvocationId", value))
    }
}

@JvmInline
value class ArgbColor private constructor(val value: Long) {
    companion object {
        fun of(value: Long): ArgbColor {
            require(value in 0..0xffffffffL) {
                "ARGB color must be an unsigned 32-bit value; received $value"
            }
            return ArgbColor(value)
        }
    }
}

@JvmInline
value class StableKey private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun parse(value: String): StableKey =
            StableKey(StableIdentityFormat.requireLocal("StableKey", value))
    }
}

@JvmInline
value class ContractMajor private constructor(val value: Int) {
    override fun toString(): String = value.toString()

    companion object {
        fun of(value: Int): ContractMajor {
            require(value >= 1) { "Contract major must be at least 1; received $value" }
            return ContractMajor(value)
        }
    }
}

@JvmInline
value class SchemaVersion private constructor(val value: Int) {
    override fun toString(): String = value.toString()

    companion object {
        fun of(value: Int): SchemaVersion {
            require(value >= 1) { "Configuration schema version must be at least 1; received $value" }
            return SchemaVersion(value)
        }
    }
}
