plugins {
    id("java")
    id("java-library")
    kotlin("jvm") version "2.2.0"
    id("fabric-loom") version "1.14-SNAPSHOT" apply false
    id("net.fabricmc.fabric-loom") version "1.16-SNAPSHOT" apply false
    id("com.google.devtools.ksp") version "2.2.0-2.0.2"
    id("dev.kikugie.stonecutter")
    id("me.modmuss50.mod-publish-plugin") version "0.8.4"
}

allprojects {
    configurations.all {
        resolutionStrategy {
            force("org.jetbrains.kotlin:kotlin-reflect:2.2.0")
        }
    }
}

val minecraftVersion = property("minecraft_version").toString()
val isMinecraft26Plus = minecraftVersion.substringBefore('.').toIntOrNull()?.let { it >= 26 } == true

if (isMinecraft26Plus) {
    apply(plugin = "net.fabricmc.fabric-loom")
} else {
    apply(plugin = "fabric-loom")
}

val modMetadata = loadHeosmodMetadata(rootProject.file("src/main/java/heos/Heosmod.java"))
val modId = modMetadata.getValue("MOD_ID")
val modName = modMetadata.getValue("MOD_NAME")
val baseVersion = modMetadata.getValue("MOD_VERSION")
val modDescription = modMetadata.getValue("MOD_DESCRIPTION")
val modAuthor = modMetadata.getValue("MOD_AUTHOR")
val modLicense = modMetadata.getValue("MOD_LICENSE")
val modHomepage = modMetadata.getValue("MOD_HOMEPAGE")
val modSources = modMetadata.getValue("MOD_SOURCES")
val modIssues = modMetadata.getValue("MOD_ISSUES")
val dynamicVersion = if (baseVersion.endsWith("-SNAPSHOT")) {
    val lastReleaseTag = runGit("describe", "--tags", "--match", "[0-9]*.[0-9]*.[0-9]*", "--abbrev=0")
    if (lastReleaseTag != null) {
        val countStr = runGit("rev-list", "$lastReleaseTag..HEAD", "--count")
        val count = countStr?.toIntOrNull() ?: 0
        if (count > 0) "$baseVersion.$count" else baseVersion
    } else baseVersion
} else baseVersion
version = dynamicVersion

repositories {
    maven(url = "https://maven.nucleoid.xyz")
    maven(url = "https://oss.sonatype.org/content/repositories/snapshots")
}

val supportedVersionsForArtifact = property("supported_versions")
    .toString()
    .split(",")
    .map(String::trim)
    .filter(String::isNotEmpty)
val minecraftVersionLabel = when (supportedVersionsForArtifact.size) {
    0 -> property("minecraft_version").toString()
    1 -> supportedVersionsForArtifact.first()
    else -> "${supportedVersionsForArtifact.first()}-${supportedVersionsForArtifact.last()}"
}

base.archivesName = "${modId}-fabric-mc$minecraftVersionLabel"
val releaseJarsDirectory = rootProject.layout.buildDirectory.dir("release-jars")

val awFile = when {
    isMinecraft26Plus -> "heos.26.1.2.accesswidener"
    stonecutter.eval(stonecutter.current.version, ">=1.21.11") -> "heos.1.21.11.accesswidener"
    stonecutter.eval(stonecutter.current.version, ">=1.21.5") -> "heos.1.21.5.accesswidener"
    stonecutter.eval(stonecutter.current.version, ">=1.21.2") -> "heos.1.21.2.accesswidener"
    stonecutter.eval(stonecutter.current.version, ">=1.20.3") -> "heos.1.20.3.accesswidener"
    stonecutter.eval(stonecutter.current.version, ">=1.19.4") -> "heos.1.19.4.accesswidener"
    else -> throw GradleException("Access widener is missing for Minecraft ${stonecutter.current.version})")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(if (isMinecraft26Plus) 25 else 21))
    }
    sourceCompatibility = if (isMinecraft26Plus) JavaVersion.VERSION_25 else JavaVersion.VERSION_21
    targetCompatibility = if (isMinecraft26Plus) JavaVersion.VERSION_25 else JavaVersion.VERSION_21
}

extensions.configure<net.fabricmc.loom.api.LoomGradleExtensionAPI>("loom") {
    splitEnvironmentSourceSets()
    accessWidenerPath.set(rootProject.file("src/main/resources/accesswidener/$awFile"))
    mods {
        create(modId).apply {
            sourceSet(sourceSets["main"])
            sourceSet(sourceSets["client"])
        }
    }

    runConfigs.all {
        ideConfigGenerated(true)
        runDir("run")
        
        vmArgs(
            "--add-opens", "java.base/sun.misc=ALL-UNNAMED",
            "--add-opens", "java.base/java.lang=ALL-UNNAMED"
        )
    }
}

afterEvaluate {
    tasks.findByName("runGameTest")?.apply {
        enabled = false
        usesService(semaphore)
    }
}

