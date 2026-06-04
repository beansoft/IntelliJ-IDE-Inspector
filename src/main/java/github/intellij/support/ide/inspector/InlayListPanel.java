package github.intellij.support.ide.inspector;

import com.intellij.codeInsight.hints.InlayGroup;
import com.intellij.codeInsight.hints.declarative.InlayHintsProviderExtensionBean;
import com.intellij.codeInsight.hints.settings.InlayProviderSettingsModel;
import com.intellij.codeInsight.hints.settings.InlaySettingsProvider;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.ide.plugins.cl.PluginAwareClassLoader;
import com.intellij.lang.Language;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.DumbAwareToggleAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.ui.ComboboxSpeedSearch;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.TreeSpeedSearch;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.tree.TreeUtil;
import github.intellij.support.ide.inspector.source.InspectionGitHistoryPopup;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Readonly inlay-hint provider tab. Lists all {@link InlayProviderSettingsModel}s
 * contributed via {@link InlaySettingsProvider} EP, grouped by inlay group,
 * language, or providing plugin. No enable/disable, no settings mutation.
 */
public final class InlayListPanel {

  private InlayListPanel() {}

  public static @NotNull JComponent create(@NotNull Project project) {
    return new Panel(project).root;
  }

  static final String LANG_ANY = "(Any language)";
  static final String LANG_NONE = "(No language)";
  static final String PLUGIN_ANY = "(Any plugin)";
  static final String PLUGIN_UNKNOWN = "(Unknown)";

  private enum GroupMode { GROUP, LANGUAGE, PLUGIN }

  private static final class Row {
    final String name;
    final String id;
    final String groupTitle;
    final String languageId;
    final String languageDisplay;
    final String implClass;
    final String pluginId;
    final String pluginName;
    final String description;

    Row(InlayProviderSettingsModel m, Map<String, String> declarativeImplByProviderId) {
      this.name = nullSafe(m.getName());
      this.id = nullSafe(m.getId());
      String gt = "(Other)";
      try {
        InlayGroup g = m.getGroup();
        if (g != null) gt = nullSafeOr(g.title(), g.name());
      } catch (Throwable ignored) {}
      this.groupTitle = gt;

      Language lang = null;
      try { lang = m.getLanguage(); } catch (Throwable ignored) {}
      this.languageId = lang == null ? "" : lang.getID();
      this.languageDisplay = lang == null ? "" : nullSafeOr(lang.getDisplayName(), lang.getID());

      this.implClass = resolveImplClass(m, declarativeImplByProviderId);

      String pid = "";
      String pname = "";
      try {
        ClassLoader cl = m.getClass().getClassLoader();
        if (cl instanceof PluginAwareClassLoader pacl) {
          PluginId pluginIdObj = pacl.getPluginId();
          if (pluginIdObj != null) {
            pid = pluginIdObj.getIdString();
            IdeaPluginDescriptor pd = PluginManagerCore.getPlugin(pluginIdObj);
            if (pd != null && pd.getName() != null) pname = pd.getName();
          }
        }
      } catch (Throwable ignored) {}
      this.pluginId = pid;
      this.pluginName = pname.isEmpty() ? pid : pname;

      String desc = "";
      try {
        String d = m.getDescription();
        if (d != null) desc = d;
      } catch (Throwable ignored) {}
      this.description = desc;
    }

    String pluginBucket() { return pluginName.isEmpty() ? PLUGIN_UNKNOWN : pluginName; }
    String languageBucket() {
      if (languageId.isEmpty()) return LANG_NONE;
      return languageDisplay.isEmpty() ? languageId : languageDisplay;
    }
    @Override public String toString() { return name; }

    private static String nullSafe(String s) { return s == null ? "" : s; }
    private static String nullSafeOr(String s, String fallback) {
      return (s == null || s.isEmpty()) ? (fallback == null ? "" : fallback) : s;
    }

