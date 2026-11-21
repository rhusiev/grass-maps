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
    ":planner",
    ":places",
    ":tracks",
    ":ui-shell",
    ":control",
    ":devtools",
    ":app",
    ":plugins:example",
    ":plugins:style-standard",
    ":plugins:style-touring",
    ":plugins:layers-panel",
    ":plugins:synthetic-data",
    ":plugins:gpx",
    ":plugins:info-widgets",
    ":plugins:dev-tools",
    ":plugins:map-controls",
    ":plugins:point-info",
    ":plugins:osm-places",
    ":plugins:online-search",
    ":plugins:favourites-ui",
    ":plugins:favourites-layer",
    ":plugins:links",
    ":plugins:share",
    ":plugins:coordinates",
    ":plugins:guidance-ui",
    ":plugins:valhalla",
    ":plugins:direct-engine",
    ":plugins:nav-simulator",
    ":plugins:way-alerts",
    ":plugins:proximity-alerts",
    ":plugins:route-warnings",
    ":plugins:track-layer",
    ":plugins:region-outlines",
    ":plugins:route-layer",
    ":plugins:track-library",
    ":plugins:contours",
    ":plugins:route-relations",
    ":plugins:raster",
    ":plugins:downloads-ui",
    ":plugins:profiles-ui",
    ":plugins:plugin-manager",
)
