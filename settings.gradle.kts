pluginManagement {
    repositories {
        // Google's repository is filtered to the groups it actually serves, so a plugin it does not
        // host resolves from Maven Central rather than costing a failed lookup first.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Resolves a matching JDK automatically, so the build does not depend on whichever Java the
    // machine happens to have on its PATH.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    // Repositories are declared here and only here; a module that adds its own fails the build
    // rather than quietly resolving dependencies from somewhere unreviewed.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Killit"
include(":app")
