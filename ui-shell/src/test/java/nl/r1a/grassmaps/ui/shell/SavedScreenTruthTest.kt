package nl.r1a.grassmaps.ui.shell

import nl.r1a.grassmaps.plugin.api.model.FavouriteBrowseState
import nl.r1a.grassmaps.plugin.api.model.FavouriteListSummary
import nl.r1a.grassmaps.plugin.api.model.FavouriteListsState
import nl.r1a.grassmaps.plugin.api.model.FavouritePointRow
import nl.r1a.grassmaps.plugin.api.model.FavouritePointSummary
import nl.r1a.grassmaps.plugin.api.model.FavouriteSort
import nl.r1a.grassmaps.plugin.api.model.FavouriteSpecial
import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 9 design review: with three saved places the Saved screen said "3 places
 * in 0 groups", "0 of 0 on the map" and "No saved places yet". The counts came
 * from the declared lists only, and the empty state checked the lists instead of
 * the places. A place whose group has no definition, or that has no group, must
 * still be counted and drawn.
 */
class SavedScreenTruthTest {
    private val english = TestStringCatalog.read("src/main/res/values/strings.xml").resolver()

    @Test
    fun placesWithoutADeclaredListAreCountedAndTheEmptyNoticeStaysAway() {
        val content = savedScreenContent(undeclaredState(), english, COMMANDS)

        assertEquals("3 places in 1 group", content.subtitle)
        assertEquals("1 of 1 on the map", content.onMapSummary)
        assertNull("the screen claims there are no saved places", content.empty)
        assertEquals(listOf("Coffee", "No list"), content.groups.map { it.name })
        assertEquals(listOf("2 places · on the map", "1 place · on the map"), content.groups.map { it.summary })
    }

    @Test
    fun theSortLineNamesTheSortTheListReallyUses() {
        val content = savedScreenContent(undeclaredState(), english, COMMANDS)

        assertEquals("File order", content.hint)
    }

    @Test
    fun anEmptyLibraryWithAListStillSaysThereAreNoPlaces() {
        val state = FavouriteListsState(lists = listOf(FavouriteListSummary("Coffee", "#D48865")))

        val content = savedScreenContent(state, english, COMMANDS)

        assertEquals("No saved places yet", content.empty)
        assertEquals(listOf("Coffee"), content.groups.map { it.name })
    }

    @Test
    fun theNoListRowHasNoEyeAndAnUndeclaredGroupCannotBeRecoloured() {
        val content = savedScreenContent(undeclaredState(), english, COMMANDS)
        val coffee = content.groups.single { it.list == "Coffee" }
        val ungrouped = content.groups.single { it.list == null }

        assertTrue("the undeclared group lost its eye", coffee.canToggleVisibility)
        assertFalse("an undeclared group offers a recolour the store cannot apply", coffee.canRecolour)
        assertFalse("the map always draws places without a group", ungrouped.canToggleVisibility)
        assertFalse(ungrouped.canRecolour)
        assertEquals(listOf("Home"), ungrouped.places.map { it.name })
        assertEquals("Home", ungrouped.places.single().special)
    }

    @Test
    fun aDeclaredListIsRecolourableAndHiddenGroupsCountOffTheMap() {
        val state = undeclaredState().copy(lists = listOf(FavouriteListSummary("Parks", "#3FBF6A")))

        val content = savedScreenContent(state, english, COMMANDS, hiddenGroups = setOf("Coffee"))

        assertEquals(listOf("Parks", "Coffee", "No list"), content.groups.map { it.name })
        assertTrue(content.groups[0].canRecolour)
        assertEquals("3 places in 2 groups", content.subtitle)
        assertEquals("1 of 2 on the map", content.onMapSummary)
        assertEquals("2 places · off the map", content.groups[1].summary)
    }

