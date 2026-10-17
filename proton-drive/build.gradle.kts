plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.maxdac.marknotes.proton"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    // Proton Drive SDK: Kotlin bindings over the C# SDK, shipping NativeAOT libraries.
    implementation(libs.proton.drive.sdk)
    implementation(libs.proton.core.domain)
    implementation(libs.proton.core.network.data)
    implementation(libs.proton.core.network.domain)
    implementation(libs.proton.core.crypto.android)
    implementation(libs.proton.core.crypto.common)
    implementation(libs.proton.core.util.kotlin)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
