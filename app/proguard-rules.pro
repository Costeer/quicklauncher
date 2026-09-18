# These binary-retained manifests are consumed by downstream KSP aggregation. Contributor
# modules intentionally expose their annotation artifact only at compile time; generated runtime
# registry code has no dependency on the annotation classes themselves.
-dontwarn org.quicklauncher.registry.annotations.ContributionRegistryFragmentManifest
-dontwarn org.quicklauncher.registry.annotations.ContributionRegistryFragmentEntry
-dontwarn org.quicklauncher.registry.annotations.ContributionRegistryFragmentSlot

# protobuf-javalite's generated message info resolves these backing fields by source name.
-keepclassmembers class org.quicklauncher.host.data.preferences.proto.** extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
