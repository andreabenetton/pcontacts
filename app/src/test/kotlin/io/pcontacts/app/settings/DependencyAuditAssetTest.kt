// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.settings

import android.app.Application
import io.pcontacts.feature.settings.AuditStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DependencyAuditAssetTest {

    @Test fun parses_every_field_and_derives_the_status() {
        val audit = DependencyAuditAsset.parse(
            """
            {"schema":1,"generatedAt":"2026-09-22","nvdDataAsOf":"2026-09-19T06:16:30Z","engine":"13.0.0",
             "dependencies":[
               {"group":"a","name":"clean","version":"1","licenses":["Apache-2.0"],"cves":[]},
               {"group":"b","name":"assessed","version":"2","licenses":[],"cves":[
                 {"id":"CVE-1","score":9.8,"severity":"CRITICAL","url":"https://nvd.nist.gov/vuln/detail/CVE-1",
                  "suppressed":true,"reason":"false positive"}]},
               {"group":"c","name":"open","version":"3","licenses":["MIT"],"cves":[
                 {"id":"CVE-2","score":null,"severity":null,"url":"https://nvd.nist.gov/vuln/detail/CVE-2",
                  "suppressed":false,"reason":null}]}
             ]}
            """.trimIndent()
        )
        assertEquals("2026-09-19T06:16:30Z", audit.nvdDataAsOf)
        assertEquals(AuditStatus.OPEN, audit.status)
        assertEquals(listOf("c:open:3", "b:assessed:2", "a:clean:1"), audit.sortedForDisplay.map { it.coordinate })
        val assessed = audit.dependencies.single { it.name == "assessed" }.cves.single()
        assertEquals(9.8, assessed.score!!, 0.0)
        assertEquals("false positive", assessed.reason)
        val open = audit.dependencies.single { it.name == "open" }.cves.single()
        assertNull(open.score)
        assertNull(open.reason)
    }

    /** The committed snapshot must always parse: a broken file would take the whole app down at start. */
    @Test fun the_committed_snapshot_parses() {
        val file = File("src/main/assets/${DependencyAuditAsset.FILE_NAME}")
        assertTrue("missing ${file.absolutePath}", file.exists())
        val audit = DependencyAuditAsset.parse(file.readText())
        assertTrue(audit.dependencies.isNotEmpty())
        assertTrue(audit.dependencies.all { it.group.isNotEmpty() && it.version.isNotEmpty() })
    }
}
