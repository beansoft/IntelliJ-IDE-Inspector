# External Git Log Integration

## English

### Goal

Expose `git4idea.log.showExternalGitLogInToolwindow` so the plugin can open
the IntelliJ Platform **Version Control** tool window on an external Git repo
(typically the configured `intellij-community` checkout), instead of relying on
a custom commit table popup.

### Step 1 — Add `Git4Idea` bundled plugin

`gradle.properties`

```properties
platformBundledPlugins = com.intellij.java,Git4Idea
```

`src/main/resources/META-INF/plugin.xml`

```xml
<depends>com.intellij.modules.platform</depends>
<depends>com.intellij.modules.lang</depends>
<depends>Git4Idea</depends>
```

Required dependency is safe because the plugin already targets IntelliJ
Ultimate only (`platformType = IU`), where `Git4Idea` is always bundled.

### Step 2 — Action: `ShowExternalGitLogAction`

New file: `src/main/kotlin/github/intellij/support/ide/inspector/action/ShowExternalGitLogAction.kt`

Logic:

1. Reuse `ShowGitLogForClassesAction.getClipboardText()` +
   `showMultiLineInputDialog(...)` for class-name input.
2. Off EDT, in a read action, resolve each class via
   `ClassFinderService.getInstance(project).getPsiFiles(name)`.
3. For each resolved `PsiFile.virtualFile`, look up the VCS root via
   `ProjectLevelVcsManager.getVcsFor(vf)` and keep it only if
   `vcs.keyInstanceMethod == GitVcs.getKey()` and
   `GitUtil.isGitRoot(root.toNioPath())`.
4. On EDT, fetch
   `ToolWindowManager.getInstance(project).getToolWindow(ChangesViewContentManager.TOOLWINDOW_ID)`
   and call
   `git4idea.log.showExternalGitLogInToolwindow(project, toolWindow, roots, tabTitle, tabDescription)`.

Register in `src/main/resources/META-INF/plugin_java.xml`:

```xml
<action id="showExternalGitLogForClasses"
        class="github.intellij.support.ide.inspector.action.ShowExternalGitLogAction"
        text="Show External Git Log For Classes...">
    <add-to-group group-id="HelpMenu" anchor="after"
                  relative-to-action="showGitLogForClasses" />
</action>
```

Lives in `plugin_java.xml` because it needs both `com.intellij.java`
(`ClassFinderService`) and `Git4Idea`; both bundled in IU.

### Step 3 — `InspectionGitHistoryPopup.showExternalGitLog`

`src/main/kotlin/github/intellij/support/ide/inspector/source/InspectionGitHistoryPopup.kt`

Mirrors the `TabbedShowHistoryAction` pattern: shows **single-file history**
inside the external repo log tab, instead of the whole repository log.

```kotlin
fun showExternalGitLog(project: Project, fqn: String, anchor: Component?) {
    // 1. Read ideSourceRepoPath from LensSettingsState; prompt configure if empty.
    // 2. Validate the path exists and GitUtil.isGitRoot(repoRoot).
    // 3. Resolve LocalFileSystem.refreshAndFindFileByNioFile(repoRoot) → rootVf.
    // 4. Resolve ChangesViewContentManager.TOOLWINDOW_ID tool window.
    // 5. Backgroundable task: IdeaSourcePathResolver.resolve(repoRoot, fqn) → file VirtualFile.
    // 6. onSuccess:
    //    Fast path — if VcsLogFileHistoryProvider.canShowFileHistory(paths, null):
    //        historyProvider.showFileHistory(paths, null)   // same as TabbedShowHistoryAction
    //    Slow path (external repo not in project VCS mappings):
    //        val filters = VcsLogFilterObject.collection(
    //            VcsLogFilterObject.fromVirtualFiles(setOf(vf)))
    //        showExternalGitLogInToolwindow(project, toolWindow,
    //            { createLogUi("EXTERNAL " + rootPath, filters) },
    //            listOf(rootVf),
    //            "IDEA History: <ClassName>",
    //            "<fqn>\n<filePath>")
}
```

The `uiFactory` overload (`VcsLogManager.() -> T : VcsLogUiEx`) lets the call
pre-apply a `VcsLogFilterCollection`, restricting the new tab to commits
touching the resolved file — same effect as `TabbedShowHistoryAction`'s
`showNewFileHistory`, but driven through `showExternalGitLogInToolwindow`
because the IDEA source repo is **external** to the current project and is
not in `ProjectLevelVcsManager` mappings (so `VcsLogFileHistoryProvider`
cannot serve it directly).

Old `show(...)` (custom commit-table popup) retained for backward
compatibility — only the caller is swapped.

### Step 4 — Swap caller

`src/main/java/github/jetbrains/support/ide/inspector/InspectionListPanel.java:214`