    /**
     * Resolve the real {@link com.intellij.codeInsight.hints.declarative.InlayHintsProvider}
     * impl class for declarative models. {@code DeclarativeHintsProviderSettingsModel} is a
     * generic wrapper around an {@code InlayHintsProviderExtensionBean}; calling
     * {@code m.getClass().getName()} for those would always return the wrapper class.
     *
     * Resolution: look up the model's id in the EP-derived
     * {@code providerId -> implementationClass} map (built once per load by reading
     * {@code InlayHintsProviderExtensionBean.EP.extensionList} — no provider instantiation,
     * no reflection). Falls back to {@code m.getClass().getName()} for non-declarative
     * models (code vision, v1 inlay hints, etc.) whose own class already is the impl.
     */
    private static String resolveImplClass(InlayProviderSettingsModel m, Map<String, String> declarativeImplByProviderId) {
      String id = m.getId();
      if (id != null && !id.isEmpty()) {
        String impl = declarativeImplByProviderId.get(id);
        if (impl != null && !impl.isEmpty()) return impl;
      }
      return m.getClass().getName();
    }
  }

  private static final class LangItem {
    final String label;
    final String languageId; // "" any, null none
    LangItem(String label, String languageId) { this.label = label; this.languageId = languageId; }
    @Override public String toString() { return label; }
  }

  private static final class PluginItem {
    final String label;
    final String pluginName; // "" any, null unknown
    PluginItem(String label, String pluginName) { this.label = label; this.pluginName = pluginName; }
    @Override public String toString() { return label; }
  }

  private static final class Panel {
    final JComponent root;
    private final Project project;
    private final ComboBox<LangItem> languageCombo = new ComboBox<>();
    private final ComboBox<PluginItem> pluginCombo = new ComboBox<>();
    private final SearchTextField filterField = new SearchTextField();
    private final SearchTextField classFilterField = new SearchTextField();
    private final DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("Inlay providers");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(rootNode);
    private final Tree tree = new Tree(treeModel);
    private final JEditorPane description = new JEditorPane();
    private final JBLabel status = new JBLabel("");
    private volatile long loadToken = 0;
    private List<Row> allRows = new ArrayList<>();
    private GroupMode groupMode = GroupMode.GROUP;
    private boolean limitToCurrentFile = false;
    private boolean suppressFilterEvents = false;

    Panel(@NotNull Project p) {
      this.project = p;

      tree.setRootVisible(false);
      tree.setShowsRootHandles(true);
      tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
      tree.addTreeSelectionListener(this::onTreeSelected);
      TreeSpeedSearch.installOn(tree);

      description.setEditable(false);
      description.setContentType("text/html");
      description.setBorder(JBUI.Borders.empty(8));

      JBSplitter splitter = new JBSplitter(false, 0.4f);
      splitter.setFirstComponent(new JBScrollPane(tree));
      splitter.setSecondComponent(new JBScrollPane(description));

      JPanel top = new JPanel(new BorderLayout(8, 0));
      top.setBorder(JBUI.Borders.empty(4, 6));

      JPanel row1 = new JPanel();
      row1.setLayout(new BoxLayout(row1, BoxLayout.X_AXIS));
      row1.add(new JBLabel("Filter:"));
      row1.add(Box.createHorizontalStrut(6));
      filterField.setPreferredSize(new Dimension(220, filterField.getPreferredSize().height));
      row1.add(filterField);
      row1.add(Box.createHorizontalStrut(12));
      row1.add(new JBLabel("Class:"));
      row1.add(Box.createHorizontalStrut(6));
      classFilterField.setPreferredSize(new Dimension(220, classFilterField.getPreferredSize().height));
      classFilterField.getTextEditor().getEmptyText().setText("Filter by class name");
      row1.add(classFilterField);
      row1.add(Box.createHorizontalStrut(12));
      row1.add(status);

      JPanel row2 = new JPanel();
      row2.setLayout(new BoxLayout(row2, BoxLayout.X_AXIS));
      row2.setBorder(JBUI.Borders.emptyTop(4));
      row2.add(new JBLabel("Language:"));
      row2.add(Box.createHorizontalStrut(6));
      languageCombo.setPreferredSize(new Dimension(180, languageCombo.getPreferredSize().height));
      ComboboxSpeedSearch.installOn(languageCombo);
      row2.add(languageCombo);
      row2.add(Box.createHorizontalStrut(12));
      row2.add(new JBLabel("Plugin:"));
      row2.add(Box.createHorizontalStrut(6));
      pluginCombo.setPreferredSize(new Dimension(240, pluginCombo.getPreferredSize().height));
      ComboboxSpeedSearch.installOn(pluginCombo);
      row2.add(pluginCombo);
      row2.add(Box.createHorizontalGlue());

      JPanel west = new JPanel();
      west.setLayout(new BoxLayout(west, BoxLayout.Y_AXIS));
      west.add(row1);
      west.add(row2);

      top.add(west, BorderLayout.WEST);
      top.add(buildToolbar(top), BorderLayout.EAST);

      JPanel main = new JPanel(new BorderLayout());
      main.add(top, BorderLayout.NORTH);
      main.add(splitter, BorderLayout.CENTER);
      this.root = main;

      filterField.addDocumentListener(new DocumentAdapter() {
        @Override protected void textChanged(@NotNull DocumentEvent e) { rebuildTree(); }
      });
      classFilterField.addDocumentListener(new DocumentAdapter() {
        @Override protected void textChanged(@NotNull DocumentEvent e) { rebuildTree(); }
      });
      languageCombo.addActionListener(e -> { if (!suppressFilterEvents) rebuildTree(); });
      pluginCombo.addActionListener(e -> { if (!suppressFilterEvents) rebuildTree(); });

      loadRows();
    }

