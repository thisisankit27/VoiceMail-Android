// Standalone build for the Phase 0 throwaway probe. It is deliberately not part of the product
// build: nothing in the app will ever depend on it. See docs/DEVELOPMENT_ROADMAP.md (PR-02..PR-05).
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "telecom-probe"
include(":app")