```java
// before
InspectionGitHistoryPopup.INSTANCE.show(project, r.implClass, tree);
// after
InspectionGitHistoryPopup.INSTANCE.showExternalGitLog(project, r.implClass, tree);
```

### Step 5 — Cross-IDE MCP tool

When the IDEA source repo is open in a **separate** IDE instance (not in the
current project), expose a tool through the JetBrains MCP Server plugin so a
client (Claude, source IDE) can ask that instance to run the fast file-history
path directly.

`gradle.properties`

```properties
platformBundledPlugins = com.intellij.java,Git4Idea,com.intellij.mcpServer
```

`src/main/resources/META-INF/plugin.xml`

```xml
<depends optional="true" config-file="plugin_mcp.xml">com.intellij.mcpServer</depends>
```

`src/main/resources/META-INF/plugin_mcp.xml`

```xml
<idea-plugin>
    <extensions defaultExtensionNs="com.intellij">
        <mcpServer.mcpToolset
            implementation="github.intellij.support.ide.inspector.mcp.IdeaInspectorMcpToolset" />
    </extensions>
</idea-plugin>
```

`src/main/kotlin/github/intellij/support/ide/inspector/mcp/IdeaInspectorMcpToolset.kt`

Implements `com.intellij.mcpserver.McpToolset` and exposes two `@McpTool`s:

- `show_file_history(filePath)` — `filePath` is absolute or project-relative.
  Resolves the `VirtualFile`, calls `VcsLogFileHistoryProvider.showFileHistory(paths, null)`
  on EDT. Same fast path used by `TabbedShowHistoryAction.showNewFileHistory`.
- `show_file_history_for_fqn(fqn)` — uses `IdeaSourcePathResolver.resolve(project.basePath, fqn)`
  inside a `readAction`, then opens the resolved file's history.

Project handle comes from `currentCoroutineContext().project`; errors are
surfaced through `mcpFail(...)`.

### Step 6 — Verify

```bash
./gradlew compileKotlin compileJava
```

`@ApiStatus.Internal` warning on `showExternalGitLogInToolwindow` is expected
and non-blocking.

---

## 中文

### 目标

调用 `git4idea.log.showExternalGitLogInToolwindow`，让插件在 IDE 的 **Version
Control** 工具窗口里打开外部 Git 仓库（一般是配置的 `intellij-community`
本地检出），替代原有的自定义提交表格弹窗。

### 步骤 1 — 添加 `Git4Idea` 捆绑插件依赖

`gradle.properties`

```properties
platformBundledPlugins = com.intellij.java,Git4Idea
```

`src/main/resources/META-INF/plugin.xml`

```xml
<depends>com.intellij.modules.platform</depends>
<depends>com.intellij.modules.lang</depends>
<depends>Git4Idea</depends>
```

插件目标平台为 IntelliJ Ultimate（`platformType = IU`），`Git4Idea` 在
Ultimate 中始终捆绑，因此声明为必需依赖是安全的。

### 步骤 2 — 新增 Action：`ShowExternalGitLogAction`

新文件：`src/main/kotlin/github/intellij/support/ide/inspector/action/ShowExternalGitLogAction.kt`

逻辑：

1. 复用 `ShowGitLogForClassesAction.getClipboardText()` 与
   `showMultiLineInputDialog(...)` 收集多行类名输入。
2. 在后台 read action 中通过
   `ClassFinderService.getInstance(project).getPsiFiles(name)` 解析每个类。
3. 对每个 `PsiFile.virtualFile` 用 `ProjectLevelVcsManager.getVcsFor(vf)`
   找到所属 VCS，仅保留 `vcs.keyInstanceMethod == GitVcs.getKey()` 且
   `GitUtil.isGitRoot(root.toNioPath())` 的根目录。
4. 在 EDT 上取
   `ToolWindowManager.getInstance(project).getToolWindow(ChangesViewContentManager.TOOLWINDOW_ID)`，
   调用
   `git4idea.log.showExternalGitLogInToolwindow(project, toolWindow, roots, tabTitle, tabDescription)`。

在 `src/main/resources/META-INF/plugin_java.xml` 中注册：

```xml
<action id="showExternalGitLogForClasses"
        class="github.intellij.support.ide.inspector.action.ShowExternalGitLogAction"
        text="Show External Git Log For Classes...">
    <add-to-group group-id="HelpMenu" anchor="after"
                  relative-to-action="showGitLogForClasses" />
</action>
```

放在 `plugin_java.xml` 中，因为同时依赖 `com.intellij.java`
（`ClassFinderService`）和 `Git4Idea`，二者在 IU 中均捆绑。

### 步骤 3 — `InspectionGitHistoryPopup.showExternalGitLog`

