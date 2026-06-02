package github.intellij.support.ide.inspector.source

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.logger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

object GitHistoryReader {
    private val LOG = logger<GitHistoryReader>()

    private const val SEP = ""
    private const val FORMAT = "%H${SEP}%an${SEP}%ad${SEP}%s"

    fun recentCommits(repoRoot: Path, filePath: Path, limit: Int = 30): Result {
        if (!Files.isDirectory(repoRoot.resolve(".git"))) {
            return Result.Error("Not a git repository: $repoRoot")
        }
        if (!Files.exists(filePath)) {
            return Result.Error("File not found: $filePath")
        }

        val rel = try {
            repoRoot.toAbsolutePath().normalize().relativize(filePath.toAbsolutePath().normalize()).toString()
        } catch (_: IllegalArgumentException) {
            return Result.Error("File is not inside repo root.")
        }

        val cli = GeneralCommandLine(
            "git", "log",
            "--no-merges",
            "-n", limit.toString(),
            "--date=short",
            "--follow",
            "--pretty=format:$FORMAT",
            "--",
            rel,
        ).withWorkDirectory(repoRoot.toFile())
            .withCharset(StandardCharsets.UTF_8)

        return try {
            val handler = CapturingProcessHandler(cli)
            val output = handler.runProcess(20_000)
            if (output.exitCode != 0) {
                Result.Error("git log failed (exit ${output.exitCode}): ${output.stderr.trim()}")
            } else {
                val commits = output.stdout.lineSequence()
                    .filter { it.isNotBlank() }
                    .mapNotNull { line ->
                        val parts = line.split(SEP)
                        if (parts.size >= 4) {
                            CommitInfo(parts[0], parts[1], parts[2], parts.drop(3).joinToString(SEP))
                        } else null
                    }
                    .toList()
                Result.Ok(commits, rel)
            }
        } catch (t: Throwable) {
            LOG.warn("git invocation failed", t)
            Result.Error("git invocation failed: ${t.message}")
        }
    }

    sealed class Result {
        data class Ok(val commits: List<CommitInfo>, val relativePath: String) : Result()
        data class Error(val message: String) : Result()
    }
}
