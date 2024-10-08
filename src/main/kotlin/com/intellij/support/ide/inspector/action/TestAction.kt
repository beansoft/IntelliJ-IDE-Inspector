package com.intellij.support.ide.inspector.action

import com.intellij.ide.lightEdit.LightEditService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.TextEditor
import java.nio.file.Path

class TestAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
//        service<SupportRunService>().coroutineScope.launch {
//            withContext(Dispatchers.EDT) {
//                val result = withContext(Dispatchers.Default) {
//                    Thread.sleep(10000)
////                    delay(1.seconds)
//                    return@withContext "Hello"
//                }
//
//                println(result)
//            }
//        }

        val lightEditService = LightEditService.getInstance()
        val path = Path.of("sample.java")
        val lightEditorInfo = lightEditService.createNewDocument(path)
        val myFileEditor = lightEditorInfo.fileEditor
        if (myFileEditor is TextEditor) {
            ApplicationManager.getApplication().runWriteAction {
                myFileEditor.editor.document.setText("public class Main {}")
            }
        }
    }
}