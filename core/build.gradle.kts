plugins {
    id("org.jetbrains.kotlin.jvm")
    // The marknotes-spec fixture loader, shared with :app's parity tests.
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
    api(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bcrypt)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    testFixturesApi(libs.junit)
    testFixturesApi(libs.kotlinx.serialization.json)
}

val specFixtures = rootProject.fileTree("spec/fixtures") { include("**/*.json") }

tasks.withType<Test>().configureEach {
    // Parity tests (package ...core.parity) run the marknotes-spec fixtures from the spec/ submodule.
    systemProperty("marknotes.specDir", rootProject.file("spec").absolutePath)
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
