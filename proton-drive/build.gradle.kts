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
    implementation(project(":pro-core"))
    // Proton Drive SDK: Kotlin bindings over the C# SDK, shipping NativeAOT libraries.
    implementation(pro.proton.drive.sdk)
    implementation(pro.proton.core.domain)
    implementation(pro.proton.core.network.data)
    implementation(pro.proton.core.network.domain)
    implementation(pro.proton.core.crypto.android)
    implementation(pro.proton.core.crypto.common)
    implementation(pro.proton.core.util.kotlin)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
