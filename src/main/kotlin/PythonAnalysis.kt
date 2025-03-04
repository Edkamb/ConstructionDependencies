package org.smolang.depender

import org.antlr.v4.runtime.*
import org.antlr.v4.runtime.tree.*
import java.io.File
import antlr.Python3Lexer
import antlr.Python3Parser
import antlr.Python3ParserBaseListener
import java.util.regex.Pattern

data class MethodAnalysis(
    val methodName: String,
    val columnsAccessed: MutableSet<String> = mutableSetOf(),
    val classesCreated: MutableSet<String> = mutableSetOf(),
    val instancesReferencedClasses: MutableSet<String> = mutableSetOf(),
    val classFields: MutableSet<String> = mutableSetOf()
)

class PythonFileAnalyzer {
    fun analyzeFile(filePath: String): List<MethodAnalysis> {
        val file = File(filePath)
        val input = CharStreams.fromPath(file.toPath())
        val lexer = Python3Lexer(input)
        val tokenStream = CommonTokenStream(lexer)
        val parser = Python3Parser(tokenStream)

        val tree = parser.file_input()
        val listener = PythonMethodListener()
        ParseTreeWalker.DEFAULT.walk(listener, tree)

        return listener.methodAnalyses
    }
}

class PythonMethodListener : Python3ParserBaseListener() {
    val methodAnalyses = mutableListOf<MethodAnalysis>()
    private var currentMethod: MethodAnalysis? = null

    override fun enterFuncdef(ctx: Python3Parser.FuncdefContext?) {
        // Extract method name and create a new MethodAnalysis
        ctx?.name()?.text?.let { methodName ->
            currentMethod = MethodAnalysis(methodName)
        }
    }

    override fun exitFuncdef(ctx: Python3Parser.FuncdefContext?) {
        // Add completed method analysis to the list
        currentMethod?.let {
            if (it.columnsAccessed.isNotEmpty() ||
                it.classesCreated.isNotEmpty() ||
                it.instancesReferencedClasses.isNotEmpty() ||
                it.classFields.isNotEmpty()) {
                methodAnalyses.add(it)
            }
        }
        currentMethod = null
    }

    override fun enterExpr_stmt(ctx: Python3Parser.Expr_stmtContext?) {
        currentMethod?.let { method ->
            // Look for pandas DataFrame column access
            ctx?.testlist_star_expr()?.forEach { text ->
                // Match patterns like df['column'] or df.loc[:, 'column']
                val columnPatterns = listOf(
                    "\\[['\"](.*?)['\"]\\]",
                    "\\.loc\\[.*?['\"](.*?)['\"]\\]"
                )

                columnPatterns.forEach { pattern ->
                    val matcher = Pattern.compile(pattern).matcher(text.text)
                    while (matcher.find()) {
                        matcher.group(1)?.let { column ->
                            method.columnsAccessed.add(column)
                        }
                    }
                }
            }

            // Look for class field assignments
            ctx?.text?.let { text ->
                // Match patterns for various field assignment styles
                val fieldPatterns = listOf(
                    // Patterns like new_sys.systemId.append(...)
                    "new_[a-zA-Z]+\\.([a-zA-Z]+)\\.append\\(",
                    // Patterns like new_sys.systemName = ...
                    "new_[a-zA-Z]+\\.([a-zA-Z]+)\\s*=",
                    // Patterns like a.accessedBy = ...
                    "[a-zA-Z]+\\.([a-zA-Z]+)\\s*=",
                    // Patterns like a.at.append(...)
                    "[a-zA-Z]+\\.([a-zA-Z]+)\\.append\\("
                )

                fieldPatterns.forEach { pattern ->
                    val matcher = Pattern.compile(pattern).matcher(text)
                    while (matcher.find()) {
                        matcher.group(1)?.let { field ->
                            method.classFields.add(field)
                        }
                    }
                }
            }
        }
    }

    override fun enterAtom_expr(ctx: Python3Parser.Atom_exprContext?) {
        currentMethod?.let { method ->
            // Look for class creation and instances references
            ctx?.text?.let { text ->
                // More flexible class creation patterns
                val classCreationPatterns = listOf(
                    "new_[a-zA-Z]+\\s*=\\s*onto\\.([A-Z][a-zA-Z]*)\\(\\)",
                    "[a-zA-Z]+\\s*=\\s*onto\\.([A-Z][a-zA-Z]*)\\(\\)",
                    "onto\\.([A-Z][a-zA-Z]*)\\(\\)"
                )

                classCreationPatterns.forEach { pattern ->
                    val classCreationMatcher = Pattern.compile(pattern).matcher(text)
                    while (classCreationMatcher.find()) {
                        classCreationMatcher.group(1)?.let { className ->
                            method.classesCreated.add(className)
                        }
                    }
                }

                // Match instances() references: onto.ClassName.instances()
                val instancesPattern = "onto\\.([A-Z][a-zA-Z]*)\\.instances\\(\\)".toRegex()
                val instancesMatch = instancesPattern.find(text)
                instancesMatch?.groupValues?.get(1)?.let { className ->
                    method.instancesReferencedClasses.add(className)
                }
            }
        }
    }
}

// Example usage
fun main() {
    val analyzer = PythonFileAnalyzer()
    val results = analyzer.analyzeFile("/home/edkam/IdeaProjects/Java/example/mapping.py")

    results.forEach { analysis ->
        println("Method: ${analysis.methodName}")
        println("Columns Accessed: ${analysis.columnsAccessed}")
        println("Classes Created: ${analysis.classesCreated}")
        println("Instances Referenced Classes: ${analysis.instancesReferencedClasses}")
        println("Class Fields Set: ${analysis.classFields}")
        println("---")
    }
}