import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Scraper centrale: `gradle :scraper:installDist` e poi
// scraper/build/install/scraper/bin/scraper <offerte.json> [canali.txt]
plugins {
    application
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("com.example.offerteamazon.scraper.MainKt")
}

dependencies {
    implementation(project(":core"))
}
