// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class RelativeTimeTest {

    private val now = 10_000_000_000L
    private val hour = 3_600_000L

    @Test fun german_mid_sentence_is_lower_case() {
        // Seen on the Pixel 2026-10-02: "letzte Prüfung Vor 2 Stunden".
        assertEquals("vor 2 Stunden", agoInSentence(now - 2 * hour, now, Locale.GERMAN))
    }

    @Test fun english_reads_as_before() {
        assertEquals("2 hours ago", agoInSentence(now - 2 * hour, now, Locale.ENGLISH))
        assertEquals("5 minutes ago", agoInSentence(now - 5 * 60_000L, now, Locale.ENGLISH))
        assertEquals("3 days ago", agoInSentence(now - 3 * 24 * hour, now, Locale.ENGLISH))
        assertEquals("now", agoInSentence(now, now, Locale.ENGLISH))
    }
}
