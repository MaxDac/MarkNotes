plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.maxdac.marknotes.proton"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core"))
    // Experimental Proton Drive SDK: Kotlin bindings over the C# SDK, shipping NativeAOT libraries.
    implementation(libs.proton.drive.sdk)
    implementation(libs.kotlinx.coroutines.android)
}
