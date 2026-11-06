pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        // NewPipe Extractor (trailers): only that library comes from JitPack.
        maven("https://jitpack.io") { content { includeGroup("com.github.teamnewpipe"); includeGroup("com.github.TeamNewPipe") } }
    }
}
rootProject.name = "GlassLauncher"
include(":app")
