plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Pro-only JVM code on top of the libre :core: Proton SRP/auth client and the OneDrive (Microsoft Graph) store.

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core"))
    api(pro.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(pro.bcrypt)
    // :core's sync API exposes the marknotes-sync Kotlin bindings, which need JNA on the classpath to compile.
    compileOnly(libs.jna)

    testImplementation(libs.jna)
    testRuntimeOnly(libs.marknotes.sync.natives)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(pro.okhttp.mockwebserver)
}
