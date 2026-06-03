package github.intellij.support.ide.inspector;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.impl.config.IntentionActionMetaData;
import com.intellij.codeInsight.intention.impl.config.IntentionActionWrapper;
import com.intellij.codeInsight.intention.impl.config.IntentionManagerSettings;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.DumbAwareToggleAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Readonly intention tree tab. Lists registered intention actions grouped by
 * their category path (mirrors Settings | Editor | Intentions) or by providing
 * plugin. Shows description for selected leaf. No enable/disable.
 */
public final class IntentionListPanel {

  private IntentionListPanel() {}

  public static @NotNull JComponent create(@NotNull Project project) {
    return new Panel(project).root;
  }

  static final String PLUGIN_ANY = "(Any plugin)";
  static final String PLUGIN_UNKNOWN = "(Unknown)";

  private enum GroupMode { CATEGORY, PLUGIN }

  private static final class Row {
    final String family;
    final String[] groupPath;
    final String implClass;
    final String pluginId;
    final String pluginName;
    final IntentionActionMetaData meta;

    Row(IntentionActionMetaData m) {
      this.meta = m;
      this.family = nullSafe(m.getFamily());
      String[] cat = m.myCategory;
      this.groupPath = (cat == null || cat.length == 0) ? new String[]{"(no group)"} : cat;
      String cls = "-";
      try {
        IntentionAction action = m.getAction();
        if (action instanceof IntentionActionWrapper wrapper) {
          cls = wrapper.getImplementationClassName();
        } else if (action != null) {
          cls = action.getClass().getName();
        }
      } catch (Throwable ignored) {}
      this.implClass = cls;

      String pid = "";
      String pname = "";
      try {
        PluginId id = m.getPluginId();
        if (id != null) {
          pid = id.getIdString();
          IdeaPluginDescriptor pd = PluginManagerCore.getPlugin(id);
          if (pd != null && pd.getName() != null) pname = pd.getName();
        }
      } catch (Throwable ignored) {}
      this.pluginId = pid;
      this.pluginName = pname.isEmpty() ? pid : pname;
    }

    String groupPathJoined() { return String.join(" / ", groupPath); }
    String pluginBucket() { return pluginName.isEmpty() ? PLUGIN_UNKNOWN : pluginName; }
    @Override public String toString() { return family; }

    private static String nullSafe(String s) { return s == null ? "" : s; }
  }

  private static final class PluginItem {
    final String label;
    final String pluginName; // "" = any, null = unknown
    PluginItem(String label, String pluginName) { this.label = label; this.pluginName = pluginName; }
    @Override public String toString() { return label; }
  }

  private static final class Panel {
    final JComponent root;
    private final Project project;
    private final ComboBox<PluginItem> pluginCombo = new ComboBox<>();
    private final SearchTextField filterField = new SearchTextField();
    private final DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("Intentions");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(rootNode);
    private final Tree tree = new Tree(treeModel);
    private final JEditorPane description = new JEditorPane();
    private final JBLabel status = new JBLabel("");
    private volatile long loadToken = 0;
    private List<Row> allRows = new ArrayList<>();
    private GroupMode groupMode = GroupMode.CATEGORY;
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
      row1.add(status);

      JPanel row2 = new JPanel();
      row2.setLayout(new BoxLayout(row2, BoxLayout.X_AXIS));
      row2.setBorder(JBUI.Borders.emptyTop(4));
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
      pluginCombo.addActionListener(e -> { if (!suppressFilterEvents) rebuildTree(); });

      loadRows();
    }

