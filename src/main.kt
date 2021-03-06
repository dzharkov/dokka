package org.jetbrains.dokka

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.sampullara.cli.Args
import com.sampullara.cli.Argument
import org.jetbrains.kotlin.cli.common.arguments.ValueDescription
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.config.JavaSourceRoot
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.utils.PathUtil
import java.io.File

class DokkaArguments {
    Argument(value = "src", description = "Source file or directory (allows many paths separated by the system path separator)")
    ValueDescription("<path>")
    public var src: String = ""

    Argument(value = "srcLink", description = "Mapping between a source directory and a Web site for browsing the code")
    ValueDescription("<path>=<url>[#lineSuffix]")
    public var srcLink: String = ""

    Argument(value = "include", description = "Markdown files to load (allows many paths separated by the system path separator)")
    ValueDescription("<path>")
    public var include: String = ""

    Argument(value = "samples", description = "Source root for samples")
    ValueDescription("<path>")
    public var samples: String = ""

    Argument(value = "output", description = "Output directory path")
    ValueDescription("<path>")
    public var outputDir: String = "out/doc/"

    Argument(value = "format", description = "Output format (text, html, markdown, jekyll, kotlin-website)")
    ValueDescription("<name>")
    public var outputFormat: String = "html"

    Argument(value = "module", description = "Name of the documentation module")
    ValueDescription("<name>")
    public var moduleName: String = ""

    Argument(value = "classpath", description = "Classpath for symbol resolution")
    ValueDescription("<path>")
    public var classpath: String = ""

    Argument(value = "nodeprecated", description = "Exclude deprecated members from documentation")
    public var nodeprecated: Boolean = false

}

private fun parseSourceLinkDefinition(srcLink: String): SourceLinkDefinition {
    val (path, urlAndLine) = srcLink.split('=')
    return SourceLinkDefinition(File(path).getAbsolutePath(),
            urlAndLine.substringBefore("#"),
            urlAndLine.substringAfter("#", "").let { if (it.isEmpty()) null else "#" + it })
}

public fun main(args: Array<String>) {
    val arguments = DokkaArguments()
    val freeArgs: List<String> = Args.parse(arguments, args) ?: listOf()
    val sources = if (arguments.src.isNotEmpty()) arguments.src.split(File.pathSeparatorChar).toList() + freeArgs else freeArgs
    val samples = if (arguments.samples.isNotEmpty()) arguments.samples.split(File.pathSeparatorChar).toList() else listOf()
    val includes = if (arguments.include.isNotEmpty()) arguments.include.split(File.pathSeparatorChar).toList() else listOf()

    val sourceLinks = if (arguments.srcLink.isNotEmpty() && arguments.srcLink.contains("="))
        listOf(parseSourceLinkDefinition(arguments.srcLink))
    else {
        if (arguments.srcLink.isNotEmpty()) {
            println("Warning: Invalid -srcLink syntax. Expected: <path>=<url>[#lineSuffix]. No source links will be generated.")
        }
        listOf()
    }

    val classPath = arguments.classpath.split(File.pathSeparatorChar).toList()
    val generator = DokkaGenerator(
            DokkaConsoleLogger,
            classPath,
            sources,
            samples,
            includes,
            arguments.moduleName,
            arguments.outputDir,
            arguments.outputFormat,
            sourceLinks,
            arguments.nodeprecated)

    generator.generate()
    DokkaConsoleLogger.report()
}

interface DokkaLogger {
    fun info(message: String)
    fun warn(message: String)
    fun error(message: String)
}

object DokkaConsoleLogger: DokkaLogger {
    var warningCount: Int = 0

    override fun info(message: String) = println(message)
    override fun warn(message: String) {
        println("WARN: $message")
        warningCount++
    }

    override fun error(message: String) = println("ERROR: $message")

    fun report() {
        if (warningCount > 0) {
            println("Generation completed with $warningCount warnings")
        } else {
            println("Generation completed successfully")
        }
    }
}

class DokkaMessageCollector(val logger: DokkaLogger): MessageCollector {
    override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageLocation) {
        logger.error(MessageRenderer.PLAIN_FULL_PATHS.render(severity, message, location))
    }
}

