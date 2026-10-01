// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import android.icu.text.DisplayContext
import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.ULocale
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * "2 hours ago" as it reads inside a sentence ("last check 2 hours ago"), in [locale].
 * DateUtils' relative strings start with a capital in some languages (German "Vor 2 Stunden"),
 * which is wrong mid-sentence; ICU formats for that position.
 */
internal fun agoInSentence(thenMillis: Long, nowMillis: Long, locale: Locale): String {
    val formatter = RelativeDateTimeFormatter.getInstance(
        ULocale.forLocale(locale),
        null,
        RelativeDateTimeFormatter.Style.LONG,
        DisplayContext.CAPITALIZATION_FOR_MIDDLE_OF_SENTENCE
    )
    val minutes = TimeUnit.MILLISECONDS.toMinutes((nowMillis - thenMillis).coerceAtLeast(0))
    val hours = TimeUnit.MINUTES.toHours(minutes)
    val days = TimeUnit.HOURS.toDays(hours)
    val last = RelativeDateTimeFormatter.Direction.LAST
    return when {
        minutes < 1 -> formatter.format(
            RelativeDateTimeFormatter.Direction.PLAIN,
            RelativeDateTimeFormatter.AbsoluteUnit.NOW
        )
        hours < 1 -> formatter.format(minutes.toDouble(), last, RelativeDateTimeFormatter.RelativeUnit.MINUTES)
        days < 1 -> formatter.format(hours.toDouble(), last, RelativeDateTimeFormatter.RelativeUnit.HOURS)
        else -> formatter.format(days.toDouble(), last, RelativeDateTimeFormatter.RelativeUnit.DAYS)
    }
}

/** The language the screen is shown in (the app's own language when one is set). */
@Composable
internal fun currentLocale(): Locale = LocalConfiguration.current.locales[0]
