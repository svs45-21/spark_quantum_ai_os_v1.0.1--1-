package com.spark.companion

import com.spark.companion.brain.LlmException
import com.spark.companion.brain.ModelCatalog
import com.spark.companion.brain.ModelSwitch
import com.spark.companion.brain.ModelUnavailableException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {

    @Test fun claudeModelsPutTheRecommendedOnesFirst() {
        val listed = listOf("claude-haiku-5-5", "claude-sonnet-5", "claude-opus-5-5", "claude-sonnet-5-5", "claude-opus-4-1", "not-claude", "claude-opus-5-5")
        assertEquals(
            listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-sonnet-5", "claude-haiku-5-5", "claude-opus-4-1"),
            ModelCatalog.claudeModels(listed),
        )
    }

    @Test fun switchRetriesWithDiscoveredModelAndSaves() = runBlocking {
        var saved = ""
        val tried = ArrayList<String>()
        val sw = ModelSwitch({ "old-model" }, onSwitch = { saved = it })
        val out = sw.run({ "new-model" }) { m ->
            tried += m
            if (m == "old-model") throw ModelUnavailableException("gone") else "ok:$m"
        }
        assertEquals("ok:new-model", out)
        assertEquals(listOf("old-model", "new-model"), tried)
        assertEquals("new-model", saved)
        assertEquals("new-model", sw.current())
    }

    @Test(expected = LlmException::class)
    fun switchGivesUpWhenNothingElseWorks() {
        runBlocking {
            ModelSwitch({ "only" }).run({ "only" }) { throw ModelUnavailableException("gone") }
        }
    }

}

class ModelListingTest {
    private val day = 24L * 60 * 60 * 1000

    @Test fun firstFetchMarksNothingNew() {
        val m = com.spark.companion.brain.ModelListing.merge(null, listOf("a" to "", "b" to ""), 1000L)
        assertTrue(m.added.isEmpty())
        assertFalse(m.list.entries.any { com.spark.companion.brain.ModelListing.isNew(it, 1000L) })
    }

    @Test fun laterFetchFlagsAddedAndRemovedModels() {
        val L = com.spark.companion.brain.ModelListing
        val first = L.merge(null, listOf("a" to "", "b" to ""), 0L).list
        val second = L.merge(first, listOf("c" to "free", "a" to ""), day)
        assertEquals(listOf("c"), second.added)
        assertEquals(listOf("b"), second.removed)
        assertEquals(listOf("c", "a"), second.list.ids)
        val c = second.list.entries.first()
        assertTrue(L.isNew(c, day + 1))
        assertFalse(L.isNew(c, day + L.NEW_FOR_MS + 1))
        // Still remembered as first seen on day one after another refresh.
        val third = L.merge(second.list, listOf("a" to "", "c" to "free"), 3 * day)
        assertTrue(third.added.isEmpty())
        assertEquals(day, third.list.entries.first { it.id == "c" }.firstSeen)
    }

    @Test fun jsonRoundTrip() {
        val L = com.spark.companion.brain.ModelListing
        val list = L.merge(null, listOf("x/y:free" to "free · phone control"), 5L).list
        assertEquals(list, L.fromJson(L.toJson(list)))
        assertNull(L.fromJson("not json"))
    }
}
