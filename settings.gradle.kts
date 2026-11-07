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

rootProject.name = "grass-maps"

include(
    ":plugin-api",
    ":plugin-ui-api",
    ":core",
    ":core-services",
    ":map",
    ":regions",
    ":routing",
    ":navigation",
    ":alerts",
    ":places",
    ":tracks",
    ":ui-shell",
    ":control",
    ":devtools",
    ":app",
    ":plugins:example",
    ":plugins:style-standard",
    ":plugins:synthetic-data",
    ":plugins:gpx",
    ":plugins:info-widgets",
)
