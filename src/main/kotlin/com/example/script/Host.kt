package com.example.script

import java.io.File
import java.nio.file.FileSystems
import java.util.*
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.FileBasedScriptSource
import kotlin.script.experimental.host.FileScriptSource
import kotlin.script.experimental.host.createCompilationConfigurationFromTemplate
import kotlin.script.experimental.jvm.defaultJvmScriptingHostConfiguration
import kotlin.script.experimental.jvm.dependenciesFromClassContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.size != 1) {
        println("Usage: program /path/to/script")
        exitProcess(1)
    }

    val file = File(args[0])
    Host().evaluate(file)
}

class Host {
    private val jvmHost = BasicJvmScriptingHost()
    private val compilationConfiguration = createCompilationConfigurationFromTemplate(
        KotlinType(CustomScript::class),
        defaultJvmScriptingHostConfiguration
    )

    fun evaluate(file: File) {
        val scriptSource = file.toScriptSource()
        val result = jvmHost.eval(scriptSource, compilationConfiguration, null)
        if (result is ResultWithDiagnostics.Failure) {
            println("Script evaluation failed:")
            result.reports.forEach { report ->
                println(" - [${report.severity}] ${report.message}")
            }
        }
    }
}

@UseExperimental(ExperimentalStdlibApi::class)
fun File.toScriptSource(): SourceCode {
    // We use a custom FileScriptSource so that we can manually set its name. This name will be used by the Kotlin
    // compiler to compute the generated class name. With the default FileScriptSource, a file named 'foo.custom.kts'
    // will have the class name Foo_custom. This means that the compilation will fail if a script file imports a script
    // with the same file name (e.g. in another folder) because both generated classes will have the same name.
    //
    // Therefore, we assume the script is in a subfolder named 'scripts/' and include its path in the name. For
    // instance, the script scripts/sub_folder/foo.custom.kts will be named "SubFolderFoo". However, for some reason the
    // Kotlin compiler will append _custom because of the file extension, so the final name will be
    // "SubFolderFoo_custom".

    // We normalize the Path to remove redundant sub paths like '..' and './'.
    val absolutePath = this.toPath().normalize().toAbsolutePath().toString()
    val scriptsFolder = "scripts/"
    val index = absolutePath.indexOf(scriptsFolder)
    if (index == -1) {
        throw IllegalStateException("The script is not in the expected folder.")
    }

    val relativePath = absolutePath.substring(index + scriptsFolder.length)
    val extensionWithPrefixDot = ".custom.kts"
    val relativePathWithoutExtension = if (relativePath.endsWith(extensionWithPrefixDot)) {
        relativePath.substring(0, relativePath.length - extensionWithPrefixDot.length)
    } else {
        relativePath
    }

    // sub_folder/foo.custom.kts will be named SubFolderFoo.
    val name = relativePathWithoutExtension
        .split(FileSystems.getDefault().separator)
        .flatMap { it.split('.') }
        .flatMap { it.split('_') }
        .map { it.capitalize(Locale.ENGLISH) }
        .map {
            // Replace all non alphanumeric characters by '_'.
            it.replace(Regex("[^a-zA-Z0-9]"), "_")
        }
        .joinToString("")

    println("Path: $absolutePath | Name: $name")
    return CustomFileScriptSource(this, name)
}

class CustomFileScriptSource(file: File, override val name: String): FileScriptSource(file)

/** A custom script definition that allows scripts to import other scripts. */
@KotlinScript(
    fileExtension = "custom.kts",
    compilationConfiguration = CompilationConfiguration::class
)
abstract class CustomScript

@Target(AnnotationTarget.FILE)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
annotation class Import(val path: String)

object CompilationConfiguration : ScriptCompilationConfiguration(
    {
        defaultImports(Import::class)
        jvm {
            dependenciesFromClassContext(CustomScript::class, wholeClasspath = true)
        }
        refineConfiguration {
            onAnnotations<Import> { context ->
                // The context.script here is a KtFileScriptSource, unfortunately not the scriptSource created above...
                val script = context.script as FileBasedScriptSource
                val scriptFile = script.file

                val imports = context.collectedData
                    ?.get(ScriptCollectedData.foundAnnotations)
                    ?.filterIsInstance<Import>() ?: emptyList()
                val importedScripts = imports.mapNotNull { import ->
                    val relativePath = import.path
                    val importedFile = scriptFile.parentFile
                        ?.resolve(relativePath)
                        ?.takeIf { it.exists() } ?: return@mapNotNull null
                    importedFile.toScriptSource()
                }

                if (importedScripts.isEmpty()) return@onAnnotations context.compilationConfiguration.asSuccess()

                ScriptCompilationConfiguration(context.compilationConfiguration) {
                    importScripts.append(importedScripts)
                }.asSuccess()
            }
        }
    }
)
