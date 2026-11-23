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
