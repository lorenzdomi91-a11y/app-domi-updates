// Copia generata da VENATOR/tools/sync-scraper.sh: modifica i sorgenti nel repository VENATOR.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
rootProject.name = "venator-scraper"
include(":core")
include(":scraper")
