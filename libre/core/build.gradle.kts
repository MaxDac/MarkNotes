plugins {
    id("org.jetbrains.kotlin.jvm")
    // The marknotes-spec fixture loader, shared with :app-shared's parity tests.
    `java-test-fixtures`
}

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
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jsoup)
    api(libs.marknotes.sync.kotlin)
    // JNA loads the native sync library: :app-shared ships the Android AAR, JVM tests the jar and the host libraries.
    compileOnly(libs.jna)

    testImplementation(libs.jna)
    testRuntimeOnly(libs.marknotes.sync.natives)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    testFixturesApi(libs.junit)
    testFixturesApi(libs.kotlinx.serialization.json)
}

// The spec/ submodule sits next to this module, both in marknotes-android and when the Pro app includes it.
val specDir = file("../spec")
val specFixtures = fileTree(specDir.resolve("fixtures")) { include("**/*.json") }

tasks.withType<Test>().configureEach {
    // Parity tests (package ...core.parity) run the marknotes-spec fixtures from the spec/ submodule.
    systemProperty("marknotes.specDir", specDir.absolutePath)
    inputs.files(specFixtures).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("specFixtures")
}

tasks.named<Test>("test") {
    // Without the submodule the parity tests skip; parityTest requires it.
    systemProperty("marknotes.parity.required", "false")
}

val parityTest by tasks.registering(Test::class) {
    description = "Runs the marknotes-spec fixtures against :core (requires the spec/ submodule)."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("io.github.maxdac.marknotes.core.parity.*")
    systemProperty("marknotes.parity.required", "true")
}
