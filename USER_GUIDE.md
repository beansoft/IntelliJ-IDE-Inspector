# IDE Inspector — User Guide

A plugin to quickly find intention/inspection classes in IntelliJ-based IDEs and explore their git history.

## Table of Contents

1. [Installation](#installation)
2. [Quick Start](#quick-start)
3. [Inline Inspection Lenses](#inline-inspection-lenses)
4. [Dumping Intentions and Inspections](#dumping-intentions-and-inspections)
5. [Viewing Inspection Git History](#viewing-inspection-git-history)
6. [Settings and Configuration](#settings-and-configuration)
7. [Tool Window: IDE Properties](#tool-window-ide-properties)
8. [MCP Integration](#mcp-integration)

---

## Installation

### From JetBrains Marketplace

1. Open IntelliJ IDEA (or other JetBrains IDE).
2. Go to **Settings | Plugins** (or **Preferences | Plugins** on macOS).
3. Search for **IDE Inspector**.
4. Click **Install** and restart the IDE.

### From Disk

1. Download the `.zip` plugin package from [GitHub Releases](https://github.com/beansoft/IntelliJ-IDE-Inspector/releases).
2. Open your IDE.
3. Go to **Settings | Plugins** (or **Preferences | Plugins**).
4. Click **⚙️ | Install plugin from disk...**.
5. Select the downloaded `.zip` file.
6. Restart the IDE.

### Supported IDEs

- **IntelliJ IDEA 2026.1+** (Ultimate edition)
- **WebStorm 2026.1+**
- **PyCharm 2026.1+**
- **CLion 2026.1+**
- **Rider**: Not supported due to platform differences.

---

## Quick Start

### Three ways to find inspection classes:

1. **Click directly on inspection result** in the editor.
2. **Use menu: Help | Dump Editor Intentions** to see all intentions/inspections on the current line.
3. **Use the IDE Properties tool window** (covered below) to browse inspections globally.

Copy the inspection/intention class name, then use **Help | Show Git Log For Classes...** (requires the IntelliJ Community source repo to be open).

---

## Inline Inspection Lenses

### What are inline lenses?

By default, the plugin displays small, clickable markers (lenses) next to lines with errors, warnings, and other inspections. This makes it easy to see which inspections apply to a given line.

### What severities are shown?

By default:
- Errors
- Warnings
- Weak Warnings
- Server Problems
- Grammar Errors
- Typos
- Other high-severity inspections

### Click a lens

Click any lens marker to open a dialog showing:
- The inspection's **class name** (e.g., `com.intellij.codeInspection.unused.UnusedDeclarationInspection`)
- Any quick-fix **action class names** (e.g., `com.intellij.codeInspection.unused.RemoveUnusedVariableFix`)

Copy the class name and paste it into **Help | Show Git Log For Classes...** to jump to the source.

---

## Dumping Intentions and Inspections

### Via Menu

1. Click anywhere in an editor line.
2. Go to **Help | Dump Editor Intentions**.
3. A dialog appears listing all available intentions, inspections, and quick fixes at that caret position.
4. Each item shows:
   - Human-readable description
   - Implementation class name (copyable)

### Via Lens Click

Click any inline lens marker to see the specific inspection and its quick fixes for that location.

---

## Viewing Inspection Git History

> **Prerequisite**: You must configure the path to a local `intellij-community` repository checkout for this feature to work.

### 1. Configure the IDEA Source Repository Path

1. Go to **Settings | Tools | IDE Inspector** (or **Preferences | Tools | IDE Inspector** on macOS).
2. Under **IDEA Source Repository**, click **Browse...** and select your local `intellij-community` checkout directory.
   - Example: `/path/to/intellij-community/`
3. Click **OK** to save.

### 2. Open the IDE Properties Tool Window

1. Go to **View | Tool Windows | IDE Properties**.
2. Click the **Inspections** tab (in the tool window).
3. A tree view of all available inspections appears, grouped by category.

### 3. Select an Inspection and View Its Git History

1. In the **Inspections** tab, find and click an inspection in the tree.
2. Click the **Show Git History** button (history icon) in the tool window's toolbar.
3. A popup appears showing recent commits affecting that inspection's source file:
   - **Date** — when the commit was made
   - **Author** — who wrote the commit
   - **Hash** — commit hash (8 chars + full hash on hover)
   - **Subject** — commit message

### Popup Actions

- **Double-click a row** → Copy full commit hash to clipboard
- **Right-click a row** → Context menu:
  - **Copy Hash** — copy to clipboard
  - **Open in GitHub** — open the commit in GitHub (requires internet)
- **Footer buttons**:
  - **Copy Path** — copy the source file path to clipboard
  - **Open File** — open the source file in a new editor tab

---

## Settings and Configuration

### Access Settings

1. Go to **Settings | Tools | IDE Inspector** (or **Preferences | Tools | IDE Inspector** on macOS).

### Options

| Setting | Description | Default |
|---------|-------------|---------|
| **IDEA Source Repository** | Path to a local `intellij-community` checkout. Required for git history lookup. | (empty) |
| **Visible Severities** | Choose which inspection severities to display as inline lenses. | Errors, Warnings, Weak Warnings, etc. |

### Configuring Visible Severities

1. In **Settings | Tools | IDE Inspector**, find the **Visible Severities** section.
2. Check or uncheck severity levels to show/hide inline lenses:
   - **Error** — critical issues
   - **Warning** — potential problems
   - **Weak Warning** — minor issues
   - **Server Problem** — IDE server issues
   - **Grammar Error** — grammar/spell-check issues
   - **Typo** — typo detection
   - Custom severities defined by plugins

3. Click **OK** to apply.

---

## Tool Window: IDE Properties

The **IDE Properties** tool window displays plugin-related information and inspection browsing.

### Access

1. Go to **View | Tool Windows | IDE Properties**.

### Tabs

#### Inspections Tab

- **Tree view** of all available inspections, grouped by category (e.g., Java, Kotlin, XML).
- **Select an inspection** → its class name appears in the status bar.
- **Click toolbar button "Show Git History"** → opens a popup with recent commits (see [Viewing Inspection Git History](#viewing-inspection-git-history)).

#### Special Paths Tab

- **Browse IDE special paths** (system paths, user paths, plugin paths, etc.).
- Useful for debugging or understanding IDE directory structure.
- Paths include:
  - System config directory
  - User plugins directory
  - IDE installation directory
  - Project directories
  - Etc.

---

## MCP Integration

> **Note**: MCP (Model Context Protocol) tools are available when the plugin is running in an IDE that supports the JetBrains MCP Server plugin (bundled in IDE 2024.2+).

### What is MCP?

MCP is a protocol that allows external clients (like Claude Code, language models, or other tools) to request operations from your IDE via a structured interface.

### MCP Tools Provided by IDE Inspector

The plugin exposes the following tools via MCP:

#### 1. `show_file_history`

Opens the git history for a file in the IDE's Version Control tool window.

**Parameters:**
- `filePath` (string): Absolute path or project-relative path to the file.

**Example:**
```
Tool: show_file_history
Input: filePath = "/Users/you/intellij-community/platform/platform-api/src/com/intellij/openapi/project/Project.java"
```

**Result:** Opens the **Version Control** tool window showing the commit history for `Project.java`.

#### 2. `show_file_history_for_fqn`

Resolves a class by its fully qualified name (FQN) and opens its git history in the IDE's Version Control tool window. Requires the **IDEA Source Repository** path to be configured.

**Parameters:**
- `fqn` (string): Fully qualified class name (e.g., `com.intellij.openapi.project.Project`).

**Example:**
```
Tool: show_file_history_for_fqn
Input: fqn = "com.intellij.openapi.project.Project"
```

**Result:**
1. Resolves the class to its source file in the configured `intellij-community` checkout.
2. Opens the **Version Control** tool window showing the commit history for that file.

#### 3. `show_git_log_for_classes`

Opens the git log for multiple classes in the IDE's Version Control tool window.

**Parameters:**
- `classes` (array of strings): List of fully qualified class names.

**Example:**
```
Tool: show_git_log_for_classes
Input: classes = [
  "com.intellij.codeInspection.unused.UnusedDeclarationInspection",
  "com.intellij.codeInspection.naming.NamingConventionInspection"
]
```

**Result:** Opens the **Version Control** tool window showing the combined history for both classes.

### Using MCP with Claude Code

If you are using Claude Code with the JetBrains MCP Server integration:

1. **Configure the IDEA Source Repository path** (see [Settings](#settings-and-configuration)).
2. **Claude Code will automatically detect** the available MCP tools.
3. **When Claude needs to show you inspection source history**, it will use `show_file_history_for_fqn` to jump directly to the right commit.

---

## Troubleshooting

### Lenses not appearing

- **Check settings**: Go to **Settings | Tools | IDE Inspector** and verify that severity levels are not all unchecked.
- **Reload the IDE**: Sometimes a full restart is needed.
- **Check file type**: Lenses only appear for editors with inspections (e.g., source code files).

### Git history not found

- **Configure the path**: Go to **Settings | Tools | IDE Inspector** and set the **IDEA Source Repository** path.
- **Verify the path**: Make sure it points to a valid `intellij-community` checkout with a `.git` directory.
- **Check the class name**: Ensure the inspection class name is correct and exists in that repository.

### MCP tools not available

- **Check IDE version**: MCP support requires IDE 2024.2 or later.
- **Verify MCP Server plugin**: Go to **Settings | Plugins** and search for "MCP Server". It should be bundled and enabled.
- **Restart the IDE**: Changes to plugin dependencies sometimes require a restart.

---

## Tips and Tricks

1. **Split the Version Control tool window**: In the **Version Control** tool window, right-click the **History** tab and select **Split 'History' Group**. This allows you to view multiple file histories side-by-side.

2. **Use the external git log**: When the IDEA source repository is open in a separate IDE window, use **Help | Show External Git Log For Classes...** to open its history without switching windows.

3. **Keyboard shortcut for intentions**: Use the standard IntelliJ shortcut (usually **Alt+Enter** or **Ctrl+Enter**) to open the intention menu on any line.

4. **Customize lint severities**: If certain inspection severities clutter your editor, uncheck them in **Settings | Tools | IDE Inspector** under **Visible Severities**.

---

## Support and Feedback

- **GitHub Issues**: [beansoft/IntelliJ-IDE-Inspector](https://github.com/beansoft/IntelliJ-IDE-Inspector/issues)
- **Plugin Page**: [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/INSERT_PLUGIN_ID)

---

## Credits

Inspired by and extends [Inspection Lens](https://plugins.jetbrains.com/plugin/19678-inspection-lens).
