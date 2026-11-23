package com.mochyy.mochymusic.modules

import com.mochyy.mochymusic.module.ModuleFeature
import com.mochyy.mochymusic.modules.local.LocalModule
import com.mochyy.mochymusic.modules.web.WebModuleManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The modules the fork's store lists have to be modules the app can actually take. A store row that
 * ends in "this isn't a module" is worse than no row at all, and this is the only place that gets
 * checked without a phone: every manifest under modules/store must parse, change the look, and have
 * a catalogue row pointing at it.
 */
class StoreManifestsTest {

    private val repoRoot = listOf(File(".."), File(".")).first { File(it, "modules/store").isDirectory }
    private val storeDir = File(repoRoot, "modules/store")
    private val catalog = File(repoRoot, "modules/catalog.json")

    @Test
    fun `every stored manifest is a look module the app accepts`() {
        val folders = storeDir.listFiles().orEmpty().filter { File(it, "manifest.json").isFile }
        assertTrue("no module folders under modules/store", folders.isNotEmpty())

        for (folder in folders) {
            val text = File(folder, "manifest.json").readText()
            val manifest = WebModuleManifest.parse(text) ?: error("${folder.name}: manifest.json doesn't parse")
            assertTrue("${folder.name}: no id", manifest.id.isNotBlank())
            assertTrue("${folder.name}: no name", manifest.name.isNotBlank())
            assertTrue("${folder.name}: version is what its row shows", manifest.version.isNotBlank())
            assertTrue("${folder.name}: changes nothing, so mochy would refuse it", manifest.changesLook)

            val module = LocalModule(manifest, files = null)
            assertTrue("${folder.name}: isn't read as a look", ModuleFeature.Look in module.adds)
            assertEquals(
                "${folder.name}: the folder name is the module id, so the catalogue can point at it",
                folder.name,
                manifest.id,
            )
        }
    }

    @Test
    fun `every catalogue row has the pictures its store page draws`() {
        val text = catalog.readText()
        for (field in listOf("icon", "banner")) {
            val urls = Regex("\"$field\"\\s*:\\s*\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toList()
            assertEquals("$field: every row needs one, or its page falls back to a letter", 4, urls.size)
            for (url in urls) {
                assertTrue("$url: the app only loads https pictures", url.startsWith("https://"))
                val path = url.substringAfter("/modules/store/")
                assertTrue("$url: nothing at modules/store/$path", File(storeDir, path).isFile)
            }
        }
    }

    @Test
    fun `every zip a row installs from is built and holds its manifest`() {
        val text = catalog.readText()
        val zips = Regex("\"zip\"\\s*:\\s*\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toList()
        assertTrue("liquid glass is the one kind a link can't carry", zips.isNotEmpty())

        for (url in zips) {
            val path = url.substringAfter("/modules/store/")
            val file = File(storeDir, path)
            assertTrue("$url: nothing at modules/store/$path, rebuild it with the command in modules/store/README.md", file.isFile)
            val names = java.util.zip.ZipFile(file).use { zip -> zip.entries().toList().map { it.name } }
            assertTrue("$url: a zip with no manifest.json at its root", "manifest.json" in names)
        }
    }

    @Test
    fun `every catalogue row points at a stored module`() {
        val text = catalog.readText()
        val urls = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toList()
        assertTrue("the catalogue lists nothing", urls.isNotEmpty())

        for (url in urls) {
            val id = url.trimEnd('/').substringAfterLast('/')
            assertTrue("$url: no module folder for it", File(storeDir, "$id/manifest.json").isFile)
            assertTrue("$url: the app only fetches https links", url.startsWith("https://"))
        }
    }
}
