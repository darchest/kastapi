/*
 * Copyright 2021-2025, Darchest and contributors.
 * Licensed under the Apache License, Version 2.0
 */

package org.darchest.kastapi.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSFile
import java.io.OutputStreamWriter

class KtorGenerator(
    private val resolver: Resolver,
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>
) {

    fun generateFiles(packages: Set<PackageInfo>) {
        val packageName = "org.darchest.kastapi.generated"
        val className = "GeneratedRoutes"

        val files = mutableSetOf<KSFile>()
        packages.forEach { pkg ->
            pkg.bundles.forEach { bundle ->
                files += bundle.cls.containingFile!!
            }
        }

        val file = codeGenerator.createNewFile(
            Dependencies(true, *files.toTypedArray()),
            packageName,
            className
        )

        OutputStreamWriter(file, Charsets.UTF_8).use { writer ->
            writer.write("package $packageName\n\n")
            writer.write("import io.ktor.http.*\n")
            writer.write("import io.ktor.server.application.*\n")
            writer.write("import io.ktor.server.request.*\n")
            writer.write("import io.ktor.server.routing.*\n")
            writer.write("import io.ktor.server.response.*\n")
            writer.write("import org.darchest.kastapi.ktor.utility.KtorUtility\n")

            for (pkg in packages) {
                writer.write("\nfun Route.${pkg.name}Routes() {\n")

                for (bundle in pkg.bundles) {
                    writer.write("    route(\"${bundle.path}\") {\n")

                    for (endpoint in bundle.endpoints) {
                        writeEndpoint(writer, endpoint, bundle, "        ")
                    }

                    writer.write("    }\n")
                }

                writer.write("}\n")
            }
        }
    }

    private fun writeEndpoint(writer: OutputStreamWriter, endpoint: EndpointInfo, bundle: RoutesBundleInfo, spacer: String) {
        val methodFn = endpoint.method
        writer.write("$spacer$methodFn(\"${endpoint.path}\") {\n")

        val args = mutableListOf<String>()

        val hasMultipart = endpoint.arguments.find { it.source == ParameterSource.Multipart } != null

        if (endpoint.arguments.find { it.source == ParameterSource.Form } != null) {
            writer.write("$spacer    val form = call.receiveParameters()\n")
        } else if (hasMultipart) {
            writer.write("$spacer    val multipart = call.receiveMultipart()\n")
        }

        endpoint.arguments.forEachIndexed { ind, arg ->
            if (arg.type == "io.ktor.server.application.ApplicationCall") {
                args += "call"
                return@forEachIndexed
            }

            val localName = "arg$ind"
            args += localName

            writer.write("$spacer    ")
            if (arg.source == ParameterSource.Multipart)
                writer.write("var")
            else
                writer.write("val")
            writer.write(" $localName")
            if (arg.source == ParameterSource.Multipart)
                writer.write(": ${arg.type}?")
            writer.write(" = ")
            writeParameterGetter(writer, arg)
            writer.write("\n")
        }

        val part = "part"

        if (hasMultipart) {
            writer.write("$spacer    var $part = multipart.readPart()\n")
            writer.write("$spacer    while ($part != null) {\n")
            writer.write("$spacer        when ($part.name) {\n")
            endpoint.arguments.forEachIndexed { ind, arg ->
                if (arg.source != ParameterSource.Multipart)
                    return@forEachIndexed

                val localName = "arg$ind"

                writer.write("$spacer            \"${arg.name}\" -> $localName = KtorUtility.getMultipartParameter($part, ${arg.type}::class)\n")
            }
            writer.write("$spacer        }\n")
            writer.write("$spacer        $part.dispose()\n")
            writer.write("$spacer        $part = multipart.readPart()\n")
            writer.write("$spacer    }\n")
        }

        endpoint.arguments.forEachIndexed { ind, arg ->
            if (arg.source != ParameterSource.Multipart || arg.canBeNull)
                return@forEachIndexed

            val localName = "arg$ind"

            writer.write("$spacer    if ($localName == null)\n")
            writer.write("$spacer        throw RuntimeException(\"Missing required form parameter '${arg.name}'\")\n")
        }

        val allWrappers = mutableListOf<String>()
        if (options.containsKey("org.darchest.kastapi.defaultWrappers"))
            allWrappers.addAll(options["org.darchest.kastapi.defaultWrappers"]!!.split(";"))

        allWrappers.addAll(bundle.wrappers.distinct())
        allWrappers.removeAll(bundle.removedWrappers)
        allWrappers.addAll(endpoint.wrappers.distinct())
        allWrappers.removeAll(endpoint.removedWrappers)

        writer.write("$spacer    val api = ${bundle.cls.qualifiedName!!.asString()}()\n")
        writer.write("$spacer    val result = ")
        for ((ind, wrapper) in allWrappers.withIndex()) {
            if (ind != 0) {
                writer.write("$spacer    ")
                repeat(ind) { writer.write("    ") }
            }
            writer.write("$wrapper().wrap {\n")
        }
        if (allWrappers.isNotEmpty()) {
            writer.write("$spacer    ")
            repeat(allWrappers.size) { writer.write("    ") }
        }
        writer.write("api.${endpoint.fnName}(${args.joinToString(", ")})\n")
        for ((ind, wrapper) in allWrappers.withIndex()) {
            writer.write("$spacer    ")
            repeat(allWrappers.size - ind - 1) { writer.write("    ") }
            writer.write("}\n")
        }

        if (endpoint.pairWithCode) {
            writer.write("$spacer    KtorUtility.respond(call, result.first, result.second, ${endpoint.returnType}::class)\n")
        } else {
            writer.write("$spacer    KtorUtility.respond(call, ")
            writer.write("${endpoint.codeOnSuccess ?: 200}, ")
            writer.write("result, ${endpoint.returnType}::class)\n")
        }
        writer.write("${spacer}}\n")
    }

    private fun writeParameterGetter(writer: OutputStreamWriter, arg: ArgumentInfo) {
        when (arg.source) {
            ParameterSource.Path -> {
                val req = if (arg.canBeNull) "Optional" else "Required"
                val fnName = "get${req}PathParameter"
                writer.write("KtorUtility.$fnName(call, \"${arg.name}\", ${arg.type}::class)")
            }
            ParameterSource.Query -> {
                val req = if (arg.canBeNull) "Optional" else "Required"
                val fnName = "get${req}QueryParameter"
                writer.write("KtorUtility.$fnName(call, \"${arg.name}\", ${arg.type}::class)")
            }
            ParameterSource.Body ->  {
                writer.write("call.receive<${arg.type}>()")
            }
            ParameterSource.Form -> {
                val req = if (arg.canBeNull) "Optional" else "Required"
                val fnName = "get${req}FormParameter"
                writer.write("KtorUtility.$fnName(form, \"${arg.name}\", ${arg.type}::class)")
            }
            ParameterSource.Multipart -> {
                writer.write("null")
            }
        }
    }
}