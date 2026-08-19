package github.intellij.support.ide.inspector.logviewer

data class IdeaLogEntry(
    val index: Int,
    val timestamp: String,
    val elapsedMs: Long,
    val level: String,
    val logger: String,
    val message: String,
    val body: String,
    val startOffset: Int,
    val endOffset: Int,
    val exceptions: List<IdeaLogException>,
) {
    val hasException: Boolean get() = exceptions.isNotEmpty()
    val primaryExceptionType: String? get() = exceptions.firstOrNull()?.type
    val primaryExceptionMessage: String? get() = exceptions.firstOrNull()?.message
}

data class IdeaLogException(
    val type: String,
    val message: String,
    val stackLines: List<String>,
    val causedBy: Boolean,
)