    private JComponent buildToolbar(JComponent target) {
      DefaultActionGroup group = new DefaultActionGroup();
      group.add(new DumbAwareAction("Refresh", "Reload inlay providers", com.intellij.icons.AllIcons.Actions.Refresh) {
        @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
        @Override public void actionPerformed(@NotNull AnActionEvent e) { loadRows(); }
      });
      group.add(new DumbAwareAction("Expand All", "Expand every group", com.intellij.icons.AllIcons.Actions.Expandall) {
        @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        @Override public void actionPerformed(@NotNull AnActionEvent e) { TreeUtil.expandAll(tree); }
      });
      group.add(new DumbAwareAction("Collapse All", "Collapse every group", com.intellij.icons.AllIcons.Actions.Collapseall) {
        @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        @Override public void actionPerformed(@NotNull AnActionEvent e) { TreeUtil.collapseAll(tree, 1); }
      });
      group.add(new Separator());
      group.add(new DumbAwareToggleAction("Group by Inlay Group",
        "Group providers by their inlay group (Parameters, Types, …)",
        com.intellij.icons.AllIcons.Actions.GroupBy) {
        @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        @Override public boolean isSelected(@NotNull AnActionEvent e) { return groupMode == GroupMode.GROUP; }
        @Override public void setSelected(@NotNull AnActionEvent e, boolean state) {
          if (state && groupMode != GroupMode.GROUP) { groupMode = GroupMode.GROUP; rebuildTree(); }
        }
      });
      group.add(new DumbAwareToggleAction("Group by Language",
        "Group providers by target language",
        com.intellij.icons.AllIcons.Actions.GroupByFile) {
        @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        @Override public boolean isSelected(@NotNull AnActionEvent e) { return groupMode == GroupMode.LANGUAGE; }
        @Override public void setSelected(@NotNull AnActionEvent e, boolean state) {
          if (state && groupMode != GroupMode.LANGUAGE) { groupMode = GroupMode.LANGUAGE; rebuildTree(); }
        }
      });
      group.add(new DumbAwareToggleAction("Group by Plugin",
        "Group providers by providing plugin",
        com.intellij.icons.AllIcons.Actions.GroupByModule) {
        @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        @Override public boolean isSelected(@NotNull AnActionEvent e) { return groupMode == GroupMode.PLUGIN; }
        @Override public void setSelected(@NotNull AnActionEvent e, boolean state) {
          if (state && groupMode != GroupMode.PLUGIN) { groupMode = GroupMode.PLUGIN; rebuildTree(); }
        }
      });
      group.add(new DumbAwareToggleAction("Limit to Current File",
        "Show only providers for the language of the currently open editor file",
        com.intellij.icons.AllIcons.Actions.PreviewDetails) {
        @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        @Override public boolean isSelected(@NotNull AnActionEvent e) { return limitToCurrentFile; }
        @Override public void setSelected(@NotNull AnActionEvent e, boolean state) {
          limitToCurrentFile = state;
          rebuildTree();
        }
      });
      group.add(new Separator());
      group.add(new DumbAwareAction("Show Git History",
        "Show recent commits for selected inlay provider class in configured IDEA source repo",
        com.intellij.icons.AllIcons.Vcs.History) {
        @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        @Override public void update(@NotNull AnActionEvent e) {
          e.getPresentation().setEnabled(selectedRow() != null);
        }
        @Override public void actionPerformed(@NotNull AnActionEvent e) {
          Row r = selectedRow();
          if (r == null || r.implClass == null || r.implClass.isEmpty()) return;
          InspectionGitHistoryPopup.INSTANCE.showExternalGitLog(project, r.implClass, tree);
        }
      });
      group.add(new DumbAwareAction("Copy Class Name", "Copy implementation class of selected provider",
        com.intellij.icons.AllIcons.Actions.Copy) {
        @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        @Override public void update(@NotNull AnActionEvent e) {
          e.getPresentation().setEnabled(selectedRow() != null);
        }
        @Override public void actionPerformed(@NotNull AnActionEvent e) {
          Row r = selectedRow();
          if (r != null && r.implClass != null) {
            CopyPasteManager.getInstance().setContents(new StringSelection(r.implClass));
          }
        }
      });
      ActionToolbar toolbar = ActionManager.getInstance()
        .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, group, true);
      toolbar.setTargetComponent(target);
      return toolbar.getComponent();
    }

