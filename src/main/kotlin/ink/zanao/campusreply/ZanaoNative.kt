package ink.zanao.campusreply

import io.github.whywood.deepdeep.plugin.api.PluginHost
import java.io.File
import java.util.UUID

/**
 * Loads bundled libAesNew.so for App-API signing (makeSign) and response
 * decryption (unhashAppResult).
 *
 * ART allows one .so path to be opened by only ONE ClassLoader. The host wraps
 * every plugin (re)load/upgrade in a fresh InMemoryDexClassLoader, so a fixed
 * extraction path fails after upgrade with:
 *   "Shared library ... already opened by ClassLoader ... can't open ..."
 * Therefore every load gets a uniquely named copy; stale copies are unlinked
 * (safe: an already-mapped inode stays alive for the previous ClassLoader).
 */
internal object ZanaoNative {
    private const val ASSET = "libAesNew.so"
    private const val LIB_NAME = "AesNew"

    @Volatile
    private var loaded = false

    @Synchronized
    fun ensure(host: PluginHost) {
        if (loaded) return
        // Cheap first try: host may expose the plugin lib dir to this ClassLoader.
        runCatching { System.loadLibrary(LIB_NAME) }
            .onSuccess { loaded = true }
        if (loaded) return

        val source = File(host.assetsDir, ASSET)
        check(source.isFile) { "native asset missing: ${source.absolutePath}" }
        val dir = File(host.dataDir, "native").apply { mkdirs() }
        dir.listFiles()?.forEach { stale ->
            if (stale.isFile && stale.name.startsWith("libAesNew")) {
                runCatching { stale.delete() }
            }
        }
        val target = File(dir, "libAesNew-${UUID.randomUUID()}.so")
        source.copyTo(target, overwrite = false)
        try {
            System.load(target.absolutePath)
        } catch (e: UnsatisfiedLinkError) {
            throw IllegalStateException("native load failed: ${target.absolutePath}: ${e.message}", e)
        }
        loaded = true
    }

    fun signState(): String = if (loaded) "loaded" else "not_loaded"
}
