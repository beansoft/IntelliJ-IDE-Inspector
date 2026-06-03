package github.intellij.support.ide.inspector;

import com.intellij.codeInspection.InspectionEP;
import com.intellij.codeInspection.ex.InspectionProfileImpl;
import com.intellij.codeInspection.ex.InspectionToolWrapper;
import com.intellij.lang.Language;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.extensions.PluginDescriptor;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.DumbAwareToggleAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.profile.codeInspection.InspectionProfileManager;
import com.intellij.profile.codeInspection.ProjectInspectionProfileManager;
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
 * Readonly inspection tree tab. Lists inspections from the selected profile
 * grouped by category (group path) or by providing plugin. Shows description
 * for selected leaf. No enable/disable, no settings, no profile mutation.
 */
public final class InspectionListPanel {

  private InspectionListPanel() {}

  public static @NotNull JComponent create(@NotNull Project project) {
    return new Panel(project).root;
  }

  static final String LANG_ANY = "(Any language)";
  static final String LANG_NONE = "(No language)";
  static final String PLUGIN_ANY = "(Any plugin)";
  static final String PLUGIN_UNKNOWN = "(Unknown)";

  private enum GroupMode { CATEGORY, PLUGIN, LANGUAGE }

  private static final class Row {
    final String shortName;
    final String displayName;
    final String[] groupPath;
    final String severity;
    final String languageId;
    final String languageDisplay;
    final String implClass;
    final String pluginId;
    final String pluginName;
    final InspectionToolWrapper<?, ?> wrapper;

    Row(InspectionToolWrapper<?, ?> w) {
      this.wrapper = w;
      this.shortName = nullSafe(w.getShortName());
      this.displayName = nullSafe(w.getDisplayName());
      String[] gp = w.getGroupPath();
      this.groupPath = (gp == null || gp.length == 0) ? new String[]{"(no group)"} : gp;
      String sev;
      try {
        sev = w.getDefaultLevel().getSeverity().getName();
      } catch (Throwable t) {
        sev = "-";
      }
      this.severity = sev;
      String lang = w.getLanguage();
      this.languageId = (lang == null || lang.isEmpty()) ? "" : lang;
      this.languageDisplay = resolveLanguageDisplay(this.languageId);
      this.implClass = resolveImplClass(w);

      String pid = "";
      String pname = "";
      try {
        InspectionEP ep = w.getExtension();
        if (ep != null) {
          PluginDescriptor pd = ep.getPluginDescriptor();
          if (pd != null) {
            PluginId id = pd.getPluginId();
            if (id != null) pid = id.getIdString();
            String n = pd.getName();
            if (n != null) pname = n;
          }
        }
      } catch (Throwable ignored) {}
      this.pluginId = pid;
      this.pluginName = pname.isEmpty() ? (pid.isEmpty() ? "" : pid) : pname;
    }

    String groupPathJoined() {
      return String.join(" / ", groupPath);
    }

    String leafLabel() {
      return displayName.isEmpty() ? shortName : displayName;
    }

    String pluginBucket() {
      return pluginName.isEmpty() ? PLUGIN_UNKNOWN : pluginName;
    }

    String languageBucket() {
      if (languageId.isEmpty()) return LANG_NONE;
      return languageDisplay.isEmpty() ? languageId : languageDisplay;
    }

    @Override public String toString() { return leafLabel(); }

    private static String resolveImplClass(InspectionToolWrapper<?, ?> w) {
      try {
        InspectionEP ep = w.getExtension();
        if (ep != null && ep.implementationClass != null && !ep.implementationClass.isEmpty()) {
          return ep.implementationClass;
        }
      } catch (Throwable ignored) {}
      try {
        return w.getDescriptionContextClass().getName();
      } catch (Throwable ignored) {}
      return "-";
    }

    private static String resolveLanguageDisplay(String langId) {
      if (langId == null || langId.isEmpty()) return "";
      try {
        Language l = Language.findLanguageByID(langId);
        if (l != null) {
          String disp = l.getDisplayName();
          if (disp != null && !disp.isEmpty()) return disp;
        }
      } catch (Throwable ignored) {}
      return langId;
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }
  }

