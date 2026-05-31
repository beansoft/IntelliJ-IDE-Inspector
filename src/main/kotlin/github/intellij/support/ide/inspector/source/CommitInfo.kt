package github.intellij.support.ide.inspector.source

data class CommitInfo(
    val hash: String,
    val author: String,
    val date: String,
    val subject: String,
) {
    val shortHash: String get() = if (hash.length > 8) hash.substring(0, 8) else hash
}
