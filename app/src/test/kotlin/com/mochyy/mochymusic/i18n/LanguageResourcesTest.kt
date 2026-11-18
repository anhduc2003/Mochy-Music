package com.mochyy.mochymusic.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Check the selector and the Indonesian format strings against the actual resource tree. */
class LanguageResourcesTest {
    private val resDir = listOf(File("src/main/res"), File("app/src/main/res"))
        .first { it.isDirectory }

    private fun children(file: File): List<Element> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val nodes = factory.newDocumentBuilder().parse(file).documentElement.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun values(folder: File): Map<String, Element> = folder.listFiles().orEmpty()
        .filter { it.extension == "xml" }
        .flatMap(::children)
        .filter { it.tagName in setOf("string", "plurals") && it.getAttribute("translatable") != "false" }
        .associateBy { it.getAttribute("name") }

    /**
     * The language a tag names: the two codes Android renamed, and Norwegian's macrolanguage tag,
     * which names the same entry as its main written form in a picker that offers one row a language.
     */
    private fun language(tag: String): String = when (val code = tag.substringBefore('-').lowercase()) {
        "in" -> "id"
        "iw" -> "he"
        "no" -> "nb"
        else -> code
    }

    /** The tag a `values-*` folder stands for, following Android's qualifier syntax. */
    private fun tagOf(folder: String): String {
        val qualifier = folder.removePrefix("values-")
        return if (qualifier.startsWith("b+")) qualifier.removePrefix("b+").replace('+', '-')
        else qualifier.replace("-r", "-")
    }

    /**
     * The picker offers a row per **language**, and the resource tree is what Crowdin writes back: a
     * folder per language and, where the translator picked a region, another per region
     * (`values-de` beside `values-de-rDE`, `values-af-rZA` with no plain `values-af`). Comparing the
     * two lists tag for tag therefore asked for a second picker row per region, and hid the real
     * question: is every language that has translations offered, and is every offered language one
     * the app can actually show?
     */
    @Test fun `picker lists exactly the app translated languages`() {
        val array = children(File(resDir, "values/app_language_tags.xml"))
            .single { it.tagName == "string-array" && it.getAttribute("name") == "app_language_tags" }
        val nodes = array.childNodes
        val tags = (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
            .map { it.textContent.trim() }

        // a region folder with strings is a translation of its language
        val translated = resDir.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values-") && values(it).isNotEmpty() }
            .map { tagOf(it.name) }
            .toSet() + "en"

        assertEquals("Duplicate language tags", tags.size, tags.toSet().size)
        assertEquals(
            "offered languages and translated languages differ",
            translated.map(::language).toSet(),
            tags.map(::language).toSet(),
        )

        // and every tag offered has somewhere to come from: its own folder, or a region of its language
        tags.forEach { tag ->
            assertTrue(
                "$tag is offered but no resources translate it",
                translated.any { it.equals(tag, ignoreCase = true) || language(it) == language(tag) },
            )
        }
    }

    @Test fun `Indonesian strings preserve the default format arguments`() {
        val english = values(File(resDir, "values"))
        val indonesian = values(File(resDir, "values-in"))
        val argument = Regex("%(?!%)(?:\\d+\\$)?[a-zA-Z]")
        fun placeholders(element: Element): List<String> {
            val text = if (element.tagName == "plurals") {
                val nodes = element.childNodes
                (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
                    .first { it.getAttribute("quantity") == "other" }.textContent
            } else element.textContent
            return argument.findAll(text).map { it.value }.sorted().toList()
        }
        (english.keys intersect indonesian.keys).forEach { key ->
            val en = english.getValue(key)
            val id = indonesian.getValue(key)
            assertEquals("Type of $key", en.tagName, id.tagName)
            assertEquals("Formatting in $key", placeholders(en), placeholders(id))
        }
        assertTrue("Expected Indonesian resources", indonesian.size > 1000)
    }
}
