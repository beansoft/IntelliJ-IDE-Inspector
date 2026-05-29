# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

IntelliJ Platform plugin (`com.intellij.support.ide.inspector`, display name "IDE Inspector"). Forks/extends [Inspection Lens](https://plugins.jetbrains.com/plugin/19678-inspection-lens) and adds tooling to extract inspection/intention class names from a running IDE so they can be looked up in the IntelliJ Community source repo via `Help | Show Git Log For Classes...`.

Targets IntelliJ Ultimate (`platformType=IU`), platform `2026.1.2`, `sinceBuild=253`, `untilBuild=263.*`. JVM toolchain 21. Kotlin `2.3.20`.

## Build / Run / Test

Tasks come from the IntelliJ Platform Gradle Plugin (`org.jetbrains.intellij.platform`). Use the Gradle wrapper:

```bash
./gradlew buildPlugin            # produces build/distributions/<name>-<version>.zip
./gradlew runIde                 # launches sandbox IDE with the plugin installed
./gradlew test                   # JUnit 5 tests under src/test/kotlin
./gradlew test --tests "com.intellij.support.ide.editor.EditorLensTest.Priority.minimumOffset"  # single test
./gradlew verifyPlugin           # plugin verifier (uses pluginVerifierIdeVersions from gradle.properties)
./gradlew publishPlugin          # requires PUBLISH_TOKEN / CERTIFICATE_CHAIN / PRIVATE_KEY / PRIVATE_KEY_PASSWORD env vars
./gradlew patchChangelog         # invoked automatically before publishPlugin
```

Pre-wired IDE run configurations in `.run/`: `Build Plugin`, `Build Plugin Offline`, `Run IDE with Plugin`.

Plugin/platform versions, since/until builds, and bundled-plugin list live in `gradle.properties`. Library versions in `gradle/libs.versions.toml`. Do not hardcode versions in `build.gradle.kts`.

The plugin's `<description>` in `plugin.xml` is overwritten at build time from the `<!-- Plugin description -->` ... `<!-- Plugin description end -->` block in `README.md`. The build fails if those markers are missing.

`changeNotes` is rendered from `CHANGELOG.md` keyed by `pluginVersion`.

## Architecture

### Two feature surfaces

1. **Inline inspection lenses** — rendered next to lines with `HighlightInfo`s, clickable to dump the inspection + quick-fix class names.
2. **Help menu tooling** — `Dump Editor Intentions...` and (when `com.intellij.java` is present) `Show Git Log For Classes...` for jumping straight to inspection sources in an `intellij-community` checkout.

### Lens pipeline

Wiring runs through these listeners/services (do not reorder without understanding lifetimes):

- `InspectionLensFileOpenedListener` (project-level `FileOpenedSyncListener`) installs lenses on each newly opened `TextEditor`.
- `InspectionLensPluginListener` (application-level `DynamicPluginListener`) reinstalls/uninstalls on dynamic plugin load/unload.
- `InspectionLens.install(editor)` creates an intersected lifetime of `InspectionLensPluginDisposableService` and the editor's own lifetime, then registers a `LensMarkupModelListener` on the editor's `DocumentMarkupModel`.
- `LensMarkupModelListener` filters `RangeHighlighter`s through `LensSettingsState.severityFilter` and forwards to `EditorLensManagerDispatcher`, which batches commands to `EditorLensManager`.
- `EditorLensManager` keeps an `IdentityHashMap<RangeHighlighter, EditorLens>` per editor and renders via `LensRenderer` (subclass of `HintRenderer`) inside `EditorLensInlay`s.
- `LensSeverity` / `LensSeverityFilter` / `LensSettingsState` (persistent component, storage `ide.inspector.xml`) drive what renders. `LensApplicationConfigurable` exposes settings under **Settings | Tools | IDE Inspector**.
- `InspectionEditorMouseListener` (registered as `editorFactoryMouseListener` in `plugin.xml`) intercepts clicks on lens inlays and calls `LensRenderer.dumpInspection()` to show `IntentionDumpDialog`.

### Intention / inspection dumping

- `LensRenderer.setPropertiesFrom` and `dumpInspection` read `HighlightInfo.inspectionToolId`, then `InspectionProfileManager.currentProfile.getInspectionTool(...)` to recover the inspection's `implementationClass`. When `inspectionToolId` is null it falls back to reflection on `HighlightInfo`'s private `toolId` field.
- Quick-fix classes are pulled via `info.findRegisteredQuickFix { desc, range -> ... }` and resolved through `ReportingClassSubstitutor.getClassToReport(action)`. Do **not** read `info.quickFixActionRanges` directly — it can be null and crashes (see `bug.md`); use `collectQuickFixActionRanges()` instead.
- `IntentionDumpAction` (registered in `HelpMenu`, `EditorPopupMenu`, `EditorContextBarMenu`) iterates `CachedIntentions` (inspection fixes, intentions, error fixes, gutters, notifications), applies `IntentionsOrderProvider`, and shows `IntentionDumpDialog` with both human-readable and class-name lists.
- `ShowGitLogForClassesAction` (declared in `plugin_java.xml`, only loads if `com.intellij.java` is present) takes the class names from the clipboard / multi-line input, resolves them through `ClassFinderService.getPsiFiles`, navigates to the file, and opens VCS file history via `AbstractVcsHelper.showFileHistory`.

### Tool window

`IdeHelperToolWindowFactory` (Java) registers a `"IDE Properties"` tool window that dumps `PathManager` paths, registered `LanguageTestCreators`, and JRE info. Not on the lens hot path.

### Optional dependencies

`plugin.xml` declares two optional plugin deps with their own config files:

- `tanvd.grazi` → `compatibility/IdeInspector-Grazie.xml` (currently empty / commented out).
- `com.intellij.java` → `plugin_java.xml` (registers `ShowGitLogForClassesAction` only when Java support is loaded — keeps the plugin loadable in WebStorm/etc.).

### Package layout quirk

Most code is under `com.intellij.support.ide.inspector.*` (Kotlin). The tool window factory and the intention dump dialog live under `com.jetbrains.support.ide.inspector.*` (Java). Both packages are part of the same plugin — keep this in mind when searching.

### Known gotcha — stale PLUGIN_ID

`InspectionLens.PLUGIN_ID = "com.chylex.intellij.inspectionlens"` does **not** match the real plugin id in `plugin.xml` (`com.intellij.support.ide.inspector`). `InspectionLensPluginListener` gates install/uninstall on `PLUGIN_ID`, so the dynamic-plugin path will not match during plugin reload. The `FileOpenedSyncListener` path still works for normal editor opens. If you change anything in this area, fix the constant.

## Tests

JUnit 5 (`org.junit.jupiter`), platform test framework via `testFramework(TestFrameworkType.Platform)`. Tests live in `src/test/kotlin`. `EditorLensTest` pins assumptions about `HighlightSeverity.DEFAULT_SEVERITIES` (min `10`, max `400`) — if those IntelliJ constants drift, rework `EditorLensInlay.getInlayHintPriority` rather than just bumping the test.

## Notes from existing repo files

- `README.md` is the source of truth for the user-facing description (build extracts it).
- `bug.md` records the historical `quickFixActionRanges must not be null` NPE — already worked around in `LensRenderer.collectQuickFixActionRanges`. Keep that defensive read.
- `guide.md` is the user-facing how-to (install in IDEA + IJ source repo, click inspection, copy class, `Help | Show Git Log For Classes...`). Mirror any user-flow changes there.
