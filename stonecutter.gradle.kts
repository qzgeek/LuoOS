plugins {
    id("dev.kikugie.stonecutter")
    id("me.modmuss50.mod-publish-plugin") version "0.8.4" apply false
}
stonecutter active "1.21.11"

stonecutter.tasks {
    order("publishMods")
}

val collectReleaseJars by tasks.registering(Sync::class) {
    group = "build"
    description = "Collects all built release jars into build/release-jars."
    into(layout.buildDirectory.dir("release-jars"))
}

gradle.projectsEvaluated {
    val versionProjects = subprojects.filter { !it.path.startsWith(":folia") && it.findProperty("minecraft_version") != null }
    val versionAssemblies = versionProjects.map { it.tasks.named("assemble") }
    val foliaProject = findProject(":folia")
    val foliaVersionProjects = foliaProject?.subprojects.orEmpty()
    val foliaShadowJars = foliaVersionProjects.map { it.tasks.named("shadowJar") }

    collectReleaseJars.configure {
        dependsOn(versionAssemblies)
        dependsOn(foliaShadowJars)

        versionProjects.forEach { versionProject ->
            val releaseJar = if (versionProject.findProperty("minecraft_version")
                    ?.toString()
                    ?.substringBefore('.')
                    ?.toIntOrNull()
                    ?.let { it >= 26 } == true
            ) {
                versionProject.tasks.named("jar")
            } else {
                versionProject.tasks.named("remapJar")
            }

            from(releaseJar.map { it.outputs.files }) {
                include("*.jar")
                exclude("*-sources.jar", "*-dev.jar", "*-all.jar")
            }
        }

        foliaVersionProjects.forEach { versionProject ->
            from(versionProject.tasks.named("shadowJar").map { it.outputs.files }) {
                include("*.jar")
                exclude("*-sources.jar", "*-dev.jar", "*-all.jar")
            }
        }
    }

    tasks.matching { it.name == "assemble" || it.name == "build" }.configureEach {
        finalizedBy(collectReleaseJars)
    }

}
