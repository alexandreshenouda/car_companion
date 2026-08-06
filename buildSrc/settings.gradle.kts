// buildSrc is an independent build, so the root project's
// RepositoriesMode.FAIL_ON_PROJECT_REPOS does not reach it.
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "buildSrc"
