// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

// Android deprecated the IM and SIP data kinds in API 31, but other apps still store them and a
// rewrite must not drop them: their deprecated MIME types are named here, and only here.
@file:Suppress("DEPRECATION")

package io.pcontacts.core.contactswriter

import android.provider.ContactsContract.CommonDataKinds.Im
import android.provider.ContactsContract.CommonDataKinds.SipAddress

/** `Im.CONTENT_ITEM_TYPE`. */
internal const val IM_ITEM_TYPE: String = Im.CONTENT_ITEM_TYPE

/** `SipAddress.CONTENT_ITEM_TYPE`. */
internal const val SIP_ADDRESS_ITEM_TYPE: String = SipAddress.CONTENT_ITEM_TYPE