dependencies {
    // Fabric
    add("minecraft", "com.mojang:minecraft:${property("minecraft_version")}")
    if (!isMinecraft26Plus) {
        add("mappings", project.extensions.getByType<net.fabricmc.loom.api.LoomGradleExtensionAPI>().officialMojangMappings())
    }

    if (isMinecraft26Plus) {
        add("implementation", "net.fabricmc:fabric-loader:${property("loader_version")}")
        add("implementation", "net.fabricmc.fabric-api:fabric-api:${property("fabric_version")}")
    } else {
        add("modImplementation", "net.fabricmc:fabric-loader:${property("loader_version")}")
        add("modImplementation", "net.fabricmc.fabric-api:fabric-api:${property("fabric_version")}")
    }
    
    // Mixin Extras for advanced mixin features
    if (isMinecraft26Plus) {
        add("implementation", "io.github.llamalad7:mixinextras-fabric:0.5.4")
    } else {
        add("implementation", "io.github.llamalad7:mixinextras-fabric:0.4.1")
        add("include", "io.github.llamalad7:mixinextras-fabric:0.4.1")
    }

    add("implementation", "org.xerial:sqlite-jdbc:${property("sqlite_version")}")
    add("include", "org.xerial:sqlite-jdbc:${property("sqlite_version")}")
}

tasks.jar {
    from("LICENSE")
    outputs.upToDateWhen { false }
}

tasks.findByName("remapJar")?.let { remapJarTask ->
    remapJarTask.outputs.upToDateWhen { false }
}

val releaseJarTask = if (isMinecraft26Plus) {
    tasks.named("jar")
} else {
    tasks.named("remapJar")
}

val collectFabricJar by tasks.registering(Copy::class) {
    group = "build"
    description = "Collects this Fabric release jar into root build/release-jars."
    dependsOn(releaseJarTask)
    into(releaseJarsDirectory)
    from(releaseJarTask.map { it.outputs.files }) {
        include("*.jar")
        exclude("*-sources.jar", "*-dev.jar", "*-all.jar")
    }
}

tasks.named("assemble") {
    finalizedBy(collectFabricJar)
}

tasks.named("build") {
    finalizedBy(collectFabricJar)
}

tasks.withType<ProcessResources>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

tasks.processResources {
    filesMatching("fabric.mod.json") {
        expand(
            mapOf(
                "version" to dynamicVersion,
                "mod_id" to modId,
                "mod_name" to modName,
                "mod_description" to modDescription,
                "mod_author" to modAuthor,
                "mod_license" to modLicense,
                "mod_homepage" to modHomepage,
                "mod_sources" to modSources,
                "mod_issues" to modIssues,
                "supported_minecraft_version" to project.property("supported_minecraft_version")
            )
        )
        filter {
            it.replace("accesswidener/heos.1.21.2.accesswidener", "accesswidener/$awFile")
        }
        filter {
            if (stonecutter.eval(stonecutter.current.version, ">=1.21.2")) {
                it
            } else {
                it.replace("\"heos.mixins.json\",", "\"heos.mixins.json\"")
                    .replace("    \"heos.bugfix.ghost_pearl.mixins.json\"", "")
            }
        }
    }

    filesMatching(listOf("heos.mixins.json", "heos.bugfix.ghost_pearl.mixins.json")) {
        filter {
            it.replace("\${refmap}", "${base.archivesName.get()}-refmap.json")
        }
    }
}

tasks.processTestResources {
    tasks.findByName("kspGametestKotlin")?.let { dependsOn(it) }
}

tasks.findByName("processGametestResources")?.let { found ->
    val task = found as Copy
    task.duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    tasks.findByName("kspTestKotlin")?.let { task.dependsOn(it) }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(if (isMinecraft26Plus) 25 else 21)
}

java {
    withSourcesJar()
}

publishMods {
    val modrinthToken = System.getenv("MODRINTH_TOKEN") ?: ""
    val curseforgeToken = System.getenv("CURSEFORGE_TOKEN") ?: ""

    file = if (isMinecraft26Plus) {
        tasks.jar.flatMap { it.archiveFile }.map { it.asFile }
    } else {
        tasks.named("remapJar").map { it.outputs.files.singleFile }
    }
    dryRun.set(true) // Disabled by default - set your own project IDs

    displayName.set("${modName} ${property("display_name")} $dynamicVersion")
    version.set(dynamicVersion)

    changelog.set(rootProject.file("RELEASE_NOTE.md").takeIf { it.isFile }?.readText() ?: "")
    type.set(STABLE)
    modLoaders.add("fabric")

    val targets = property("supported_versions").toString().split(",")

    modrinth {
        projectId = "YOUR_MODRINTH_PROJECT_ID" // Replace with your project ID
        accessToken = modrinthToken

        targets.forEach(minecraftVersions::add)
        requires("fabric-api")
    }

    curseforge {
        projectId = "YOUR_CURSEFORGE_PROJECT_ID" // Replace with your project ID
        accessToken = curseforgeToken

        targets.forEach(minecraftVersions::add)
        requires("fabric-api")
    }
}

private abstract class ServerRunSemaphore : BuildService<BuildServiceParameters.None>

private val semaphore = gradle.sharedServices.registerIfAbsent("semaphore", ServerRunSemaphore::class.java) {
    maxParallelUsages.set(1)
}

fun runGit(vararg args: String): String? = try {
    val proc = ProcessBuilder("git", *args).redirectErrorStream(true).start()
    proc.waitFor(5, TimeUnit.SECONDS)
    if (proc.exitValue() == 0) proc.inputStream.bufferedReader().readText().trim().takeIf { it.isNotBlank() } else null
} catch (_: Exception) { null }

fun loadHeosmodMetadata(file: File): Map<String, String> {
    val pattern = Regex("""public\s+static\s+final\s+String\s+(\w+)\s*=\s*"((?:\\.|[^"])*)";""")
    return pattern.findAll(file.readText()).associate { match ->
        val key = match.groupValues[1]
        val value = match.groupValues[2]
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\\\", "\\")
        key to value
    }
}
