// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.support.ide.inspector;
import com.intellij.codeInsight.hint.HintUtil;
import com.intellij.codeInsight.intention.impl.IntentionActionWithTextCaching;
import com.intellij.ide.IdeBundle;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.application.ApplicationNamesInfo;
import com.intellij.openapi.application.ex.ApplicationInfoEx;
import com.intellij.openapi.diagnostic.ReportingClassSubstitutor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.openapi.util.NlsSafe;
import com.intellij.ui.AppUIUtil;
import com.intellij.ui.HyperlinkAdapter;
import com.intellij.ui.HyperlinkLabel;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.scale.ScaleContext;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;

public final class IntentionDumpDialog extends DialogWrapper {
  private final List<String> lines;
  private final List<String> myInfo;
  private final String firstLine;

  public IntentionDumpDialog(@Nullable Project project,
                             @NotNull String title, @NotNull String firstLine, @NotNull List<String> lines,
                             @NotNull List<String> copyString ) {
    super(project, false, true);
    this.lines = lines;
    this.myInfo = copyString;
    this.firstLine = firstLine;
    setResizable(false);
    setTitle(title);

    init();
  }

  @Override
  protected JComponent createSouthPanel() {
    JComponent result = super.createSouthPanel();

    // Registering the copy action only on the buttons panel, because it conflicts with copyable labels in the center panel
    new DumbAwareAction() {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        copyAboutInfoToClipboard();
        close(OK_EXIT_CODE);
        showCopiedDialog();
      }
    }.registerCustomShortcutSet(CustomShortcutSet.fromString("meta C", "control C"), result, getDisposable());

    return result;
  }

  @Override
  protected @NotNull JComponent createCenterPanel() {
    Icon appIcon = AppUIUtil.loadApplicationIcon(ScaleContext.create(), 60);
    Box box = getText();
    JLabel icon = new JLabel(appIcon);
    icon.setVerticalAlignment(SwingConstants.TOP);
    icon.setBorder(JBUI.Borders.empty(20, 12, 0, 24));
    box.setBorder(JBUI.Borders.empty(20, 0, 0, 20));

    return JBUI.Panels.simplePanel()
      // .addToLeft(icon)
      .addToCenter(box);
  }

  @Override
  protected void createDefaultActions() {
    super.createDefaultActions();
    myOKAction = new OkAction() {
      {
        putValue(NAME, IdeBundle.message("button.copy.and.close"));
        putValue(SHORT_DESCRIPTION, "Copy class names to clipboard");
      }

      @Override
      protected void doAction(ActionEvent e) {
        copyAboutInfoToClipboard();
        close(OK_EXIT_CODE);
        showCopiedDialog();
      }
    };
    myCancelAction.putValue(Action.NAME, IdeBundle.message("action.close"));
  }

  private void copyAboutInfoToClipboard() {
    try {
      var text = new StringBuilder();

      myInfo.forEach(s -> text.append(s).append('\n'));

      CopyPasteManager.getInstance().setContents(new StringSelection(text.toString()));
    }
    catch (Exception ignore) {
      ignore.printStackTrace();
    }
  }

  public static void showCopiedDialog() {
    var message = "Class names copied to clipboard, please analyze it via <b>Help | Show Git Log For Classes.." +
                  ".</b> in the IDEA source repo ";
    var label = HintUtil.createSuccessLabel(message);
    JBPopupFactory.getInstance().createBalloonBuilder(label)
            .setFadeoutTime(5000)
            .setFillColor(HintUtil.getWarningColor())
            .createBalloon()
            .show(new RelativePoint(new Point(100, 100)), Balloon.Position.atRight);
  }



  private @NotNull Box getText() {
    Box box = Box.createVerticalBox();


    @NlsSafe String appName = this.firstLine;// "Intentions of current editor position";
    box.add(label(appName, JBFont.h3().asBold()));
    box.add(Box.createVerticalStrut(10));
    // myInfo.add(appName);


    //Print extra information from plugins

    @NlsSafe String text = String.join("<p>", lines);  // joining with paragraph separators for better-looking copied text
    box.add(label(text, getDefaultTextFont()));
    addEmptyLine(box);

    //Link to open-source projects
    HyperlinkLabel openSourceSoftware = hyperlinkLabel("Powered by <hyperlink>IDE Inspector plugin</hyperlink>");
    openSourceSoftware.addHyperlinkListener(new HyperlinkAdapter() {
      @Override
      protected void hyperlinkActivated(@NotNull HyperlinkEvent e) {
        // showOssInfo(box);
      }
    });
    box.add(openSourceSoftware);

    addEmptyLine(box);

    return box;
  }

  private static JBFont getDefaultTextFont() {
    return JBFont.medium();
  }

  private static void addEmptyLine(@NotNull Box box) {
    box.add(Box.createVerticalStrut(18));
  }

  private static @NotNull JLabel label(@NlsContexts.Label @NotNull String text, JBFont font) {
    var label = new JBLabel(text).withFont(font);
    label.setCopyable(true);
    return label;
  }

  private static @NotNull HyperlinkLabel hyperlinkLabel(@NlsContexts.LinkLabel @NotNull String textWithLink) {
    var hyperlinkLabel = new HyperlinkLabel();
    hyperlinkLabel.setTextWithHyperlink(textWithLink);
    hyperlinkLabel.setFont(getDefaultTextFont());
    return hyperlinkLabel;
  }



}
