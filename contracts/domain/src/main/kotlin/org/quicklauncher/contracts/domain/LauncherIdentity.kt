package org.quicklauncher.contracts.domain

/** A validated Android application package name. */
@JvmInline
value class PackageName private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val segment = Regex("[A-Za-z][A-Za-z0-9_]*")

        fun parse(value: String): PackageName {
            val segments = value.split('.')
            require(segments.size >= 2 && segments.all(segment::matches)) {
                "Invalid Android package name: $value"
            }
            return PackageName(value)
        }
    }
}

/** A stable Android user/profile serial number. Unlike UserHandle, this value may be persisted. */
@JvmInline
value class ProfileSerial private constructor(val value: Long) {
    override fun toString(): String = value.toString()

    companion object {
        fun of(value: Long): ProfileSerial {
            require(value >= 0) { "Profile serial must not be Android's invalid negative sentinel: $value" }
            return ProfileSerial(value)
        }
    }
}

/** Identifies one installed package within one Android profile. */
data class ProfilePackageIdentity(
    val profile: ProfileSerial,
    val packageName: PackageName,
)
