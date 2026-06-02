package github.intellij.support.ide.inspector.source

import com.intellij.openapi.diagnostic.logger
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

object IdeaSourcePathResolver {
    private val LOG = logger<IdeaSourcePathResolver>()

    private val cache = ConcurrentHashMap<String, Path?>()

    fun resolve(repoRoot: Path, fqn: String): Path? {
        val cleanFqn = fqn.substringBefore('$').trim()
        if (cleanFqn.isEmpty()) return null
        val key = repoRoot.toAbsolutePath().toString() + "::" + cleanFqn
        cache[key]?.let { return it }
        if (cache.containsKey(key)) return null

        val resolved = doResolve(repoRoot, cleanFqn)
        cache[key] = resolved
        return resolved
    }

    fun clearCache() = cache.clear()

    private fun doResolve(repoRoot: Path, fqn: String): Path? {
        if (!repoRoot.exists() || !repoRoot.isDirectory()) return null
        val relPath = fqn.replace('.', '/')
        val simpleName = fqn.substringAfterLast('.')
        val candidateSuffixes = listOf("$relPath.java", "$relPath.kt")

        val moduleRoots = collectModuleRoots(repoRoot)
        for (module in moduleRoots) {
            for (srcRoot in candidateSrcDirs(module)) {
                for (suffix in candidateSuffixes) {
                    val candidate = srcRoot.resolve(suffix)
                    if (candidate.exists()) return candidate
                }
            }
        }

        // Fallback: deep walk capped depth, looking for files named SimpleName.{java,kt}
        // and containing the FQN's package declaration.
        return walkForFile(repoRoot, simpleName, fqn.substringBeforeLast('.', ""))
    }

    private fun candidateSrcDirs(module: Path): List<Path> {
        val result = mutableListOf<Path>()
        for (name in listOf("src", "source", "java", "kotlin", "gen", "testSrc")) {
            val p = module.resolve(name)
            if (p.isDirectory()) result.add(p)
        }
        if (result.isEmpty() && module.isDirectory()) result.add(module)
        return result
    }

    private fun collectModuleRoots(repoRoot: Path): List<Path> {
        val out = mutableListOf<Path>()
        val knownTops = listOf(
            "platform", "plugins", "java", "python", "android", "PyCharm",
            "RubyMine", "WebStorm", "PhpStorm", "DataGrip", "GoLand", "Rider",
            "CLion", "AppCode", "DataSpell", "Aqua", "tools", "xml", "json",
            "spellchecker", "yaml", "markdown", "uast", "vcs-impl", "execution-impl",
            "lang-impl", "analysis-impl", "analysis-api", "lvcs", "lang-api",
            "platform-impl", "platform-api", "indexing-impl", "core-impl",
            "remoteServers", "smRunner", "structuralsearch",
        )
        // 1) traverse known top dirs to depth 4 collecting all directories that contain a "src"
        for (top in knownTops) {
            val t = repoRoot.resolve(top)
            if (!t.isDirectory()) continue
            collectModulesUnder(t, 4, out)
        }
        // 2) also include repoRoot itself
        out.add(repoRoot)
        return out
    }

    private fun collectModulesUnder(start: Path, maxDepth: Int, out: MutableList<Path>) {
        try {
            Files.walkFileTree(start, emptySet(), maxDepth, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = dir.name
                    if (name.startsWith(".") || name == "build" || name == "out" || name == "node_modules") {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    // Treat directory as a module if it has a src/ child
                    if (Files.isDirectory(dir.resolve("src")) || Files.isDirectory(dir.resolve("source"))) {
                        out.add(dir)
                    }
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (t: Throwable) {
            LOG.debug("walk failed for $start", t)
        }
    }

    private fun walkForFile(repoRoot: Path, simpleName: String, expectedPackage: String): Path? {
        val target = listOf("$simpleName.java", "$simpleName.kt")
        var found: Path? = null
        try {
            Files.walkFileTree(repoRoot, emptySet(), 12, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = dir.name
                    if (name.startsWith(".") || name == "build" || name == "out" || name == "node_modules") {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (file.name !in target) return FileVisitResult.CONTINUE
                    if (expectedPackage.isEmpty()) {
                        found = file
                        return FileVisitResult.TERMINATE
                    }
                    return try {
                        val text = Files.newBufferedReader(file).use { reader ->
                            val sb = StringBuilder()
                            var line: String?
                            var count = 0
                            while (reader.readLine().also { line = it } != null && count < 60) {
                                sb.append(line).append('\n'); count++
                                if (line!!.trimStart().startsWith("package ")) break
                            }
                            sb.toString()
                        }
                        if (text.contains("package $expectedPackage")) {
                            found = file
                            FileVisitResult.TERMINATE
                        } else FileVisitResult.CONTINUE
                    } catch (_: Throwable) {
                        FileVisitResult.CONTINUE
                    }
                }
            })
        } catch (t: Throwable) {
            LOG.debug("walkForFile failed", t)
        }
        return found
    }
}
