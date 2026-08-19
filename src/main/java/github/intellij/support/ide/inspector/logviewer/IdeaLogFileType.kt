package github.intellij.support.ide.inspector.logviewer

import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.fileTypes.PlainTextLikeFileType
import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

object IdeaLogFileType : LanguageFileType(PlainTextLanguage.INSTANCE, true), PlainTextLikeFileType {
    override fun getName(): String = "IDEA Log"
    override fun getDescription(): String = "IntelliJ IDEA log file"
    override fun getDefaultExtension(): String = "log"
    override fun getIcon(): Icon? = com.intellij.icons.AllIcons.FileTypes.Text
}
