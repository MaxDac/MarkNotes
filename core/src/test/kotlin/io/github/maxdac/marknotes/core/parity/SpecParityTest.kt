package io.github.maxdac.marknotes.core.parity

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Runs every marknotes-spec fixture case mapped to `:core` (see [CoreParity]) against the real implementation. */
@RunWith(Parameterized::class)
class SpecParityTest(private val case: FixtureCase) {
    @Test
    fun matchesSpec() {
        val mapping = CoreParity.mappings.getValue(case.fixture) as ParityMapping.Run
        case.check(mapping.run(case), mapping.pendingSpecIssues[case.name])
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<FixtureCase> = CoreParity.mappings
            .filterValues { it is ParityMapping.Run }
            .keys
            .sorted()
            .flatMap(SpecFixtures::load)
    }
}

/** Every fixture file in the spec must be mapped, and every mapping must point at an existing file and case. */
class SpecCoverageTest {
    @Test
    fun everyFixtureIsMapped() {
        assertEquals(SpecFixtures.ids(), CoreParity.mappings.keys.sorted())
    }

    @Test
    fun pendingSpecIssuesReferToExistingCases() {
        SpecFixtures.requireDir()
        CoreParity.mappings.forEach { (fixture, mapping) ->
            if (mapping !is ParityMapping.Run) return@forEach
            val names = SpecFixtures.load(fixture).map { it.name }.toSet()
            assertEquals("stale pending issues in $fixture", emptySet<String>(), mapping.pendingSpecIssues.keys - names)
        }
    }
}
