import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Codice puro Kotlin/JVM condiviso tra l'app Android e lo scraper su GitHub Actions.
plugins {
    `java-library`
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

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.jsoup)
    api(libs.gson)

    testImplementation(libs.junit)
}
