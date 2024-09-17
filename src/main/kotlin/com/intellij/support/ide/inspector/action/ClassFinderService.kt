package com.intellij.support.ide.inspector.action

import com.intellij.analysis.customization.console.ClassInfoResolver
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.annotations.NonNls

internal data class ProbableClassName(val from: Int,
                                      val to: Int,
                                      val fullLine: String,
                                      val fullClassName: String)

private const val EXCEPTION_IN_THREAD: @NonNls String = "Exception in thread \""
private const val CAUSED_BY: @NonNls String = "Caused by: "
private const val AT: @NonNls String = "\tat "

private const val POINT_CODE = '.'.code

private val HARDCODED_NOT_CLASS = setOf(
  "sun.awt.X11"
)

@Service(Service.Level.PROJECT)
/**
 * Depends Java plugin.
 * @see com.intellij.analysis.customization.console.ClassFinderFilter
 */
class ClassFinderService(val project: Project, val coroutineScope: CoroutineScope)  {

  private val myInfoCache : ClassInfoResolver = ClassInfoResolver(project, GlobalSearchScope.allScope(project))
  private val psiManager : PsiManager = PsiManager.getInstance(project)

  data class ProbableClassName(val from: Int,
                                        val to: Int,
                                        val fullLine: String,
                                        val fullClassName: String)

  fun getPsiFiles(line:String) : List<PsiFile>? {
//    val expectedClasses = findProbableClasses(line)
//
//    if (expectedClasses.isNotEmpty()) {
//
//    }
    return getFiles(project, line)
  }

  fun getFiles(project: Project, fullClassName: String): List<PsiFile> {
    if (DumbService.isDumb(project)) {
      return emptyList()
    }

    val packageName = StringUtil.getPackageName(fullClassName)
    if (packageName.length == fullClassName.length) return emptyList()
    var className = fullClassName.substring(packageName.length + 1)
    if(packageName.isEmpty()) className = fullClassName
    val resolvedClasses = myInfoCache.resolveClasses(className, packageName)
    val currentFiles: MutableList<PsiFile> = ArrayList()
    for (file in resolvedClasses.classes.values) {
      if (!file.isValid) continue
      val psiFile = PsiManager.getInstance(project).findFile(file)
      if (psiFile != null) {
        val navigationElement = psiFile.navigationElement // Sources may be downloaded.
        if (navigationElement is PsiFile) {
          currentFiles.add(navigationElement)
          continue
        }
        currentFiles.add(psiFile)
      }
    }
    return currentFiles
  }


  companion object {
    fun getInstance(project: Project): com.intellij.support.ide.inspector.action.ClassFinderService {
      return project.service()
    }

    private fun findProbableClasses(line: String): List<com.intellij.support.ide.inspector.action.ClassFinderService.ProbableClassName> {
      if (line.isBlank() || line.startsWith(com.intellij.support.ide.inspector.action.EXCEPTION_IN_THREAD) || line.startsWith(
          com.intellij.support.ide.inspector.action.CAUSED_BY
        ) || line.startsWith(com.intellij.support.ide.inspector.action.AT)) {
        return emptyList()
      }

      val result = mutableListOf<com.intellij.support.ide.inspector.action.ClassFinderService.ProbableClassName>()
      var start = -1
      var pointCount = 0
      var i = 0
      var first = true
      while (true) {
        if (!first) {
          val previousPoint = line.codePointAt(i)
          i += Character.charCount(previousPoint)
          if (i >= line.length) {
            break
          }
        }
        else {
          first = false
        }

        val ch = line.codePointAt(i)
        if (start == -1 && com.intellij.support.ide.inspector.action.ClassFinderService.Companion.isJavaIdentifierStart(ch)) {
          start = i
          continue
        }
        if (start != -1 && ch == com.intellij.support.ide.inspector.action.POINT_CODE) {
          pointCount++
          continue
        }
        if (start != -1 &&
          ((line.codePointAt(i - 1) == com.intellij.support.ide.inspector.action.POINT_CODE && com.intellij.support.ide.inspector.action.ClassFinderService.Companion.isJavaIdentifierStart(
            ch
          )) ||
                  (line.codePointAt(i - 1) != com.intellij.support.ide.inspector.action.POINT_CODE && com.intellij.support.ide.inspector.action.ClassFinderService.Companion.isJavaIdentifierPart(
                    ch
                  )))) {
          val charCount = Character.charCount(ch)
          if (i + charCount >= line.length && pointCount >= 2) {
            com.intellij.support.ide.inspector.action.ClassFinderService.Companion.addProbableClass(
              line,
              start,
              line.length,
              result
            )
          }
          continue
        }

        if (pointCount >= 2) {
          com.intellij.support.ide.inspector.action.ClassFinderService.Companion.addProbableClass(line, start, i, result)
        }
        pointCount = 0
        start = -1
      }
      return result
    }

    private fun isJavaIdentifierStart(cp: Int): Boolean {
      return cp >= 'a'.code && cp <= 'z'.code || cp >= 'A'.code && cp <= 'Z'.code ||
              Character.isJavaIdentifierStart(cp)
    }

    private fun isJavaIdentifierPart(cp: Int): Boolean {
      return cp >= '0'.code && cp <= '9'.code || cp >= 'a'.code && cp <= 'z'.code || cp >= 'A'.code && cp <= 'Z'.code ||
              Character.isJavaIdentifierPart(cp)
    }

    private fun addProbableClass(line: String,
                                 startInclusive: Int,
                                 endExclusive: Int,
                                 result: MutableList<com.intellij.support.ide.inspector.action.ClassFinderService.ProbableClassName>) {
      var actualEndExclusive = endExclusive
      if (actualEndExclusive > 0 && line[actualEndExclusive - 1] == '.') {
        actualEndExclusive--
      }
      val fullClassName = line.substring(startInclusive, actualEndExclusive)
      if (com.intellij.support.ide.inspector.action.ClassFinderService.Companion.canBeShortenedFullyQualifiedClassName(
          fullClassName
        ) && com.intellij.support.ide.inspector.action.ClassFinderService.Companion.isJavaStyle(fullClassName) && !com.intellij.support.ide.inspector.action.ClassFinderService.Companion.isHardcodedNotClass(
          fullClassName
        )
      ) {
        val probableClassName = com.intellij.support.ide.inspector.action.ClassFinderService.ProbableClassName(
          startInclusive + fullClassName.lastIndexOf(".") + 1,
          startInclusive + fullClassName.length, line, fullClassName
        )
        result.add(probableClassName)
      }
    }

    private fun isHardcodedNotClass(fullClassName: String): Boolean {
      return com.intellij.support.ide.inspector.action.HARDCODED_NOT_CLASS.contains(fullClassName)
    }

    private fun isJavaStyle(shortenedClassName: String): Boolean {
      if (shortenedClassName.isEmpty()) return false
      val indexOfSeparator = shortenedClassName.lastIndexOf('.')
      if (indexOfSeparator <= 0 || indexOfSeparator == shortenedClassName.lastIndex) return false
      return !shortenedClassName.contains("_") &&
              Character.isUpperCase(shortenedClassName[indexOfSeparator + 1]) &&
              Character.isLowerCase(shortenedClassName[0])
    }

    private fun canBeShortenedFullyQualifiedClassName(fullClassName: String): Boolean {
      var length = 0
      for (c in fullClassName) {
        if (c == '.') {
          if (length == 0) {
            return false
          }
          length = 0
        }
        else {
          length++
        }
      }
      return true
    }
  }

}