plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.maxdac.marknotes"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.maxdac.marknotes"
        minSdk = 29
        targetSdk = 36
        // Release builds override these via -Pmarknotes.versionCode / -Pmarknotes.versionName.
        versionCode = providers.gradleProperty("marknotes.versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("marknotes.versionName").orNull ?: "0.1.0-alpha"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Cloud provider registrations (see README). Without them the provider shows as unavailable.
        fun providerProperty(name: String) =
            providers.gradleProperty("marknotes.$name").orNull?.takeIf { it.isNotBlank() } ?: "unconfigured"
        val dropboxAppKey = providerProperty("dropboxAppKey")
        val msalClientId = providerProperty("msalClientId")
        val msalSignatureHash = providerProperty("msalSignatureHash")
        buildConfigField("String", "DROPBOX_APP_KEY", "\"$dropboxAppKey\"")
        buildConfigField("String", "MSAL_CLIENT_ID", "\"$msalClientId\"")
        buildConfigField("String", "MSAL_SIGNATURE_HASH", "\"$msalSignatureHash\"")
        manifestPlaceholders["dropboxAppKey"] = dropboxAppKey
        manifestPlaceholders["msalSignatureHash"] = msalSignatureHash
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
    implementation(project(":core"))
    // Optional Proton Drive SDK bridge, only present when built with -Pmarknotes.protonSdk=true.
    if (findProject(":proton-drive") != null) {
        implementation(project(":proton-drive"))
    }

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.markwon.core)
    implementation(libs.markwon.strikethrough)
    implementation(libs.markwon.tables)
    implementation(libs.markwon.tasklist)
    implementation(libs.dropbox.android)
    implementation(libs.msal)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
