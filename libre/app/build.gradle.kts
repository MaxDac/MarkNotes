plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The libre build: Dropbox only, every dependency free software, published on F-Droid and GitHub releases.
android {
    namespace = "io.github.maxdac.marknotes.libre"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.maxdac.marknotes.libre"
        minSdk = 29
        targetSdk = 36
        // Bumped by the "Prepare release" workflow; F-Droid reads these literals to detect new versions.
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // marknotes-sync's native library is built for devices (arm64) and emulators (x86_64) only.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        // MarkNotes' Dropbox app key. It is public by design (PKCE, no secret) and ships in every APK; committing it
        // lets F-Droid build a working app from source. -Pmarknotes.dropboxAppKey overrides it for a private app.
        val dropboxAppKey = providers.gradleProperty("marknotes.dropboxAppKey").orNull?.takeIf { it.isNotBlank() }
            ?: "sdjefhss3elcfp3"
        buildConfigField("String", "DROPBOX_APP_KEY", "\"$dropboxAppKey\"")
        manifestPlaceholders["dropboxAppKey"] = dropboxAppKey
    }

    // Release signing is only configured when the keystore env vars are present (set by release.yml).
    val releaseKeystore = providers.environmentVariable("MARKNOTES_KEYSTORE_PATH").orNull
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("MARKNOTES_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("MARKNOTES_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("MARKNOTES_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // F-Droid rejects the encrypted Google Play dependency metadata blob, and it breaks reproducible builds.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = false
        checkDependencies = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":app-shared"))
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