文件：`src/main/kotlin/github/intellij/support/ide/inspector/source/InspectionGitHistoryPopup.kt`

参考 `TabbedShowHistoryAction` 的思路：在外部仓库的 Log Tab 中只显示**单文件
历史**，而不是整个仓库日志。

```kotlin
fun showExternalGitLog(project: Project, fqn: String, anchor: Component?) {
    // 1. 从 LensSettingsState 读取 ideSourceRepoPath，空则提示去配置。
    // 2. 校验路径存在以及 GitUtil.isGitRoot(repoRoot)。
    // 3. 用 LocalFileSystem.refreshAndFindFileByNioFile(repoRoot) 解析 rootVf。
    // 4. 取 ChangesViewContentManager.TOOLWINDOW_ID 对应的工具窗口。
    // 5. Backgroundable 任务：IdeaSourcePathResolver.resolve(repoRoot, fqn) 得到文件 VirtualFile。
    // 6. onSuccess：
    //    快速路径——若 VcsLogFileHistoryProvider.canShowFileHistory(paths, null) 为 true：
    //        historyProvider.showFileHistory(paths, null)   // 与 TabbedShowHistoryAction 一致
    //    慢速路径（仓库未在当前项目 VCS 映射中）：
    //        val filters = VcsLogFilterObject.collection(
    //            VcsLogFilterObject.fromVirtualFiles(setOf(vf)))
    //        showExternalGitLogInToolwindow(project, toolWindow,
    //            { createLogUi("EXTERNAL " + rootPath, filters) },
    //            listOf(rootVf),
    //            "IDEA History: <ClassName>",
    //            "<fqn>\n<filePath>")
}
```

`uiFactory` 重载（`VcsLogManager.() -> T : VcsLogUiEx`）允许预先注入
`VcsLogFilterCollection`，从而把新 Tab 限定到改动了目标文件的提交——效果与
`TabbedShowHistoryAction` 的 `showNewFileHistory` 一致，但因为 IDEA 源码仓库
对当前项目而言是**外部**仓库，不在 `ProjectLevelVcsManager` 映射中，所以
`VcsLogFileHistoryProvider` 无法直接处理，必须通过
`showExternalGitLogInToolwindow` 入口。

原 `show(...)`（自定义提交表格弹窗）保留以便兼容，仅切换调用方。

### 步骤 4 — 切换调用方

`src/main/java/github/jetbrains/support/ide/inspector/InspectionListPanel.java:214`

```java
// 修改前
InspectionGitHistoryPopup.INSTANCE.show(project, r.implClass, tree);
// 修改后
InspectionGitHistoryPopup.INSTANCE.showExternalGitLog(project, r.implClass, tree);
```

### 步骤 5 — 跨 IDE 的 MCP 工具

当 IDEA 源码仓库在另一个独立的 IDE 实例中以项目方式打开时，可通过 JetBrains
MCP Server 插件暴露一个工具，让客户端（Claude、源 IDE 等）直接驱动该实例
跑刚才的快速 file-history 路径。

`gradle.properties`

```properties
platformBundledPlugins = com.intellij.java,Git4Idea,com.intellij.mcpServer
```

`src/main/resources/META-INF/plugin.xml`

```xml
<depends optional="true" config-file="plugin_mcp.xml">com.intellij.mcpServer</depends>
```

`src/main/resources/META-INF/plugin_mcp.xml`

```xml
<idea-plugin>
    <extensions defaultExtensionNs="com.intellij">
        <mcpServer.mcpToolset
            implementation="github.intellij.support.ide.inspector.mcp.IdeaInspectorMcpToolset" />
    </extensions>
</idea-plugin>
```

`src/main/kotlin/github/intellij/support/ide/inspector/mcp/IdeaInspectorMcpToolset.kt`

实现 `com.intellij.mcpserver.McpToolset`，注册两个 `@McpTool`：

- `show_file_history(filePath)`：`filePath` 支持绝对路径或项目相对路径。解析
  `VirtualFile` 后在 EDT 上调用
  `VcsLogFileHistoryProvider.showFileHistory(paths, null)`，与
  `TabbedShowHistoryAction.showNewFileHistory` 走同一条快速通路。
- `show_file_history_for_fqn(fqn)`：在 `readAction` 中通过
  `IdeaSourcePathResolver.resolve(project.basePath, fqn)` 解析类名到文件，再打开
  其 file history。

`Project` 通过 `currentCoroutineContext().project` 获得，错误用 `mcpFail(...)`
抛回 MCP 调用方。

### 步骤 6 — 验证

```bash
./gradlew compileKotlin compileJava
```

`showExternalGitLogInToolwindow` 上的 `@ApiStatus.Internal` 警告为预期警告，
不影响构建。
