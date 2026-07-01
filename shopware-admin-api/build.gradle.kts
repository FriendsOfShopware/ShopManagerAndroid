import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure-JVM Shopware 6 Admin API client (criteria DSL, entity repositories, OAuth session
// with refresh-token rotation). No Android or Compose dependencies.
plugins {
    // version-less: the Kotlin plugin is already on the build classpath (AGP built-in
    // Kotlin / the app module's serialization plugin)
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    // api: HttpClient, JsonObject and friends appear in the public surface
    api(libs.ktor.client.okhttp)
    api(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    testImplementation(libs.junit)
    testImplementation(libs.ktor.client.mock)
}