    @Test
    fun withoutAnyGroupTheHeaderCountsPlacesOnly() {
        val points = listOf(FavouritePointSummary("one", "Home"), FavouritePointSummary("two", "Office"))

        val content = savedScreenContent(FavouriteListsState(points = points), english, COMMANDS)

        assertEquals("2 places", content.subtitle)
        assertNull(content.onMapSummary)
        assertEquals(listOf("No list"), content.groups.map { it.name })
        assertEquals("2 places · on the map", content.groups.single().summary)
    }

    @Test
    fun placesFollowThePluginSortAndCarryTheirDescriptionAndPosition() {
        val point = GeoPoint(49.84, 24.03)
        val rows =
            listOf(
                FavouritePointRow("three", "Lviv coffee", "Coffee", description = "Rynok Square 10", position = point),
                FavouritePointRow("two", "Rynok coffee", "Coffee"),
                FavouritePointRow("one", "Home", special = FavouriteSpecial.HOME),
            )
        val state =
            undeclaredState().copy(
                browse = FavouriteBrowseState(sort = FavouriteSort.NAME_ASCENDING, rows = rows),
            )

        val content = savedScreenContent(state, english, COMMANDS)
        val coffee = content.groups.single { it.list == "Coffee" }

        assertEquals("Name A-Z", content.hint)
        assertEquals(listOf("Lviv coffee", "Rynok coffee"), coffee.places.map { it.name })
        assertEquals("Rynok Square 10", coffee.places.first().detail)
        assertEquals(point, coffee.places.first().position)
        assertEquals("three", coffee.places.first().id)
    }

    @Test
    fun aSearchKeepsOnlyMatchingGroupsAndSaysWhenNothingMatches() {
        val state =
            undeclaredState().copy(
                browse = FavouriteBrowseState(query = "rynok", rows = listOf(FavouritePointRow("two", "Rynok coffee", "Coffee"))),
            )

        val content = savedScreenContent(state, english, COMMANDS)

        assertTrue(content.searching)
        assertEquals(listOf("Coffee"), content.groups.map { it.name })
        assertNull(content.noMatches)
        assertEquals("Search 3 saved places", content.actions.first { it.action == SavedBarAction.SEARCH }.label)

        val none = savedScreenContent(state.copy(browse = FavouriteBrowseState(query = "zzz")), english, COMMANDS)
        assertTrue(none.groups.isEmpty())
        assertEquals("No matches", none.noMatches)
        assertNull("a search with no match claims the library is empty", none.empty)
    }

    @Test
    fun aPlaceMovesOnlyToTheOtherDeclaredLists() {
        val state =
            undeclaredState().copy(
                lists = listOf(FavouriteListSummary("Coffee", "#D48865"), FavouriteListSummary("Parks", "#3FBF6A")),
            )

        assertEquals(
            listOf(SavedMoveTarget("Parks", "Move to Parks")),
            savedMoveTargets(state, "two", english),
        )
        assertEquals(listOf("Coffee", "Parks"), savedMoveTargets(state, "one", english).map { it.list })
    }

    @Test
    fun thePanelsAndRowActionsHaveResolvedLabels() {
        val text = savedSheetText(english)

        assertEquals("New group", text.newGroup)
        assertEquals("Create", text.create)
        assertEquals("Show on map", text.showOnMap)
        assertEquals("Set as Home", text.setHome)
        assertEquals("Set as Work", text.setWork)
        assertEquals("Color for Coffee", savedRecolourTitle("Coffee", english))
    }

    private fun undeclaredState(): FavouriteListsState {
        val points =
            listOf(
                FavouritePointSummary("one", "Home", special = FavouriteSpecial.HOME),
                FavouritePointSummary("two", "Rynok coffee", "Coffee"),
                FavouritePointSummary("three", "Lviv coffee", "Coffee"),
            )
        return FavouriteListsState(
            points = points,
            browse =
                FavouriteBrowseState(
                    sort = FavouriteSort.INSERTION,
                    rows = points.map { FavouritePointRow(it.id, it.name, it.list, it.special) },
                ),
        )
    }

    private companion object {
        val COMMANDS = SavedCommands(search = true, map = true, sort = true, add = true, visibility = true)
    }
}
