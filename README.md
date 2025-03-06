# IntelliJ IDEInspector

Quickly find intention/inspection classes in the IDE.

1. Install the plugin in IDEA and open the IDEA source repo(eg:https://github.com/JetBrains/intellij-community) project from disk at https://github.com/beansoft/IntelliJ-IDE-Inspector/releases.
2. Install the plugin in the same IDEA or another IDE e.g., WebStorm(does not support Rider)
3. Open any project and click the inspection result string. Also, on any line in the editor, you can try the `Help | Dump Editor Intentions menu.` to display all intention action's class list.
4. Copy classes name.
5. Open the IDEA source repo in IDEA, then use the menu `Help | Show Git Log For Classes...`, the corresponding class file and the Git Log will be shown.

Tip: In the Git tool window, right-click the **History** tab and select `Split 'History' Group`

Inspired by [Inspection Lens](https://plugins.jetbrains.com/plugin/17302-inlineerror) for IntelliJ Platform.

> By default, the plugin shows **Errors**, **Warnings**, **Weak Warnings**, **Server Problems**, **Grammar Errors**, **Typos**, and other inspections with a high enough severity level. Configure visible severities in **Settings | Tools | Inspection Lens**.

![Screenshot](.github/readme/intellij.png)


<!-- Plugin description -->
Displays errors, warnings, and other inspections inline with inspection dumps. Highlights the background of lines with inspections. Supports light and dark themes out of the box.
<br><br>
By default, the plugin shows <b>Errors</b>, <b>Warnings</b>, <b>Weak Warnings</b>, <b>Server Problems</b>, <b>Grammar Errors</b>, <b>Typos</b>, and other inspections with a high enough severity level. Configure visible severities in <b>Settings | Tools | Inspection Lens</a>.
<br><br>
Inspired by and <a href="https://plugins.jetbrains.com/plugin/19678-inspection-lens">Inspection Lens</a> for IntelliJ Platform.
<!-- Plugin description end -->
