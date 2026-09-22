// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * `docs/DE_GOOGLED_ROMS.md` mirrors the in-app screen word for word: the
 * page is rendered here from the same string resources and [DE_GOOGLED_ROMS]
 * model, in the screen's order, and compared with the committed file.
 * Regenerate the file from this rendering when the screen changes.
 */
class DeGoogledRomsDocTest {

    private val strings: Map<String, String> by lazy {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        (0 until nodes.length).associate { i ->
            val node = nodes.item(i)
            node.attributes.getNamedItem("name").nodeValue to node.textContent.replace("\\'", "'")
        }
    }

    private val resourceNames: Map<Int, String> by lazy {
        R.string::class.java.fields.associate { it.getInt(null) to it.name }
    }

    private fun text(id: Int): String = strings.getValue(resourceNames.getValue(id))

    private fun text(name: String): String = strings.getValue(name)

    private fun render(): String = buildString {
        appendLine("# ${text("rom_screen_title")}")
        appendLine()
        appendLine(text("rom_intro_definition"))
        appendLine()
        appendLine(text("rom_intro_approaches"))
        appendLine()
        appendLine(text("rom_intro_security"))
        appendLine()
        DE_GOOGLED_ROMS.forEach { rom ->
            appendLine("## ${rom.name}")
            appendLine()
            appendLine("- **${text("rom_label_google_free")}:** ${text(rom.googleFreeByDefault)}")
            appendLine("- **${text("rom_label_compat")}:** ${text(rom.googleCompatibility)}")
            appendLine("- **${text("rom_label_focus")}:** ${text(rom.focus)}")
            appendLine("- **${text("rom_label_hardware")}:** ${text(rom.hardware)}")
            rom.note?.let { note ->
                appendLine()
                appendLine("> ${text(note)}")
            }
            appendLine()
            appendLine("${text("rom_website")}: <${rom.website}>")
            appendLine()
        }
    }.trimEnd('\n') + "\n"

    @Test fun the_markdown_page_matches_the_screen_content() {
        assertEquals(render(), File("../../docs/DE_GOOGLED_ROMS.md").readText())
    }
}
