// AGP 9 compiles Kotlin itself. The Kotlin plugins are declared (not applied) so the chosen
// Kotlin version is on the build classpath.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