    /**
     * Snapshot the declarative inlay-hint provider EP into a
     * {@code providerId -> implementationClass} map. Reads the XML-declared FQN directly
     * from each {@link InlayHintsProviderExtensionBean}; no provider class is loaded.
     */
    private static Map<String, String> buildDeclarativeImplMap() {
      Map<String, String> result = new HashMap<>();
      try {
        List<InlayHintsProviderExtensionBean> beans =
          InlayHintsProviderExtensionBean.Companion.getEP().getExtensionList();
        for (InlayHintsProviderExtensionBean bean : beans) {
          String pid = bean.getProviderId();
          String impl = bean.getImplementationClass();
          if (pid != null && !pid.isEmpty() && impl != null && !impl.isEmpty()) {
            result.putIfAbsent(pid, impl);
          }
        }
      } catch (Throwable ignored) {}
      return result;
    }

    private void loadRows() {
      final long token = ++loadToken;
      status.setText("Loading…");
      ApplicationManager.getApplication().executeOnPooledThread(() -> {
        List<Row> rows = new ArrayList<>();
        Map<String, String> declarativeImplByProviderId = buildDeclarativeImplMap();
        try {
          for (InlaySettingsProvider provider : InlaySettingsProvider.EP.INSTANCE.getExtensions()) {
            Collection<Language> langs;
            try {
              langs = provider.getSupportedLanguages(project);
            } catch (Throwable t) { continue; }
            for (Language lang : langs) {
              try {
                List<InlayProviderSettingsModel> models = provider.createModels(project, lang);
                for (InlayProviderSettingsModel m : models) {
                  try { rows.add(new Row(m, declarativeImplByProviderId)); } catch (Throwable ignored) {}
                }
              } catch (Throwable ignored) {}
            }
          }
          rows.sort(Comparator
            .comparing((Row r) -> r.groupTitle, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(r -> r.languageDisplay, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(r -> r.name, String.CASE_INSENSITIVE_ORDER));
        } catch (Throwable t) {
          ApplicationManager.getApplication().invokeLater(() -> {
            if (token != loadToken) return;
            status.setText("Load failed: " + t.getMessage());
          });
          return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
          if (token != loadToken) return;
          allRows = rows;
          rebuildFilterCombos();
          rebuildTree();
          description.setText("");
        });
      });
    }

    private void rebuildFilterCombos() {
      suppressFilterEvents = true;
      try {
        Object prevLang = languageCombo.getSelectedItem();
        TreeMap<String, String> langs = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        boolean langHasEmpty = false;
        for (Row r : allRows) {
          if (r.languageId.isEmpty()) { langHasEmpty = true; continue; }
          String disp = r.languageDisplay.isEmpty() ? r.languageId : r.languageDisplay;
          langs.putIfAbsent(disp, r.languageId);
        }
        DefaultComboBoxModel<LangItem> langModel = new DefaultComboBoxModel<>();
        langModel.addElement(new LangItem(LANG_ANY, ""));
        if (langHasEmpty) langModel.addElement(new LangItem(LANG_NONE, null));
        for (Map.Entry<String, String> e : langs.entrySet()) {
          langModel.addElement(new LangItem(e.getKey(), e.getValue()));
        }
        languageCombo.setModel(langModel);
        if (prevLang instanceof LangItem pl) {
          for (int i = 0; i < langModel.getSize(); i++) {
            if (java.util.Objects.equals(langModel.getElementAt(i).languageId, pl.languageId)) {
              languageCombo.setSelectedIndex(i);
              break;
            }
          }
        }

        Object prevPlugin = pluginCombo.getSelectedItem();
        TreeMap<String, String> plugins = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        boolean hasUnknown = false;
        for (Row r : allRows) {
          if (r.pluginName.isEmpty()) { hasUnknown = true; continue; }
          plugins.putIfAbsent(r.pluginName, r.pluginName);
        }
        DefaultComboBoxModel<PluginItem> pluginModel = new DefaultComboBoxModel<>();
        pluginModel.addElement(new PluginItem(PLUGIN_ANY, ""));
        if (hasUnknown) pluginModel.addElement(new PluginItem(PLUGIN_UNKNOWN, null));
        for (String pn : plugins.keySet()) pluginModel.addElement(new PluginItem(pn, pn));
        pluginCombo.setModel(pluginModel);
        if (prevPlugin instanceof PluginItem pp) {
          for (int i = 0; i < pluginModel.getSize(); i++) {
            if (java.util.Objects.equals(pluginModel.getElementAt(i).pluginName, pp.pluginName)) {
              pluginCombo.setSelectedIndex(i);
              break;
            }
          }
        }
      } finally {
        suppressFilterEvents = false;
      }
    }

    private void rebuildTree() {
      String q = filterField.getText().trim().toLowerCase(Locale.ROOT);
      String classQ = classFilterField.getText().trim().toLowerCase(Locale.ROOT);
      LangItem langSel = (LangItem) languageCombo.getSelectedItem();
      PluginItem pluginSel = (PluginItem) pluginCombo.getSelectedItem();
      Set<String> currentFileLangs = limitToCurrentFile ? currentFileLanguageIds() : null;

      rootNode.removeAllChildren();
      Map<String, DefaultMutableTreeNode> groupCache = new HashMap<>();
      int leafCount = 0;
      for (Row r : allRows) {
        if (!matches(r, q, classQ, langSel, pluginSel, currentFileLangs)) continue;
        DefaultMutableTreeNode parent;
        String bucket = switch (groupMode) {
          case PLUGIN -> r.pluginBucket();
          case LANGUAGE -> r.languageBucket();
          default -> r.groupTitle;
        };
        parent = groupCache.computeIfAbsent(bucket, key -> {
          DefaultMutableTreeNode n = new DefaultMutableTreeNode(new GroupNode(key));
          rootNode.add(n);
          return n;
        });
        parent.add(new DefaultMutableTreeNode(r));
        leafCount++;
      }
      treeModel.reload();
      if (!q.isEmpty()) TreeUtil.expandAll(tree); else TreeUtil.expand(tree, 1);

      List<String> parts = new ArrayList<>();
      if (langSel != null && !(langSel.languageId != null && langSel.languageId.isEmpty())) {
        parts.add(langSel.languageId == null ? "no lang" : "lang=" + langSel.label);
      }
      if (pluginSel != null && !(pluginSel.pluginName != null && pluginSel.pluginName.isEmpty())) {
        parts.add(pluginSel.pluginName == null ? "unknown plugin" : "plugin=" + pluginSel.pluginName);
      }
      if (currentFileLangs != null) {
        parts.add("current-file" + (currentFileLangs.isEmpty() ? "(none)" : ""));
      }
      String filterNote = parts.isEmpty() ? "" : "  [" + String.join(", ", parts) + "]";
      status.setText(leafCount + " / " + allRows.size() + " providers" + filterNote);
    }

    private boolean matches(Row r, String needle, String classNeedle, LangItem lang, PluginItem plugin, Set<String> currentFileLangs) {
      if (!needle.isEmpty()) {
        boolean ok = r.name.toLowerCase(Locale.ROOT).contains(needle)
          || r.id.toLowerCase(Locale.ROOT).contains(needle)
          || r.implClass.toLowerCase(Locale.ROOT).contains(needle)
          || r.groupTitle.toLowerCase(Locale.ROOT).contains(needle)
          || r.languageDisplay.toLowerCase(Locale.ROOT).contains(needle)
          || r.pluginName.toLowerCase(Locale.ROOT).contains(needle);
        if (!ok) return false;
      }
      if (!classNeedle.isEmpty()) {
        if (!r.implClass.toLowerCase(Locale.ROOT).contains(classNeedle)) return false;
      }
      if (lang != null) {
        if (lang.languageId == null) {
          if (!r.languageId.isEmpty()) return false;
        } else if (!lang.languageId.isEmpty()) {
          if (!lang.languageId.equalsIgnoreCase(r.languageId)) return false;
        }
      }
      if (plugin != null) {
        if (plugin.pluginName == null) {
          if (!r.pluginName.isEmpty()) return false;
        } else if (!plugin.pluginName.isEmpty()) {
          if (!plugin.pluginName.equalsIgnoreCase(r.pluginName)) return false;
        }
      }
      if (currentFileLangs != null) {
        if (!r.languageId.isEmpty() && !currentFileLangs.contains(r.languageId)) return false;
      }
      return true;
    }

    private Set<String> currentFileLanguageIds() {
      Set<String> result = new HashSet<>();
      try {
        FileEditorManager fem = FileEditorManager.getInstance(project);
        VirtualFile[] files = fem.getSelectedFiles();
        if (files.length == 0) return result;
        VirtualFile vf = files[0];
        PsiFile psi = PsiManager.getInstance(project).findFile(vf);
        Language base = null;
        if (psi != null) {
          base = psi.getLanguage();
        } else {
          try {
            var ft = vf.getFileType();
            if (ft instanceof LanguageFileType lft) base = lft.getLanguage();
          } catch (Throwable ignored) {}
        }
        while (base != null) {
          result.add(base.getID());
          Language next = base.getBaseLanguage();
          if (next == base) break;
          base = next;
        }
      } catch (Throwable ignored) {}
      return result;
    }

    private @Nullable Row selectedRow() {
      TreePath path = tree.getSelectionPath();
      if (path == null) return null;
      Object last = path.getLastPathComponent();
      if (last instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof Row r) return r;
      return null;
    }

    private void onTreeSelected(TreeSelectionEvent e) {
      Row r = selectedRow();
      if (r == null) { description.setText(""); return; }
      String desc = r.description.isEmpty() ? "(no description)" : r.description;
      StringBuilder html = new StringBuilder("<html><body>");
      html.append("<h3>").append(escape(r.name)).append("</h3>");
      html.append("<p><b>Id:</b> ").append(escape(r.id)).append("<br>");
      html.append("<b>Group:</b> ").append(escape(r.groupTitle)).append("<br>");
      html.append("<b>Language:</b> ").append(escape(r.languageDisplay.isEmpty() ? "-" : r.languageDisplay));
      if (!r.languageId.isEmpty() && !r.languageId.equalsIgnoreCase(r.languageDisplay)) {
        html.append(" (").append(escape(r.languageId)).append(")");
      }
      html.append("<br>");
      html.append("<b>Plugin:</b> ").append(escape(r.pluginName.isEmpty() ? PLUGIN_UNKNOWN : r.pluginName));
      if (!r.pluginId.isEmpty() && !r.pluginId.equalsIgnoreCase(r.pluginName)) {
        html.append(" (").append(escape(r.pluginId)).append(")");
      }
      html.append("<br>");
      html.append("<b>Impl class:</b> ").append(escape(r.implClass)).append("</p><hr>");
      html.append("<p>").append(escape(desc)).append("</p>");
      html.append("</body></html>");
      description.setText(html.toString());
      description.setCaretPosition(0);
    }

    private static String escape(String s) {
      if (s == null) return "";
      return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
  }

  private static final class GroupNode {
    final String name;
    GroupNode(String name) { this.name = name; }
    @Override public String toString() { return name; }
  }
}
