package com.kareem.secondbrain.capture.android.connector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DurableRelayOutboxTest {
    @Test
    fun pendingEntrySurvivesNewOutboxInstanceUntilAckRemoval() {
        val dir = Files.createTempDirectory("relay-outbox-test").toFile()
        try {
            val first = DurableRelayOutbox(dir)
            first.put("event-1", "{\"event_id\":\"sb_event-1\"}", enqueuedAtEpochMs = 100L)
            assertEquals(1, first.count())

            val afterRestart = DurableRelayOutbox(dir)
            val loaded = afterRestart.loadAll()
            assertEquals(0, loaded.corruptFiles)
            assertEquals(1, loaded.entries.size)
            assertEquals("event-1", loaded.entries.single().eventId)
            assertEquals("{\"event_id\":\"sb_event-1\"}", loaded.entries.single().raw)

            afterRestart.remove("event-1")
            assertEquals(0, DurableRelayOutbox(dir).count())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun duplicatePutKeepsOneLogicalDeliveryCopy() {
        val dir = Files.createTempDirectory("relay-outbox-dedupe").toFile()
        try {
            val outbox = DurableRelayOutbox(dir)
            outbox.put("event-1", "first", enqueuedAtEpochMs = 100L)
            outbox.put("event-1", "second", enqueuedAtEpochMs = 200L)

            val loaded = outbox.loadAll().entries
            assertEquals(1, loaded.size)
            assertEquals(100L, loaded.single().enqueuedAtEpochMs)
            assertEquals("first", loaded.single().raw)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun corruptEntryIsReportedAndPreservedForForensics() {
        val dir = Files.createTempDirectory("relay-outbox-corrupt").toFile()
        try {
            dir.resolve("broken.entry").writeText("not-an-outbox-entry")
            val outbox = DurableRelayOutbox(dir)
            val loaded = outbox.loadAll()

            assertEquals(1, loaded.corruptFiles)
            assertTrue(dir.resolve("broken.entry").exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
