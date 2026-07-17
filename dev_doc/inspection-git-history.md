# Inspection Class Git History Lookup

Feature: from the readonly `Inspections` tree tab, locate the selected inspection's implementation class file inside a local `intellij-community` checkout and show its recent git history.

## User flow

1. Configure local repo path: **Settings | Tools | IDE Inspector | IDEA Source Repository | Path**.
2. Open **IDE Properties** tool window → **Inspections** tab.
3. Select an inspection leaf node in the tree.
4. Click toolbar action **Show Git History** (history icon).
5. Background task resolves the implementation class FQN to a source file in the configured repo, then runs `git log` on it.
6. Popup shows commits (Date, Author, Hash, Subject).
   - Double-click row → copy full hash to clipboard.
   - Right-click row → `Copy Hash`, `Open in GitHub`.
   - Footer buttons → `Open File` (in current IDE editor), `Copy Path`.

## Components

### Setting
- `LensSettingsState.State.ideSourceRepoPath: String?` — persistent (xml: `ide.inspector.xml`).
- UI row added to `LensApplicationConfigurable`: folder-chooser via `FileChooserDescriptorFactory.createSingleFolderDescriptor()` + `textFieldWithBrowseButton`.

### FQN → file resolver
`source/IdeaSourcePathResolver.kt`

- Input: `repoRoot: Path`, `fqn: String`.
- Strips inner-class suffix (`Foo$Bar` → `Foo`).
- Walks known top-level dirs (`platform/`, `plugins/`, `java/`, `python/`, etc.) to depth 4 collecting candidate module roots (dirs containing `src/` or `source/`).
- For each module, looks under `src/`, `source/`, `java/`, `kotlin/`, `gen/`, `testSrc/` for `<pkg-as-path>.java` or `.kt`.
- Fallback: deep walk up to depth 12 for `SimpleName.{java,kt}`, validates by matching the `package <expected>` line in the file header.
- Caches all lookups in `ConcurrentHashMap<String, Path?>` keyed by `repoRoot::fqn`.
- Skips `build/`, `out/`, `node_modules/`, hidden dirs.

### Git history reader
`source/GitHistoryReader.kt`

- Validates `<repoRoot>/.git` exists.
- Relativizes the target file against the repo root.
- Invokes:
  ```
  git -C <repoRoot> log --no-merges -n <limit> --date=short --follow \
      --pretty=format:%H<US>%an<US>%ad<US>%s -- <relPath>
  ```
  Field separator: ASCII `0x1F` (Unit Separator), avoids tab/newline collisions in subjects.
- Runs via `GeneralCommandLine` + `CapturingProcessHandler` with 20s timeout.
- Returns sealed `Result.Ok(commits, relativePath)` or `Result.Error(message)`.

### Data
`source/CommitInfo.kt`

```kotlin
data class CommitInfo(
    val hash: String,
    val author: String,
    val date: String,
    val subject: String,
) { val shortHash: String get() = if (hash.length > 8) hash.substring(0, 8) else hash }
```

### Popup
`source/InspectionGitHistoryPopup.kt`

- Entry: `InspectionGitHistoryPopup.show(project, fqn, anchor)`.
- Unset repo path → confirm dialog → `ShowSettingsUtil` opens IDE Inspector page.
- Resolves + reads log inside `Task.Backgroundable` (cancellable, shows IDE progress).
- Result UI: `JBPopup` with `JBTable` (columns `Date | Author | Hash | Subject`, widths `90, 160, 90, 600`).
- `MouseListener`: double-click copies full hash.
- `PopupHandler`: right-click menu → `Copy Hash`, `Open in GitHub` (`https://github.com/JetBrains/intellij-community/commit/<hash>`).
- Footer: `Copy Path`, `Open File` (`LocalFileSystem.refreshAndFindFileByNioFile` → `FileEditorManager.openFile`).

### Toolbar wiring
`InspectionListPanel.java::buildToolbar`

```java
group.add(new DumbAwareAction("Show Git History", ..., AllIcons.Vcs.History) {
  @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
  @Override public void update(AnActionEvent e) {
    e.getPresentation().setEnabled(selectedRow() != null);
  }
  @Override public void actionPerformed(AnActionEvent e) {
    Row r = selectedRow();
    if (r == null || r.implClass == null || r.implClass.isEmpty() || "-".equals(r.implClass)) return;
    InspectionGitHistoryPopup.INSTANCE.show(project, r.implClass, tree);
  }
});
```

## File map

```
src/main/kotlin/github/intellij/support/ide/inspector/
  settings/LensSettingsState.kt          (edited: + ideSourceRepoPath)
  settings/LensApplicationConfigurable.kt(edited: + path chooser row)
  source/CommitInfo.kt                   (new)
  source/IdeaSourcePathResolver.kt       (new)
  source/GitHistoryReader.kt             (new)
  source/InspectionGitHistoryPopup.kt    (new)
src/main/java/github/jetbrains/support/ide/inspector/
  InspectionListPanel.java               (edited: + Show Git History action)
```

## Edge cases handled

- Inner class FQN (`a.b.Outer$Inner`) → resolves `Outer` source file.
- Multi-class `.kt` files: validated against `package` declaration on fallback walk.
- Missing `.git` dir → `Result.Error("Not a git repository: …")`.
- File outside repo (relativize throws) → `Result.Error("File is not inside repo root.")`.
- Unconfigured repo path → confirm dialog offers to open settings.
- Resolver cache prevents repeated deep walks within a session.
- `Task.Backgroundable` keeps EDT responsive during walk + git invocation.

## Known limitations

- Always uses HEAD branch.
- No diff viewer (use GitHub link).
- No date / author filter.
- Single configured repo only.
- `git log --follow` requires single path (preserved).
- Source jar-only inspections (no `.java`/`.kt` on disk) cannot be resolved.

## Test plan

- Configure repo path → local `intellij-community` checkout.
- Select known inspection (e.g. `com.intellij.codeInspection.unused.UnusedDeclarationInspection`) → click `Show Git History` → popup populated.
- Unconfigured path → confirm dialog → settings page opens.
- Bogus FQN → "Class not found" message.
- Right-click commit → `Open in GitHub` → browser navigates.

## Build status

`./gradlew compileKotlin compileJava` — green.
