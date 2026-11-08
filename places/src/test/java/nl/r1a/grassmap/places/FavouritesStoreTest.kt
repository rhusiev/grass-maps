package nl.r1a.grassmap.places

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class FavouritesStoreTest {
    private val directory = File(System.getProperty("java.io.tmpdir"), "favourites-store-${UUID.randomUUID()}")
    private val file = File(directory, "favourites/favourites.gpx")

    @After
    fun removeDirectory() {
        directory.deleteRecursively()
    }

    @Test
    fun aMissingFileIsAnEmptyStore() =
        runTest {
            assertEquals(Outcome.Success(FavouritesSnapshot()), FavouritesStore(file).load())
        }

    @Test
    fun aSavedSnapshotSurvivesAReload() =
        runTest {
            val expected = snapshot()

            assertEquals(Outcome.Success(Unit), FavouritesStore(file).save(expected))

            assertEquals(Outcome.Success(expected), FavouritesStore(file).load())
        }

    @Test
    fun aCorruptStoreFileIsATypedErrorAndIsNotOverwritten() =
        runTest {
            file.parentFile?.mkdirs()
            file.writeText("<gpx version=\"1.1\"><wpt lat=\"49.8")
            val before = file.readBytes()
            val store = FavouritesStore(file)

            assertEquals(Outcome.Failure(AppError.CorruptData), store.load())
            assertEquals(
                Outcome.Failure(AppError.CorruptData),
                store.add(favourite(49.84, 24.03, "New")),
            )
            assertTrue(before.contentEquals(file.readBytes()))
        }

    @Test
    fun deleteThenUndoRestoresTheRecordAtItsOriginalPlace() =
        runTest {
            val expected = snapshot()
            val store = FavouritesStore(file)
            store.save(expected)
            val deleted = expected.favourites[1]

            val receipt =
                when (val outcome = store.delete(deleted.id)) {
                    is Outcome.Success -> outcome.value
                    is Outcome.Failure -> throw AssertionError("delete failed: ${outcome.error}")
                }

            assertEquals(1, receipt.index)
            assertEquals(deleted, receipt.favourite)
            val afterDelete =
                when (val outcome = store.load()) {
                    is Outcome.Success -> outcome.value
                    is Outcome.Failure -> throw AssertionError("load failed: ${outcome.error}")
                }
            assertEquals(listOf("Rynok Square", "Aqueduct", "No group"), afterDelete.favourites.map { it.name })
            assertEquals("a deletion keeps the group definitions", expected.groups, afterDelete.groups)

            assertEquals(Outcome.Success(deleted), store.undoDelete(receipt.favourite.id))
            assertEquals(Outcome.Success(expected), store.load())
        }

    @Test
    fun undoOfNothingIsATypedNoOp() =
        runTest {
            val store = FavouritesStore(file)

            assertEquals(
                Outcome.Failure(AppError.NotFound),
                store.undoDelete(FavouriteId("no-such-deletion")),
            )
        }

    @Test
    fun anUndoReceiptIsConsumedOnce() =
        runTest {
            val store = FavouritesStore(file)
            val expected = snapshot()
            store.save(expected)
            val id = expected.favourites[0].id
            val receipt =
                when (val outcome = store.delete(id)) {
                    is Outcome.Success -> outcome.value
                    is Outcome.Failure -> throw AssertionError("delete failed: ${outcome.error}")
                }

            assertTrue(store.undoDelete(receipt.favourite.id) is Outcome.Success)
            assertEquals(Outcome.Failure(AppError.NotFound), store.undoDelete(receipt.favourite.id))
        }

    @Test
    fun addingAppendsTheRecordAndItsDerivedId() =
        runTest {
            val store = FavouritesStore(file)
            store.save(snapshot())
            val added =
                favourite(
                    latitude = 49.85,
                    longitude = 24.04,
                    name = "New place",
                    group = "Lviv",
                    icon = "special_star",
                )

            val stored =
                when (val outcome = store.add(added)) {
                    is Outcome.Success -> outcome.value
                    is Outcome.Failure -> throw AssertionError("add failed: ${outcome.error}")
                }

            assertEquals(added, stored)
            val loaded =
                when (val outcome = store.load()) {
                    is Outcome.Success -> outcome.value
                    is Outcome.Failure -> throw AssertionError("load failed: ${outcome.error}")
                }
            assertEquals(5, loaded.favourites.size)
            assertEquals(stored, loaded.favourites.last())
        }

    @Test
    fun addingToAGroupInheritsTheGroupIcon() =
        runTest {
            val store = FavouritesStore(file)
            store.save(snapshot())

            val stored =
                when (val outcome = store.add(favourite(49.85, 24.04, "Inherits", group = "Lviv"))) {
                    is Outcome.Success -> outcome.value
                    is Outcome.Failure -> throw AssertionError("add failed: ${outcome.error}")
                }

            assertEquals("historic_castle", stored.icon)
            assertEquals(Favourite.DEFAULT_COLOR, stored.color)
        }

    @Test
    fun savingARecordWithAnInheritedIconStoresTheGroupIcon() =
        runTest {
            val store = FavouritesStore(file)
            val snapshot =
                FavouritesSnapshot(
                    groups = listOf(FavouriteGroup("Lviv", "#FF0000", "historic_castle")),
                    favourites = listOf(favourite(49.84, 24.03, "Rynok Square", group = "Lviv")),
                )

            assertEquals(Outcome.Success(Unit), store.save(snapshot))

            val loaded =
                when (val outcome = store.load()) {
                    is Outcome.Success -> outcome.value
                    is Outcome.Failure -> throw AssertionError("load failed: ${outcome.error}")
                }
            assertEquals("historic_castle", loaded.favourites.single().icon)
        }

    @Test
    fun deletingAnUnknownIdIsNotFound() =
        runTest {
            val store = FavouritesStore(file)
            store.save(snapshot())

            assertEquals(Outcome.Failure(AppError.NotFound), store.delete(FavouriteId("missing")))
        }

    @Test
    fun anImportAppendsRecordsAndGroupsWhileExistingGroupsKeepTheirAppearance() =
        runTest {
            val store = FavouritesStore(file)
            store.save(snapshot())
            val imported =
                FavouritesSnapshot(
                    groups =
                        listOf(
                            FavouriteGroup("Lviv", "#00FF00", null, FavouriteShape.Circle),
                            FavouriteGroup("Work"),
                        ),
                    favourites = deriveIds(listOf(favourite(49.86, 24.05, "Office", group = "Work"))),
                )

            val result =
                when (val outcome = store.import(FavouritesGpx.write(imported))) {
                    is Outcome.Success -> outcome.value
                    is Outcome.Failure -> throw AssertionError("import failed: ${outcome.error}")
                }

            assertEquals(1, result.imported)
            assertTrue(result.skipped.isEmpty())
            val loaded =
                when (val outcome = store.load()) {
                    is Outcome.Success -> outcome.value
                    is Outcome.Failure -> throw AssertionError("load failed: ${outcome.error}")
                }
            assertEquals(5, loaded.favourites.size)
            assertEquals("Office", loaded.favourites.last().name)
            assertEquals(
                listOf("Lviv", "Parks", "Work"),
                loaded.groups.map { it.name },
            )
            assertEquals("#FF0000", loaded.groups.first().color)
        }

    @Test
    fun aMalformedImportLeavesTheStoreUnchanged() =
        runTest {
            val store = FavouritesStore(file)
            store.save(snapshot())

            assertEquals(
                Outcome.Failure(AppError.CorruptData),
                store.import("<gpx><wpt lat=\"49.8\">".toByteArray()),
            )
            assertEquals(Outcome.Success(snapshot()), store.load())
        }

    private fun snapshot(): FavouritesSnapshot =
        FavouritesSnapshot(
            groups =
                listOf(
                    FavouriteGroup("Lviv", "#FF0000", "historic_castle", FavouriteShape.Circle),
                    FavouriteGroup("Parks", "#0000FF", null, FavouriteShape.Square),
                ),
            favourites =
                deriveIds(
                    listOf(
                        favourite(
                            49.8419,
                            24.0316,
                            "Rynok Square",
                            "Town hall corner",
                            "Lviv",
                            "#FF0000",
                            "historic_castle",
                        ),
                        favourite(
                            49.8383,
                            24.0329,
                            "Vysoky Zamok",
                            group = "Lviv",
                            color = "#ff4e4eff",
                            icon = "historic_castle",
                            shape = FavouriteShape.Square,
                        ),
                        favourite(
                            49.8356,
                            24.035,
                            "Aqueduct",
                            group = "Parks",
                            color = "#0000FF",
                            shape = FavouriteShape.Octagon,
                        ),
                        favourite(49.844, 24.029, "No group"),
                    ),
                ),
        )
}
