pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("com.android.library") version "8.12.1"
        id("org.jetbrains.kotlin.android") version "2.0.21"
        id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    }
}

rootProject.name = "inertia-compose"
include("lib")

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            // from(files("gradle/libs.versions.toml"))
        }
    }
}
