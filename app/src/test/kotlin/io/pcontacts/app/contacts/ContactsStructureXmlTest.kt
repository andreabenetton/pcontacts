// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * res/xml/contacts.xml against the rules AOSP Contacts' ExternalAccountType and
 * BaseAccountType apply; a breach makes the account read-only (no EditSchema) or makes
 * the account type fail to load, and contacts editors stop offering it.
 */
class ContactsStructureXmlTest {

    private val root: Element = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(File("src/main/res/xml/contacts.xml"))
        .documentElement

    private val schemaKinds: Map<String, Element> = root.getElementsByTagName("EditSchema")
        .let { schemas ->
            assertEquals("contacts.xml needs exactly one EditSchema", 1, schemas.length)
            (schemas.item(0) as Element).children("DataKind").associateBy { it.getAttribute("kind") }
        }

    @Test fun the_schema_has_the_kinds_aosp_requires() {
        assertTrue(schemaKinds.keys.containsAll(listOf("name", "photo")))
        val name = schemaKinds.getValue("name")
        NAME_SUPPORTS.forEach { assertEquals("$it must be true", "true", name.getAttribute(it)) }
    }

    @Test fun single_valued_kinds_declare_max_occurs_one() {
        SINGLE_VALUED.filter { it in schemaKinds }
            .forEach { assertEquals("$it maxOccurs", "1", schemaKinds.getValue(it).getAttribute("maxOccurs")) }
    }

    @Test fun only_what_pcontacts_carries_both_ways_is_editable() {
        assertTrue("group membership is pushed (ADR-0027)", "group_membership" in schemaKinds)
        val eventTypes = schemaKinds.getValue("event").children("Type").map { it.getAttribute("type") }
        assertEquals(listOf("birthday", "anniversary"), eventTypes)
    }

    @Test fun data_kinds_outside_the_schema_are_custom_mimetypes_only() {
        // A standard mimetype declared again as ContactsDataKind is "already registered" in AOSP.
        val extra = root.children("ContactsDataKind").map { it.getAttribute("android:mimeType") }
        assertTrue(extra.isNotEmpty())
        extra.forEach { assertTrue(it, it.startsWith("vnd.android.cursor.item/vnd.")) }
    }

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

    private companion object {
        val NAME_SUPPORTS = listOf(
            "supportsPrefix",
            "supportsMiddleName",
            "supportsSuffix",
            "supportsPhoneticFamilyName",
            "supportsPhoneticMiddleName",
            "supportsPhoneticGivenName"
        )
        val SINGLE_VALUED = listOf("photo", "nickname", "organization", "note", "group_membership")
    }
}
