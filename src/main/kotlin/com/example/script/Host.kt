package com.example.script

import java.io.File
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.FileBasedScriptSource
import kotlin.script.experimental.host.createCompilationConfigurationFromTemplate
import kotlin.script.experimental.host.toScriptSource
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
