// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.account

class MissingContactsPermissionException :
    Exception("ContactsProvider unavailable — READ_CONTACTS / WRITE_CONTACTS not granted")