  private static final class ProfileItem {
    final InspectionProfileImpl profile;
    final String label;
    ProfileItem(InspectionProfileImpl profile, String label) {
      this.profile = profile;
      this.label = label;
    }
    @Override public String toString() { return label; }
  }

  /** Combo item: a label plus the underlying language id (empty = any / none sentinel). */
  private static final class LangItem {
    final String label;
    final String languageId; // "" for "any", null for "no language" sentinel
    LangItem(String label, String languageId) { this.label = label; this.languageId = languageId; }
    @Override public String toString() { return label; }
  }

  private static final class PluginItem {
    final String label;
    final String pluginName; // "" = any, null = unknown sentinel
    PluginItem(String label, String pluginName) { this.label = label; this.pluginName = pluginName; }
    @Override public String toString() { return label; }
  }

  private static final class Panel {
    final JComponent root;
    private final Project project;
    private final ComboBox<ProfileItem> profileCombo = new ComboBox<>();
    private final ComboBox<LangItem> languageCombo = new ComboBox<>();
    private final ComboBox<PluginItem> pluginCombo = new ComboBox<>();
    private final SearchTextField filterField = new SearchTextField();
    private final DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("Inspections");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(rootNode);
    private final Tree tree = new Tree(treeModel);
    private final JEditorPane description = new JEditorPane();
    private final JBLabel status = new JBLabel("");
    private volatile long loadToken = 0;
    private List<Row> allRows = new ArrayList<>();
    private GroupMode groupMode = GroupMode.CATEGORY;
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

      // Row 1: profile + filter text + status
      JPanel row1 = new JPanel();
      row1.setLayout(new BoxLayout(row1, BoxLayout.X_AXIS));
      row1.add(new JBLabel("Profile:"));
      row1.add(Box.createHorizontalStrut(6));
      row1.add(profileCombo);
      row1.add(Box.createHorizontalStrut(12));
      row1.add(new JBLabel("Filter:"));
      row1.add(Box.createHorizontalStrut(6));
      filterField.setPreferredSize(new Dimension(220, filterField.getPreferredSize().height));
      row1.add(filterField);
      row1.add(Box.createHorizontalStrut(12));
      row1.add(status);

      // Row 2: language + plugin combos
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

      profileCombo.addActionListener(e -> reloadFromCombo());
      filterField.addDocumentListener(new DocumentAdapter() {
        @Override protected void textChanged(@NotNull DocumentEvent e) { rebuildTree(); }
      });
      languageCombo.addActionListener(e -> { if (!suppressFilterEvents) rebuildTree(); });
      pluginCombo.addActionListener(e -> { if (!suppressFilterEvents) rebuildTree(); });

      populateProfiles();
      reloadFromCombo();
    }

