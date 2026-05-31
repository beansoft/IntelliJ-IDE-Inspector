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
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.ListSelectionEvent;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Readonly inspection list tab. Lists inspections from the selected profile
 * (project + IDE profiles available in dropdown). Shows description for the
 * selected row. No enable/disable, no settings, no profile mutation.
 */
public final class InspectionListPanel {

  private InspectionListPanel() {}

  public static @NotNull JComponent create(@NotNull Project project) {
    return new Panel(project).root;
  }

  private static final class Row {
    final String shortName;
    final String displayName;
    final String group;
    final String severity;
    final String language;
    final String implClass;
    final InspectionToolWrapper<?, ?> wrapper;

    Row(InspectionToolWrapper<?, ?> w) {
      this.wrapper = w;
      this.shortName = nullSafe(w.getShortName());
      this.displayName = nullSafe(w.getDisplayName());
      this.group = String.join(" / ", w.getGroupPath());
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

  private static final class Model extends AbstractTableModel {
    private final String[] COLS = {"Short Name", "Display Name", "Group", "Severity", "Language", "Impl Class"};
    private List<Row> rows = Collections.emptyList();

    void setRows(List<Row> rows) {
      this.rows = rows;
      fireTableDataChanged();
    }

    Row rowAt(int idx) {
      return (idx < 0 || idx >= rows.size()) ? null : rows.get(idx);
    }

    @Override public int getRowCount() { return rows.size(); }
    @Override public int getColumnCount() { return COLS.length; }
    @Override public String getColumnName(int c) { return COLS[c]; }
    @Override public boolean isCellEditable(int r, int c) { return false; }
    @Override public Class<?> getColumnClass(int c) { return String.class; }

    @Override
    public Object getValueAt(int r, int c) {
      Row row = rows.get(r);
      return switch (c) {
        case 0 -> row.shortName;
        case 1 -> row.displayName;
        case 2 -> row.group;
        case 3 -> row.severity;
        case 4 -> row.language;
        case 5 -> row.implClass;
        default -> "";
      };
    }
  }

  private static final class Panel {
    final JComponent root;
    private final Project project;
    private final ComboBox<ProfileItem> profileCombo = new ComboBox<>();
    private final SearchTextField filterField = new SearchTextField();
    private final Model model = new Model();
    private final JBTable table = new JBTable(model);
    private final TableRowSorter<Model> sorter = new TableRowSorter<>(model);
    private final JEditorPane description = new JEditorPane();
    private final JBLabel status = new JBLabel("");
    private volatile long loadToken = 0;

    Panel(@NotNull Project p) {
      this.project = p;

      table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
      table.setRowSorter(sorter);
      table.getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
      table.getSelectionModel().addListSelectionListener(this::onRowSelected);
      configureColumnWidths();

      description.setEditable(false);
      description.setContentType("text/html");
      description.setBorder(JBUI.Borders.empty(8));

      JBSplitter splitter = new JBSplitter(true, 0.65f);
      splitter.setFirstComponent(new JBScrollPane(table));
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
        @Override protected void textChanged(@NotNull DocumentEvent e) { applyFilter(); }
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

    private void configureColumnWidths() {
      int[] widths = {220, 280, 220, 90, 90, 320};
      for (int i = 0; i < widths.length && i < table.getColumnCount(); i++) {
        TableColumn col = table.getColumnModel().getColumn(i);
        col.setPreferredWidth(widths[i]);
      }
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
        model.setRows(Collections.emptyList());
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
            .comparing((Row r) -> r.group, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(r -> r.shortName, String.CASE_INSENSITIVE_ORDER));
        } catch (Throwable t) {
          ApplicationManager.getApplication().invokeLater(() -> {
            if (token != loadToken) return;
            status.setText("Load failed: " + t.getMessage());
          });
          return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
          if (token != loadToken) return;
          model.setRows(rows);
          applyFilter();
          status.setText(rows.size() + " inspections");
          description.setText("");
        });
      });
    }

    private void applyFilter() {
      String q = filterField.getText().trim();
      if (q.isEmpty()) {
        sorter.setRowFilter(null);
        return;
      }
      String needle = q.toLowerCase();
      sorter.setRowFilter(new javax.swing.RowFilter<>() {
        @Override
        public boolean include(Entry<? extends Model, ? extends Integer> entry) {
          for (int c = 0; c < entry.getValueCount(); c++) {
            Object v = entry.getValue(c);
            if (v != null && v.toString().toLowerCase().contains(needle)) return true;
          }
          return false;
        }
      });
    }

    private @Nullable Row selectedRow() {
      int view = table.getSelectedRow();
      if (view < 0) return null;
      int idx = table.convertRowIndexToModel(view);
      return model.rowAt(idx);
    }

    private void onRowSelected(ListSelectionEvent e) {
      if (e.getValueIsAdjusting()) return;
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
      header.append("<h3>").append(escape(r.displayName)).append("</h3>");
      header.append("<p><b>Short name:</b> ").append(escape(r.shortName)).append("<br>");
      header.append("<b>Group:</b> ").append(escape(r.group)).append("<br>");
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
}
