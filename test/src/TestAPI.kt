package org.jetbrains.dokka.tests

import org.jetbrains.jet.cli.common.messages.*
import com.intellij.openapi.util.*
import kotlin.test.fail
import org.jetbrains.dokka.*

public fun verifyModel(vararg files: String, verifier: (DocumentationModule) -> Unit) {
    val messageCollector = object : MessageCollector {
        override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageLocation) {
            when (severity) {
                CompilerMessageSeverity.WARNING,
                CompilerMessageSeverity.LOGGING,
                CompilerMessageSeverity.OUTPUT,
                CompilerMessageSeverity.INFO,
                CompilerMessageSeverity.ERROR -> {
                    println("$severity: $message at $location")
                }
                CompilerMessageSeverity.EXCEPTION -> {
                    fail("$severity: $message at $location")
                }
            }
        }
    }

    val environment = AnalysisEnvironment(messageCollector) {
        addSources(files.toList())
    }

    val options = DocumentationOptions(includeNonPublic = true)

    val documentation = environment.withContext { environment, module, context ->
        val documentationModule = DocumentationModule("test")
        val documentationBuilder = DocumentationBuilder(context, options)
        with(documentationBuilder) {
            documentationModule.appendFiles(environment.getSourceFiles())
        }
        documentationBuilder.resolveReferences(documentationModule)
        documentationModule
    }
    verifier(documentation)
    Disposer.dispose(environment)
}

fun StringBuilder.appendChildren(node: ContentNode): StringBuilder {
    for (child in node.children) {
        val childText = child.toTestString()
        append(childText)
    }
    return this
}

fun StringBuilder.appendNode(node: ContentNode): StringBuilder {
    when (node) {
        is ContentText -> {
            append(node.text)
        }
        is ContentEmphasis -> append("*").appendChildren(node).append("*")
        else -> {
            appendChildren(node)
        }
    }
    return this
}

fun ContentNode.toTestString(): String {
    val node = this
    return StringBuilder {
        appendNode(node)
    }.toString()
}
