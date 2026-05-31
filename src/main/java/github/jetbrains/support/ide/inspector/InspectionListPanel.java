package github.jetbrains.support.ide.inspector;

import com.intellij.codeInspection.InspectionEP;
import com.intellij.codeInspection.ex.InspectionProfileImpl;
import com.intellij.codeInspection.ex.InspectionToolWrapper;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.profile.codeInspection.InspectionProfileManager;
import com.intellij.profile.codeInspection.ProjectInspectionProfileManager;
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
import java.util.List;
import java.util.Map;

/**
 * Readonly inspection tree tab. Lists inspections from the selected profile
 * grouped by category (group path). Shows description for selected leaf.
 * No enable/disable, no settings, no profile mutation.
 */
public final class InspectionListPanel {

  private InspectionListPanel() {}

  public static @NotNull JComponent create(@NotNull Project project) {
    return new Panel(project).root;
  }

  private static final class Row {
    final String shortName;
    final String displayName;
    final String[] groupPath;
    final String severity;
    final String language;
    final String implClass;
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
      this.language = (lang == null || lang.isEmpty()) ? "-" : lang;
      this.implClass = resolveImplClass(w);
    }

    String groupPathJoined() {
      return String.join(" / ", groupPath);
    }

    String leafLabel() {
      return displayName.isEmpty() ? shortName : displayName;
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

  private static final class Panel {
    final JComponent root;
    private final Project project;
    private final ComboBox<ProfileItem> profileCombo = new ComboBox<>();
    private final SearchTextField filterField = new SearchTextField();
    private final DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("Inspections");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(rootNode);
    private final Tree tree = new Tree(treeModel);
    private final JEditorPane description = new JEditorPane();
    private final JBLabel status = new JBLabel("");
    private volatile long loadToken = 0;
    private List<Row> allRows = new ArrayList<>();

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

      JPanel left = new JPanel();
      left.setLayout(new BoxLayout(left, BoxLayout.X_AXIS));
      left.add(new JBLabel("Profile:"));
      left.add(Box.createHorizontalStrut(6));
      left.add(profileCombo);
      left.add(Box.createHorizontalStrut(12));
      left.add(new JBLabel("Filter:"));
      left.add(Box.createHorizontalStrut(6));
      filterField.setPreferredSize(new Dimension(220, filterField.getPreferredSize().height));
      left.add(filterField);
      left.add(Box.createHorizontalStrut(12));
      left.add(status);

      top.add(left, BorderLayout.WEST);
      top.add(buildToolbar(top), BorderLayout.EAST);

      JPanel main = new JPanel(new BorderLayout());
      main.add(top, BorderLayout.NORTH);
      main.add(splitter, BorderLayout.CENTER);
      this.root = main;

      profileCombo.addActionListener(e -> reloadFromCombo());
      filterField.addDocumentListener(new DocumentAdapter() {
        @Override protected void textChanged(@NotNull DocumentEvent e) { rebuildTree(); }
      });

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
          InspectionGitHistoryPopup.INSTANCE.show(project, r.implClass, tree);
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
          rebuildTree();
          status.setText(rows.size() + " inspections");
          description.setText("");
        });
      });
    }

    private void rebuildTree() {
      String q = filterField.getText().trim().toLowerCase();
      rootNode.removeAllChildren();

      Map<String, DefaultMutableTreeNode> groupCache = new HashMap<>();
      int leafCount = 0;
      for (Row r : allRows) {
        if (!matches(r, q)) continue;
        DefaultMutableTreeNode parent = getOrCreateGroupNode(groupCache, r.groupPath);
        parent.add(new DefaultMutableTreeNode(r));
        leafCount++;
      }

      treeModel.reload();

      if (!q.isEmpty()) {
        TreeUtil.expandAll(tree);
      } else {
        TreeUtil.expand(tree, 1);
      }

      if (!allRows.isEmpty()) {
        status.setText(leafCount + " / " + allRows.size() + " inspections");
      }
    }

    private DefaultMutableTreeNode getOrCreateGroupNode(Map<String, DefaultMutableTreeNode> cache, String[] path) {
      StringBuilder key = new StringBuilder();
      DefaultMutableTreeNode parent = rootNode;
      for (String segment : path) {
        if (key.length() > 0) key.append(' ');
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

    private static boolean matches(Row r, String needle) {
      if (needle.isEmpty()) return true;
      if (r.shortName.toLowerCase().contains(needle)) return true;
      if (r.displayName.toLowerCase().contains(needle)) return true;
      if (r.implClass.toLowerCase().contains(needle)) return true;
      if (r.groupPathJoined().toLowerCase().contains(needle)) return true;
      return false;
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
      header.append("<b>Language:</b> ").append(escape(r.language)).append("<br>");
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
