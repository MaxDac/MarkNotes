package io.github.maxdac.marknotes.core.parity

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue

/** One case of a marknotes-spec fixture file (`spec/fixtures/<area>/<function>.json`). */
class FixtureCase(
    /** Fixture id, e.g. "front-matter/read-id". */
    val fixture: String,
    val name: String,
    val input: JsonObject,
    val expected: JsonElement,
    /** Reason from `knownDivergence.android`, or null when Android must match [expected]. */
    val knownDivergence: String?
) {
    override fun toString(): String = "$fixture: $name"

    fun string(key: String): String = input.getValue(key).jsonPrimitive.content

    fun stringOrNull(key: String): String? = (input[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun int(key: String): Int = input.getValue(key).jsonPrimitive.content.toInt()

    /**
     * Compares [actual] with [expected]. With a known divergence (or a [pendingSpecIssue] not yet triaged in
     * marknotes-spec) the output must still differ; the case is then reported as skipped with the reason, so a
     * fix is noticed and the entry removed.
     */
    fun check(actual: JsonElement, pendingSpecIssue: String? = null) {
        val divergence = knownDivergence?.let { "Known divergence: $it" }
            ?: pendingSpecIssue?.let { "TODO(spec): $it" }
        if (divergence == null) {
            assertEquals(toString(), expected, actual)
            return
        }
        assertNotEquals("$this now matches the spec: remove its divergence entry", expected, actual)
        assumeTrue("$divergence (actual: $actual)", false)
    }
}

/** Loads the marknotes-spec golden fixtures from the `spec/` submodule (see spec/README.md). */
object SpecFixtures {
    const val APP_KEY = "android"

    private val json = Json { ignoreUnknownKeys = true }

    private val fixturesDir: File?
        get() = System.getProperty("marknotes.specDir")?.let { File(it, "fixtures") }?.takeIf { it.isDirectory }

    /** True when `marknotes.parity.required` is set (the parity CI job): a missing submodule is then an error. */
    private val required: Boolean get() = System.getProperty("marknotes.parity.required").toBoolean()

    /** The fixtures folder; skips the calling test when the submodule isn't checked out (fails if [required]). */
    fun requireDir(): File {
        val dir = fixturesDir
        check(dir != null || !required) { "spec/fixtures not found: run `git submodule update --init`" }
        assumeTrue("spec/ submodule not checked out (git submodule update --init)", dir != null)
        return dir!!
    }

    /** Ids ("area/function") of every fixture file, sorted. */
    fun ids(): List<String> {
        val dir = requireDir()
        return dir.walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .map { it.relativeTo(dir).invariantSeparatorsPath.removeSuffix(".json") }
            .sorted()
            .toList()
    }

    /** Cases of fixture [id], or none when the submodule isn't checked out (an error when [required]). */
    fun load(id: String): List<FixtureCase> {
        val dir = fixturesDir
        check(dir != null || !required) { "spec/fixtures not found: run `git submodule update --init`" }
        if (dir == null) return emptyList()
        val root = json.parseToJsonElement(File(dir, "$id.json").readText()).jsonObject
        return root.getValue("cases").jsonArray.map { element ->
            val case = element.jsonObject
            FixtureCase(
                fixture = id,
                name = case.getValue("name").jsonPrimitive.content,
                input = case.getValue("input").jsonObject,
                expected = case.getValue("expected"),
                knownDivergence = (case["knownDivergence"] as? JsonObject)?.get(APP_KEY)?.jsonPrimitive?.content
            )
        }
    }
}