    private JComponent buildToolbar(JComponent target) {
      DefaultActionGroup group = new DefaultActionGroup();
      group.add(new DumbAwareAction("Refresh", "Reload intentions", com.intellij.icons.AllIcons.Actions.Refresh) {
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
      group.add(new DumbAwareToggleAction("Group by Category",
        "Group intentions by their category path",
        com.intellij.icons.AllIcons.Actions.GroupBy) {
        @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        @Override public boolean isSelected(@NotNull AnActionEvent e) { return groupMode == GroupMode.CATEGORY; }
        @Override public void setSelected(@NotNull AnActionEvent e, boolean state) {
          if (state && groupMode != GroupMode.CATEGORY) { groupMode = GroupMode.CATEGORY; rebuildTree(); }
        }
      });
      group.add(new DumbAwareToggleAction("Group by Plugin",
        "Group intentions by providing plugin",
        com.intellij.icons.AllIcons.Actions.GroupByModule) {
        @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        @Override public boolean isSelected(@NotNull AnActionEvent e) { return groupMode == GroupMode.PLUGIN; }
        @Override public void setSelected(@NotNull AnActionEvent e, boolean state) {
          if (state && groupMode != GroupMode.PLUGIN) { groupMode = GroupMode.PLUGIN; rebuildTree(); }
        }
      });
      group.add(new Separator());
      group.add(new DumbAwareAction("Show Git History",
        "Show recent commits for selected intention class in configured IDEA source repo",
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
      group.add(new DumbAwareAction("Copy Class Name", "Copy implementation class of selected intention",
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

    private void loadRows() {
      final long token = ++loadToken;
      status.setText("Loading…");
      ApplicationManager.getApplication().executeOnPooledThread(() -> {
        List<Row> rows = new ArrayList<>();
        try {
          List<IntentionActionMetaData> meta = IntentionManagerSettings.getInstance().getMetaData();
          for (IntentionActionMetaData m : meta) {
            try { rows.add(new Row(m)); } catch (Throwable ignored) {}
          }
          rows.sort(Comparator
            .comparing((Row r) -> r.groupPathJoined(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(r -> r.family, String.CASE_INSENSITIVE_ORDER));
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
        Object prev = pluginCombo.getSelectedItem();
        TreeMap<String, String> plugins = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        boolean hasUnknown = false;
        for (Row r : allRows) {
          if (r.pluginName.isEmpty()) { hasUnknown = true; continue; }
          plugins.putIfAbsent(r.pluginName, r.pluginName);
        }
        DefaultComboBoxModel<PluginItem> model = new DefaultComboBoxModel<>();
        model.addElement(new PluginItem(PLUGIN_ANY, ""));
        if (hasUnknown) model.addElement(new PluginItem(PLUGIN_UNKNOWN, null));
        for (String pn : plugins.keySet()) model.addElement(new PluginItem(pn, pn));
        pluginCombo.setModel(model);
        if (prev instanceof PluginItem pp) {
          for (int i = 0; i < model.getSize(); i++) {
            PluginItem it = model.getElementAt(i);
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
      PluginItem pluginSel = (PluginItem) pluginCombo.getSelectedItem();

      rootNode.removeAllChildren();
      Map<String, DefaultMutableTreeNode> groupCache = new HashMap<>();
      int leafCount = 0;
      for (Row r : allRows) {
        if (!matches(r, q, pluginSel)) continue;
        DefaultMutableTreeNode parent;
        if (groupMode == GroupMode.PLUGIN) {
          parent = groupCache.computeIfAbsent(r.pluginBucket(), key -> {
            DefaultMutableTreeNode n = new DefaultMutableTreeNode(new GroupNode(key));
            rootNode.add(n);
            return n;
          });
        } else {
          parent = getOrCreateGroupNode(groupCache, r.groupPath);
        }
        parent.add(new DefaultMutableTreeNode(r));
        leafCount++;
      }
      treeModel.reload();
      if (!q.isEmpty()) TreeUtil.expandAll(tree); else TreeUtil.expand(tree, 1);

      String filterNote = "";
      if (pluginSel != null && !(pluginSel.pluginName != null && pluginSel.pluginName.isEmpty())) {
        filterNote = "  [plugin=" + (pluginSel.pluginName == null ? "unknown" : pluginSel.pluginName) + "]";
      }
      status.setText(leafCount + " / " + allRows.size() + " intentions" + filterNote);
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

    private boolean matches(Row r, String needle, PluginItem plugin) {
      if (!needle.isEmpty()) {
        boolean ok = r.family.toLowerCase(Locale.ROOT).contains(needle)
          || r.implClass.toLowerCase(Locale.ROOT).contains(needle)
          || r.groupPathJoined().toLowerCase(Locale.ROOT).contains(needle)
          || r.pluginName.toLowerCase(Locale.ROOT).contains(needle);
        if (!ok) return false;
      }
      if (plugin != null) {
        if (plugin.pluginName == null) {
          if (!r.pluginName.isEmpty()) return false;
        } else if (!plugin.pluginName.isEmpty()) {
          if (!plugin.pluginName.equalsIgnoreCase(r.pluginName)) return false;
        }
      }
      return true;
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
      String html;
      try {
        String raw = r.meta.getDescription().getText();
        html = (raw == null || raw.isEmpty()) ? "(no description)" : raw;
      } catch (Throwable t) {
        html = "(no description)";
      }
      StringBuilder header = new StringBuilder("<html><body>");
      header.append("<h3>").append(escape(r.family)).append("</h3>");
      header.append("<p><b>Family:</b> ").append(escape(r.family)).append("<br>");
      header.append("<b>Category:</b> ").append(escape(r.groupPathJoined())).append("<br>");
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
