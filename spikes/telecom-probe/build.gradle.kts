// AGP 9 compiles Kotlin itself. The Kotlin Android plugin is declared (not applied) only so the
// chosen Kotlin version is on the build classpath.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
