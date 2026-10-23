plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Everything the libre (F-Droid) and Pro apps share: Compose UI, sync wiring, Room, Dropbox and shared-content
// import. Provider-specific apps add their DriveConnectors through MarkNotesApp (see sync/DriveConnector.kt).
android {
    namespace = "io.github.maxdac.marknotes.shared"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = false
        checkDependencies = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// The library manifest keeps ${dropboxAppKey} for the apps to fill in; only this library's own test APK needs a value.
androidComponents {
    onVariants { variant ->
        variant.androidTest?.manifestPlaceholders?.put("dropboxAppKey", "unconfigured")
    }
}

dependencies {
    api(project(":core"))
    api(variantOf(libs.marknotes.sync.android) { artifactType("aar") })
    api(variantOf(libs.jna) { artifactType("aar") })

    api(libs.androidx.core.ktx)
    api(libs.androidx.activity.compose)
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.material.icons.extended)
    api(libs.androidx.lifecycle.viewmodel.compose)
    api(libs.androidx.lifecycle.runtime.compose)
    api(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    api(libs.kotlinx.coroutines.android)
    implementation(libs.markwon.core)
    implementation(libs.markwon.strikethrough)
    implementation(libs.markwon.tables)
    implementation(libs.markwon.tasklist)
    implementation(libs.markwon.image)
    implementation(libs.markwon.syntax.highlight) {
        exclude(group = "org.jetbrains", module = "annotations-java5")
    }
    implementation(libs.prism4j) {
        exclude(group = "org.jetbrains", module = "annotations-java5")
    }
    // Prism4j generates its grammar locator with a javac annotation processor (see CodeGrammars.java).
    annotationProcessor(libs.prism4j.bundler)
    implementation(libs.dropbox.android)
    // Text extraction from shared PDFs (see ui/importing).
    implementation(libs.pdfbox.android)
    constraints {
        // pdfbox-android's Bouncy Castle 1.72 has known vulnerabilities.
        implementation(libs.bouncycastle.bcprov.jdk15to18)
        implementation(libs.bouncycastle.bcpkix.jdk15to18)
        implementation(libs.bouncycastle.bcutil.jdk15to18)
        // The Dropbox SDK's jackson-core 2.15 has known vulnerabilities (GHSA-r7wm-3cxj-wff9, GHSA-7hhh-6rmp-j9qf).
        implementation(libs.jackson.core)
        // prism4j-bundler's commons-io 2.6 (GHSA-78wr-2p64-hpwj, GHSA-gwrp-pvrq-jmwv); compile time only.
        annotationProcessor(libs.commons.io)
    }
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    // marknotes-spec fixture loader (core/src/testFixtures).
    testImplementation(testFixtures(project(":core")))
}

// The spec/ submodule sits next to this module, both in marknotes-android and when the Pro app includes it.
val specDir = file("../spec")

tasks.withType<Test>().configureEach {
    // MentionParityTest runs a marknotes-spec fixture from the spec/ submodule; the parity CI job sets
    // -Pmarknotes.parity.required=true so a missing submodule fails instead of skipping.
    systemProperty("marknotes.specDir", specDir.absolutePath)
    systemProperty(
        "marknotes.parity.required",
        providers.gradleProperty("marknotes.parity.required").getOrElse("false")
    )
    inputs.files(fileTree(specDir.resolve("fixtures")) { include("**/*.json") })
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("specFixtures")
}
