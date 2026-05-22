// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.android.tools.lint.detector.api.Issue

class PcontactsIssueRegistry : IssueRegistry() {
    override val issues: List<Issue> = listOf(
        SensitiveLogDetector.ISSUE
    )

    override val api: Int = CURRENT_API
    override val minApi: Int = 14 // Lint API >= 14 covers AGP 8.x.

    override val vendor: Vendor = Vendor(
        vendorName = "pcontacts",
        identifier = "io.pcontacts.lint",
        feedbackUrl = "https://example.invalid/pcontacts/issues"
    )
}