class DokkaGenerator(val logger: DokkaLogger,
                     val classpath: List<String>,
                     val sources: List<String>,
                     val samples: List<String>,
                     val includes: List<String>,
                     val moduleName: String,
                     val outputDir: String,
                     val outputFormat: String,
                     val sourceLinks: List<SourceLinkDefinition>,
                     val skipDeprecated: Boolean = false) {
    fun generate() {
        val environment = createAnalysisEnvironment()

        logger.info("Module: ${moduleName}")
        logger.info("Output: ${File(outputDir).getAbsolutePath()}")
        logger.info("Sources: ${environment.sources.join()}")
        logger.info("Classpath: ${environment.classpath.joinToString()}")

        logger.info("Analysing sources and libraries... ")
        val startAnalyse = System.currentTimeMillis()

        val options = DocumentationOptions(false, sourceLinks = sourceLinks, skipDeprecated = skipDeprecated)
        val documentation = buildDocumentationModule(environment, moduleName, options, includes, { isSample(it) }, logger)

        val timeAnalyse = System.currentTimeMillis() - startAnalyse
        logger.info("done in ${timeAnalyse / 1000} secs")

        val startBuild = System.currentTimeMillis()
        val signatureGenerator = KotlinLanguageService()
        val locationService = FoldersLocationService(outputDir)
        val templateService = HtmlTemplateService.default("/dokka/styles/style.css")

        val (formatter, outlineFormatter) = when (outputFormat) {
            "html" -> {
                val htmlFormatService = HtmlFormatService(locationService, signatureGenerator, templateService)
                htmlFormatService to htmlFormatService
            }
            "markdown" -> MarkdownFormatService(locationService, signatureGenerator) to null
            "jekyll" -> JekyllFormatService(locationService.withExtension("html"), signatureGenerator) to null
            "kotlin-website" -> KotlinWebsiteFormatService(locationService.withExtension("html"), signatureGenerator) to
                    YamlOutlineService(locationService, signatureGenerator)
            else -> {
                logger.error("Unrecognized output format ${outputFormat}")
                null to null
            }
        }
        if (formatter == null) return

        val generator = FileGenerator(signatureGenerator, locationService.withExtension(formatter.extension),
                formatter, outlineFormatter)
        logger.info("Generating pages... ")
        generator.buildPage(documentation)
        generator.buildOutline(documentation)
        val timeBuild = System.currentTimeMillis() - startBuild
        logger.info("done in ${timeBuild / 1000} secs")
        Disposer.dispose(environment)
    }

    fun createAnalysisEnvironment(): AnalysisEnvironment {
        val environment = AnalysisEnvironment(DokkaMessageCollector(logger)) {
            addClasspath(PathUtil.getJdkClassesRoots())
            //   addClasspath(PathUtil.getKotlinPathsForCompiler().getRuntimePath())
            for (element in this@DokkaGenerator.classpath) {
                addClasspath(File(element))
            }

            addSources(this@DokkaGenerator.sources)
            addSources(this@DokkaGenerator.samples)
        }
        return environment
    }

    fun isSample(file: PsiFile): Boolean {
        val sourceFile = File(file.getVirtualFile()!!.getPath())
        return samples.none { sample ->
            val canonicalSample = File(sample).canonicalPath
            val canonicalSource = sourceFile.canonicalPath
            canonicalSource.startsWith(canonicalSample)
        }
    }
}

fun buildDocumentationModule(environment: AnalysisEnvironment,
                             moduleName: String,
                             options: DocumentationOptions,
                             includes: List<String> = listOf(),
                             filesToDocumentFilter: (PsiFile) -> Boolean = { file -> true },
                             logger: DokkaLogger): DocumentationModule {
    val documentation = environment.withContext { environment, resolutionFacade, session ->
        val fragmentFiles = environment.getSourceFiles().filter(filesToDocumentFilter)
        val fragments = fragmentFiles.map { session.getPackageFragment(it.getPackageFqName()) }.filterNotNull().distinct()

        val refGraph = NodeReferenceGraph()
        val documentationBuilder = DocumentationBuilder(resolutionFacade, session, options, refGraph, logger)
        val packageDocs = PackageDocs(documentationBuilder, fragments.firstOrNull(), logger)
        for (include in includes) {
            packageDocs.parse(include)
        }
        val documentationModule = DocumentationModule(moduleName, packageDocs.moduleContent)

        with(documentationBuilder) {
            documentationModule.appendFragments(fragments, packageDocs.packageContent)
        }

        val javaFiles = environment.getJavaSourceFiles().filter(filesToDocumentFilter)
        val javaDocumentationBuilder = JavaDocumentationBuilder(options, refGraph)
        javaFiles.map { javaDocumentationBuilder.appendFile(it, documentationModule) }

        refGraph.resolveReferences()

        documentationModule
    }

    return documentation
}


fun KotlinCoreEnvironment.getJavaSourceFiles(): List<PsiJavaFile> {
    val sourceRoots = configuration.get(CommonConfigurationKeys.CONTENT_ROOTS)
            ?.filterIsInstance<JavaSourceRoot>()
            ?.map { it.file }
            ?: listOf()

    val result = arrayListOf<PsiJavaFile>()
    val localFileSystem = VirtualFileManager.getInstance().getFileSystem("file")
    sourceRoots.forEach { sourceRoot ->
        sourceRoot.getAbsoluteFile().walkTopDown().forEach {
            val vFile = localFileSystem.findFileByPath(it.path)
            if (vFile != null) {
                val psiFile = PsiManager.getInstance(project).findFile(vFile)
                if (psiFile is PsiJavaFile) {
                    result.add(psiFile)
                }
            }
        }
    }
    return result
}
