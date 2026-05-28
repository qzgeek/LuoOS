import java.util.Properties

pluginManagement {
    repositories {
        maven ("https://maven.fabricmc.net/")
        maven("https://maven.kikugie.dev/releases")
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.7.10"
}

stonecutter {
    create(rootProject) {
        versions("1.20", "1.20.5", "1.21", "1.21.1", "1.21.2", "1.21.5", "1.21.6", "1.21.11", "26.1", "26.1.1", "26.1.2")
        vcsVersion = "1.21.11"
    }
}

val fabricVersions = listOf("1.20", "1.20.5", "1.21", "1.21.1", "1.21.2", "1.21.5", "1.21.6", "1.21.11", "26.1", "26.1.1", "26.1.2")
val versionPlaceholder = "[VERSION" + "ED]"
gradle.beforeProject {
    if (name in fabricVersions) {
        val propertiesFile = rootDir.resolve("versions/$name/gradle.properties")
        if (propertiesFile.isFile) {
            Properties().apply {
                propertiesFile.inputStream().use { load(it) }
            }.forEach { key, value ->
                if (key is String && value is String && value != versionPlaceholder) {
                    extensions.extraProperties[key] = value
                }
            }
        }
    }
}

val foliaVersions = listOf("1.20.1", "1.20.2", "1.20.4", "1.20.6", "1.21.4", "1.21.5", "1.21.6", "1.21.8", "1.21.11", "26.1", "26.1.1", "26.1.2")
foliaVersions.forEach { version ->
    include("folia:$version")
    project(":folia:$version").projectDir = file("folia/versions/$version")
}
