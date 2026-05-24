// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api

import retrofit2.HttpException

/**
 * Extracts the HTTP status code from a [Throwable] if it wraps a
 * Retrofit [HttpException]. Returns null for non-HTTP errors.
 *
 * Defined here (in `:core:proton-api`, where Retrofit is on the
 * classpath) so callers like `:core:sync` can inspect HTTP codes
 * without depending on Retrofit directly.
 */
fun Throwable.httpStatusCode(): Int? = (this as? HttpException)?.code()