    private JComponent buildToolbar(JComponent target) {
      DefaultActionGroup group = new DefaultActionGroup();
      group.add(new DumbAwareAction("Refresh", "Reload inspections", com.intellij.icons.AllIcons.Actions.Refresh) {
        @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
        @Override public void actionPerformed(@NotNull AnActionEvent e) {
          populateProfiles();
          reloadFromCombo();
        }
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
      group.add(new DumbAwareToggleAction("Group by Category",
        "Group inspections by their category path",
        com.intellij.icons.AllIcons.Actions.GroupBy) {
        @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        @Override public boolean isSelected(@NotNull AnActionEvent e) { return groupMode == GroupMode.CATEGORY; }
        @Override public void setSelected(@NotNull AnActionEvent e, boolean state) {
          if (state && groupMode != GroupMode.CATEGORY) {
            groupMode = GroupMode.CATEGORY;
            rebuildTree();
          }
        }
      });
      group.add(new DumbAwareToggleAction("Group by Plugin",
        "Group inspections by providing plugin",
        com.intellij.icons.AllIcons.Actions.GroupByModule) {
        @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        @Override public boolean isSelected(@NotNull AnActionEvent e) { return groupMode == GroupMode.PLUGIN; }
        @Override public void setSelected(@NotNull AnActionEvent e, boolean state) {
          if (state && groupMode != GroupMode.PLUGIN) {
            groupMode = GroupMode.PLUGIN;
            rebuildTree();
          }
        }
      });
      group.add(new DumbAwareToggleAction("Group by Language",
        "Group inspections by target language",
        com.intellij.icons.AllIcons.Actions.GroupByFile) {
        @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        @Override public boolean isSelected(@NotNull AnActionEvent e) { return groupMode == GroupMode.LANGUAGE; }
        @Override public void setSelected(@NotNull AnActionEvent e, boolean state) {
          if (state && groupMode != GroupMode.LANGUAGE) {
            groupMode = GroupMode.LANGUAGE;
            rebuildTree();
          }
        }
      });
      group.add(new DumbAwareToggleAction("Limit to Current File",
        "Show only inspections applicable to the language of the currently open editor file",
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
        "Show recent commits for selected inspection class in configured IDEA source repo",
        com.intellij.icons.AllIcons.Vcs.History) {
        @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        @Override public void update(@NotNull AnActionEvent e) {
          e.getPresentation().setEnabled(selectedRow() != null);
        }
        @Override public void actionPerformed(@NotNull AnActionEvent e) {
          Row r = selectedRow();
          if (r == null || r.implClass == null || r.implClass.isEmpty() || "-".equals(r.implClass)) return;
          InspectionGitHistoryPopup.INSTANCE.showExternalGitLog(project, r.implClass, tree);
        }
      });
      group.add(new DumbAwareAction("Copy Class Name", "Copy implementation class of selected inspection",
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

    private void populateProfiles() {
      List<ProfileItem> items = new ArrayList<>();
      ProjectInspectionProfileManager pm = ProjectInspectionProfileManager.getInstance(project);
      InspectionProfileImpl current = pm.getCurrentProfile();

      Collection<InspectionProfileImpl> projectProfiles = pm.getProfiles();
      for (InspectionProfileImpl p : projectProfiles) {
        items.add(new ProfileItem(p, "[Project] " + p.getName()));
      }
      Collection<InspectionProfileImpl> appProfiles = InspectionProfileManager.getInstance().getProfiles();
      for (InspectionProfileImpl p : appProfiles) {
        if (!projectProfiles.contains(p)) {
          items.add(new ProfileItem(p, "[IDE] " + p.getName()));
        }
      }

      DefaultComboBoxModel<ProfileItem> cbModel = new DefaultComboBoxModel<>();
      ProfileItem preselect = null;
      for (ProfileItem it : items) {
        cbModel.addElement(it);
        if (preselect == null && it.profile == current) preselect = it;
      }
      profileCombo.setModel(cbModel);
      if (preselect != null) profileCombo.setSelectedItem(preselect);
    }

    private void reloadFromCombo() {
      ProfileItem sel = (ProfileItem) profileCombo.getSelectedItem();
      if (sel == null) {
        allRows = new ArrayList<>();
        rebuildFilterCombos();
        rebuildTree();
        return;
      }
      loadRows(sel.profile);
    }

    private void loadRows(@NotNull InspectionProfileImpl profile) {
      final long token = ++loadToken;
      status.setText("Loading…");
      ApplicationManager.getApplication().executeOnPooledThread(() -> {
        List<Row> rows = new ArrayList<>();
        try {
          List<InspectionToolWrapper<?, ?>> tools = profile.getInspectionTools(null);
          for (InspectionToolWrapper<?, ?> w : tools) {
            try { rows.add(new Row(w)); } catch (Throwable ignored) {}
          }
          rows.sort(Comparator
            .comparing((Row r) -> r.groupPathJoined(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Row::leafLabel, String.CASE_INSENSITIVE_ORDER));
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
          status.setText(rows.size() + " inspections");
          description.setText("");
        });
      });
    }

    private void rebuildFilterCombos() {
      suppressFilterEvents = true;
      try {
        // Language combo
        Object prevLang = languageCombo.getSelectedItem();
        TreeMap<String, String> langs = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        boolean hasEmpty = false;
        for (Row r : allRows) {
          if (r.languageId.isEmpty()) { hasEmpty = true; continue; }
          langs.putIfAbsent(r.languageDisplay, r.languageId);
        }
        DefaultComboBoxModel<LangItem> langModel = new DefaultComboBoxModel<>();
        langModel.addElement(new LangItem(LANG_ANY, ""));
        if (hasEmpty) langModel.addElement(new LangItem(LANG_NONE, null));
        for (Map.Entry<String, String> e : langs.entrySet()) {
          langModel.addElement(new LangItem(e.getKey(), e.getValue()));
        }
        languageCombo.setModel(langModel);
        if (prevLang instanceof LangItem pl) {
          for (int i = 0; i < langModel.getSize(); i++) {
            LangItem it = langModel.getElementAt(i);
            if (java.util.Objects.equals(it.languageId, pl.languageId)) {
              languageCombo.setSelectedIndex(i);
              break;
            }
          }
        }

        // Plugin combo
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
        for (String pn : plugins.keySet()) {
          pluginModel.addElement(new PluginItem(pn, pn));
        }
        pluginCombo.setModel(pluginModel);
        if (prevPlugin instanceof PluginItem pp) {
          for (int i = 0; i < pluginModel.getSize(); i++) {
            PluginItem it = pluginModel.getElementAt(i);
            if (java.util.Objects.equals(it.pluginName, pp.pluginName)) {
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
      LangItem langSel = (LangItem) languageCombo.getSelectedItem();
      PluginItem pluginSel = (PluginItem) pluginCombo.getSelectedItem();
      Set<String> currentFileLangs = limitToCurrentFile ? currentFileLanguageIds() : null;

      rootNode.removeAllChildren();

      Map<String, DefaultMutableTreeNode> groupCache = new HashMap<>();
      int leafCount = 0;
      for (Row r : allRows) {
        if (!matches(r, q, langSel, pluginSel, currentFileLangs)) continue;
        DefaultMutableTreeNode parent;
        switch (groupMode) {
          case PLUGIN -> parent = groupCache.computeIfAbsent(r.pluginBucket(), key -> {
            DefaultMutableTreeNode n = new DefaultMutableTreeNode(new GroupNode(key));
            rootNode.add(n);
            return n;
          });
          case LANGUAGE -> parent = groupCache.computeIfAbsent(r.languageBucket(), key -> {
            DefaultMutableTreeNode n = new DefaultMutableTreeNode(new GroupNode(key));
            rootNode.add(n);
            return n;
          });
          default -> parent = getOrCreateGroupNode(groupCache, r.groupPath);
        }
        parent.add(new DefaultMutableTreeNode(r));
        leafCount++;
      }

      treeModel.reload();

      if (!q.isEmpty()) {
        TreeUtil.expandAll(tree);
      } else {
        TreeUtil.expand(tree, 1);
      }

      String filterNote = filterNote(langSel, pluginSel, currentFileLangs);
      if (!allRows.isEmpty()) {
        status.setText(leafCount + " / " + allRows.size() + " inspections" + filterNote);
      } else {
        status.setText("0 inspections" + filterNote);
      }
    }

    private String filterNote(LangItem lang, PluginItem plugin, Set<String> currentFileLangs) {
      List<String> parts = new ArrayList<>();
      if (lang != null && !(lang.languageId != null && lang.languageId.isEmpty())) {
        parts.add(lang.languageId == null ? "no lang" : "lang=" + lang.label);
      }
      if (plugin != null && !(plugin.pluginName != null && plugin.pluginName.isEmpty())) {
        parts.add(plugin.pluginName == null ? "unknown plugin" : "plugin=" + plugin.pluginName);
      }
      if (currentFileLangs != null) {
        parts.add("current-file" + (currentFileLangs.isEmpty() ? "(none)" : ""));
      }
      return parts.isEmpty() ? "" : "  [" + String.join(", ", parts) + "]";
    }

    private DefaultMutableTreeNode getOrCreateGroupNode(Map<String, DefaultMutableTreeNode> cache, String[] path) {
      StringBuilder key = new StringBuilder();
      DefaultMutableTreeNode parent = rootNode;
      for (String segment : path) {
        if (key.length() > 0) key.append(' ');
        key.append(segment);
        String k = key.toString();
        DefaultMutableTreeNode node = cache.get(k);
        if (node == null) {
          node = new DefaultMutableTreeNode(new GroupNode(segment));
          parent.add(node);
          cache.put(k, node);
        }
        parent = node;
      }
      return parent;
    }

    private boolean matches(Row r, String needle, LangItem lang, PluginItem plugin, Set<String> currentFileLangs) {
      if (!needle.isEmpty()) {
        boolean textOk = r.shortName.toLowerCase(Locale.ROOT).contains(needle)
          || r.displayName.toLowerCase(Locale.ROOT).contains(needle)
          || r.implClass.toLowerCase(Locale.ROOT).contains(needle)
          || r.groupPathJoined().toLowerCase(Locale.ROOT).contains(needle)
          || r.pluginName.toLowerCase(Locale.ROOT).contains(needle)
          || r.languageDisplay.toLowerCase(Locale.ROOT).contains(needle);
        if (!textOk) return false;
      }
      if (lang != null) {
        if (lang.languageId == null) { // "(No language)"
          if (!r.languageId.isEmpty()) return false;
        } else if (!lang.languageId.isEmpty()) {
          if (!lang.languageId.equalsIgnoreCase(r.languageId)) return false;
        }
      }
      if (plugin != null) {
        if (plugin.pluginName == null) { // "(Unknown)"
          if (!r.pluginName.isEmpty()) return false;
        } else if (!plugin.pluginName.isEmpty()) {
          if (!plugin.pluginName.equalsIgnoreCase(r.pluginName)) return false;
        }
      }
      if (currentFileLangs != null) {
        // empty languageId = applies to all languages → include
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
        Language base;
        if (psi != null) {
          base = psi.getLanguage();
        } else {
          // fallback via file type
          base = null;
          try {
            var ft = vf.getFileType();
            if (ft instanceof com.intellij.openapi.fileTypes.LanguageFileType lft) {
              base = lft.getLanguage();
            }
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
      if (last instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof Row r) {
        return r;
      }
      return null;
    }

    private void onTreeSelected(TreeSelectionEvent e) {
      Row r = selectedRow();
      if (r == null) { description.setText(""); return; }
      String html;
      try {
        String raw = r.wrapper.loadDescription();
        html = (raw == null || raw.isEmpty()) ? "(no description)" : raw;
      } catch (Throwable t) {
        html = "(no description)";
      }
      StringBuilder header = new StringBuilder("<html><body>");
      header.append("<h3>").append(escape(r.leafLabel())).append("</h3>");
      header.append("<p><b>Short name:</b> ").append(escape(r.shortName)).append("<br>");
      header.append("<b>Group:</b> ").append(escape(r.groupPathJoined())).append("<br>");
      header.append("<b>Severity:</b> ").append(escape(r.severity)).append("<br>");
      header.append("<b>Language:</b> ").append(escape(r.languageDisplay.isEmpty() ? "-" : r.languageDisplay));
      if (!r.languageId.isEmpty() && !r.languageId.equalsIgnoreCase(r.languageDisplay)) {
        header.append(" (").append(escape(r.languageId)).append(")");
      }
      header.append("<br>");
      header.append("<b>Plugin:</b> ").append(escape(r.pluginName.isEmpty() ? PLUGIN_UNKNOWN : r.pluginName));
      if (!r.pluginId.isEmpty() && !r.pluginId.equalsIgnoreCase(r.pluginName)) {
        header.append(" (").append(escape(r.pluginId)).append(")");
      }
      header.append("<br>");
      header.append("<b>Impl class:</b> ").append(escape(r.implClass)).append("</p><hr>");
      header.append(html).append("</body></html>");
      description.setText(header.toString());
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
