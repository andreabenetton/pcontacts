// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.protoncontacts

import ezvcard.property.Anniversary
import ezvcard.property.Birthday
import ezvcard.property.DateOrTimeProperty
import ezvcard.util.PartialDate
import java.time.LocalDate
import java.util.Locale

/**
 * `BDAY` / `ANNIVERSARY` ↔ the model's string, which is Android's Event
 * form: `yyyy-MM-dd`, `--MM-dd` for a date without a year, or free text.
 *
 * `[V]` Proton's web client writes a full date as `yyyyMMdd` and anything
 * else as `VALUE=text` (WebClients `packages/shared/lib/contacts/vcard.ts`,
 * pinned in docs/API_RESEARCH.md); ez-vcard writes a `LocalDate` the same
 * way in vCard 4.0. `[U]` A date without a year goes as text too: the web
 * client's `parseISO` cannot read `--MMdd` and would show a wrong date.
 */
internal object VCardDates {

    private val FULL_DATE = Regex("\\d{4}-\\d{2}-\\d{2}")

    fun read(property: DateOrTimeProperty?): String? {
        property ?: return null
        property.date?.let { t -> return runCatching { LocalDate.from(t).toString() }.getOrElse { t.toString() } }
        property.partialDate?.let { return partial(it) }
        return property.text?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun birthday(value: String): Birthday = date(value)?.let { Birthday(it) } ?: Birthday(value)

    fun anniversary(value: String): Anniversary = date(value)?.let { Anniversary(it) } ?: Anniversary(value)

    private fun date(value: String): LocalDate? =
        if (FULL_DATE.matches(value)) runCatching { LocalDate.parse(value) }.getOrNull() else null

    private fun partial(date: PartialDate): String {
        val (year, month, day) = Triple(date.year, date.month, date.date)
        return when {
            month == null || day == null -> date.toISO8601(true)
            year == null -> String.format(Locale.ROOT, "--%02d-%02d", month, day)
            else -> String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day)
        }
    }
}
