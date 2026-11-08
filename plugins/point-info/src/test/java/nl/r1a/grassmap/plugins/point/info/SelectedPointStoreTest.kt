package nl.r1a.grassmap.plugins.point.info

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectedPointStoreTest {
    private val lviv = GeoPoint(latitude = 49.8419, longitude = 24.0316)
    private val kyiv = GeoPoint(latitude = 50.4501, longitude = 30.5234)

    @Test
    fun aSelectionIsVisibleToAReaderThatStartsLater() =
        runTest {
            val store = SelectedPointStore()
            store.select(lviv)

            // A new collection is what a recomposition or a rebuilt surface
            // does; the selection is not kept in the composition.
            assertEquals(lviv, store.point.first())
        }

    @Test
    fun selectingAgainReplacesThePreviousPoint() =
        runTest {
            val store = SelectedPointStore()
            store.select(lviv)
            store.select(kyiv)

            assertEquals(kyiv, store.point.first())
        }

    @Test
    fun clearRemovesTheSelection() =
        runTest {
            val store = SelectedPointStore(lviv)

            store.clear()

            assertNull(store.point.first())
        }
}
