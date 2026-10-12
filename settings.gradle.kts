pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Compose Multiplatform pulls androidx artifacts (lifecycle, annotation) that
        // are published only to Google's Maven repository, not to Maven Central.
        google()
    }
}

rootProject.name = "Qualia2FA"

include(":composeApp")
