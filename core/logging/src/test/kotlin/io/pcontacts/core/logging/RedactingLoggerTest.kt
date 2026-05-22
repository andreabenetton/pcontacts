// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactingLoggerTest {

    @Test fun emits_redacted_message_to_sink() {
        val sink = BufferedSink()
        val logger = RedactingLogger("ProtonApi", sink)
        logger.info { "Authorization: Bearer abcdefg" }
        assertEquals(1, sink.emissions.size)
        val e = sink.emissions.first()
        assertEquals(LogLevel.INFO, e.level)
        assertEquals("ProtonApi", e.tag)
        assertEquals("Authorization: Bearer <redacted>", e.msg)
        assertNull(e.throwableInfo)
    }

    @Test fun lazy_msg_is_not_evaluated_below_min_level() {
        val sink = BufferedSink()
        val logger = RedactingLogger("X", sink, minLevel = LogLevel.WARN)
        var rendered = false
        logger.debug { rendered = true; "should not render" }
        logger.info { rendered = true; "should not render" }
        assertFalse("msg lambda should be skipped below minLevel", rendered)
        assertEquals(0, sink.emissions.size)
    }

    @Test fun throwable_info_is_attached_and_redacted() {
        val sink = BufferedSink()
        val logger = RedactingLogger("Sync", sink)
        val t = RuntimeException("payload-bytes-must-not-leak")
        logger.error(t) { "sync failed" }
        assertEquals(1, sink.emissions.size)
        val e = sink.emissions.first()
        assertEquals(LogLevel.ERROR, e.level)
        assertEquals("sync failed", e.msg)
        assertNotNull(e.throwableInfo)
        assertFalse(e.throwableInfo!!.contains("payload-bytes-must-not-leak"))
    }

    @Test fun render_error_yields_placeholder_not_throw() {
        val sink = BufferedSink()
        val logger = RedactingLogger("Z", sink)
        logger.warn { throw IllegalArgumentException("boom") }
        assertEquals(1, sink.emissions.size)
        assertTrue(sink.emissions.first().msg.startsWith("<log-render-error:"))
    }

    @Test fun noop_sink_drops_everything() {
        val logger = RedactingLogger("X", NoOpSink)
        logger.error { "this is fine" } // should not throw
    }
}
