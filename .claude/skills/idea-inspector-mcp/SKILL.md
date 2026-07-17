---
name: idea-inspector-mcp
description: Drive the IDE Inspector plugin's MCP toolset to open JetBrains IDE git history views. Use when the user wants to "show file history", "show git log for a class", or inspect commit history for an IntelliJ source file/FQN from outside the IDE. Requires the target IDE instance to have the `com.intellij.mcpServer` plugin enabled and the IDE Inspector plugin installed.
---

# IDE Inspector MCP Toolset

Source: `src/main/java/github/intellij/support/ide/inspector/mcp/IdeaInspectorMcpToolset.kt`

Class `IdeaInspectorMcpToolset` registers two MCP tools via `com.intellij.mcpServer`. Both run inside the currently focused JetBrains IDE project and open the native **Vcs.ShowTabbedFileHistory** tab.

## Prerequisites

- Target IDE has IDE Inspector plugin loaded (`plugin_mcp.xml` wires the toolset).
- `com.intellij.mcpServer` bundled plugin is enabled in that IDE.
- Project opened in IDE is under a Git VCS root (`Git4Idea` mandatory dep).
- For `show_file_history_for_fqn`: project should be an `intellij-community` checkout OR have the FQN resolvable via `ClassFinderService`.

## Tools

### `show_file_history(filePath: String)`

Open git history tab for a file.

- `filePath`: absolute path, OR project-relative path inside the opened repo.
- Resolution: `LocalFileSystem.refreshAndFindFileByNioFile`. Non-existent path → `mcpFail`.
- Validates `VcsLogFileHistoryProvider.canShowFileHistory` before showing — fails if file not under a project VCS root.
- Returns: `"Opened file history for <abs path>"`.

### `show_file_history_for_fqn(fqn: String)`

Resolve a fully qualified Java/Kotlin class name to a source file in the opened project, then open history.

- `fqn`: e.g. `com.intellij.codeInspection.dataFlow.DataFlowInspectionBase`.
- Resolution order:
  1. `ClassFinderService.getPsiFiles(fqn)` — if found, delegates to `ShowGitLogForClassesAction.showFileAndVcsHistory`.
  2. Else falls back to `IdeaSourcePathResolver.resolve(projectBasePath, fqn)` (path-based lookup against `intellij-community` source layout).
- Fails if project has no base path, base path missing, or class unresolvable.

## When to invoke

Trigger this skill when the user asks (in any project):

- "open file history for X in the IDE"
- "show git log for class com.foo.Bar"
- "open intellij-community history for inspection class Y"

Do NOT use for:
- Reading git log inline (use `git log` via Bash).
- Files outside any opened JetBrains project (no MCP target).

## Invocation pattern

The tools surface as MCP tools. Call them via the `mcp__jetbrains__*` family once the host IDE registers them — the registered names are `show_file_history` and `show_file_history_for_fqn`. Pass arguments as a single JSON object matching the parameter name.

Example arguments:

```json
{"filePath": "platform/lang-impl/src/com/intellij/codeInspection/InspectionProfileImpl.java"}
```

```json
{"fqn": "com.intellij.codeInspection.dataFlow.DataFlowInspectionBase"}
```

## Failure modes (exact strings)

- `File not found: <path>`
- `Project has no base path`
- `Project base path does not exist: <path>`
- `Class not found under <basePath>: <fqn>`
- `Cannot resolve VirtualFile for: <path>`
- `VcsLogFileHistoryProvider unavailable`
- `Cannot show file history for: <vf.path>. The file may not be under a project VCS root.`

If any of these surface, surface the exact message to the user — do not retry blindly.

## Related code

- `ClassFinderService` — PSI-based FQN → file resolution.
- `ShowGitLogForClassesAction` — Help-menu twin action (Java-only path, see `plugin_java.xml`).
- `IdeaSourcePathResolver` — path-based fallback for `intellij-community` checkouts.
- `VcsLogFileHistoryProvider` — platform service that owns the history tab.
