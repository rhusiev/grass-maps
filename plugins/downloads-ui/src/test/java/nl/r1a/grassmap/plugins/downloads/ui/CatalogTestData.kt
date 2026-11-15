package nl.r1a.grassmap.plugins.downloads.ui

import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.RegionCatalog
import nl.r1a.grassmap.plugin.api.testing.fixture.CatalogFixture
import nl.r1a.grassmap.regions.RegionCatalogParser

/**
 * The catalogue the pipeline wrote, read through the real parser, so the screen
 * tests run against the server's own file rather than a hand-built model.
 */
internal object CatalogTestData {
    fun real(): RegionCatalog = parse(CatalogFixture.bytes())

    fun withWorld(): RegionCatalog = parse(CatalogFixture.withWorld())

    fun withWorldAndAddon(): RegionCatalog = parse(CatalogFixture.withWorldAndElevationAddon())

    private fun parse(bytes: ByteArray): RegionCatalog =
        when (val outcome = RegionCatalogParser.parse(bytes)) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("catalogue refused: ${outcome.error}")
        }
}
