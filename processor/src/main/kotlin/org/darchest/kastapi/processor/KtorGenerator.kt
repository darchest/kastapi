/*
 * Copyright 2021-2025, Darchest and contributors.
 * Licensed under the Apache License, Version 2.0
 */

package org.darchest.kastapi.processor

import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.symbol.KSFile
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.buildCodeBlock
import java.io.OutputStreamWriter

class KtorGenerator: KastAPIGenerator() {

    override fun generateFiles(packages: Set<PackageInfo>) {
        val packageName = "org.darchest.kastapi.generated"
        val className = "GeneratedRoutes"

        val files = mutableSetOf<KSFile>()
        packages.forEach { pkg ->
            pkg.bundles.forEach { bundle ->
                files += bundle.cls.containingFile!!
            }
        }

        val fileSpecBuilder = FileSpec.builder(packageName, className)
        fileSpecBuilder.addImport("io.ktor.server.application", "call")
        packages.flatMap { it.bundles }.flatMap { it.endpoints }
            .mapNotNull { it.method }
            .toSet()
            .forEach { method ->
                fileSpecBuilder.addImport("io.ktor.server.routing", method)
            }
        for (pkg in packages) {
            fileSpecBuilder.addFunction(buildRoutesFunction(pkg))
        }
        val fileSpec = fileSpecBuilder.build()

        val file = codeGenerator.createNewFile(
            Dependencies(true, *files.toTypedArray()),
            packageName,
            className
        )

        OutputStreamWriter(file, Charsets.UTF_8).use { writer ->
            fileSpec.writeTo(writer)
        }
    }

    private fun buildRoutesFunction(pkg: PackageInfo): FunSpec {
        return FunSpec.builder("${pkg.name}Routes")
            .receiver(routeClass)
            .addCode(buildCodeBlock {
                for (bundle in pkg.bundles) {
                    beginControlFlow("%M(%S)", routeMember, bundle.path)
                    for (endpoint in bundle.endpoints) {
                        add(buildEndpointCode(endpoint, bundle))
                    }
                    endControlFlow()
                }
            })
            .build()
    }

    private fun buildEndpointCode(endpoint: EndpointInfo, bundle: RoutesBundleInfo): CodeBlock {
        return buildCodeBlock {
            beginControlFlow("%L(%S)", endpoint.method, endpoint.path)

            val args = mutableListOf<String>()
            val hasMultipart = endpoint.arguments.any { it.source == ParameterSource.Multipart }

            if (endpoint.arguments.any { it.source == ParameterSource.Form }) {
                addStatement("val form = call.%M()", receiveParametersMember)
            } else if (hasMultipart) {
                addStatement("val multipart = call.%M()", receiveMultipartMember)
            }

            endpoint.arguments.forEachIndexed { ind, arg ->
                if (arg.type == applicationCallFqn) {
                    args += "call"
                    return@forEachIndexed
                }

                val localName = "arg$ind"
                args += localName
                val argType = ClassName.bestGuess(arg.type)

                if (arg.source == ParameterSource.Multipart) {
                    addStatement("var %L: %T? = %L", localName, argType, parameterGetterCode(arg))
                } else {
                    addStatement("val %L = %L", localName, parameterGetterCode(arg))
                }
            }

            if (hasMultipart) {
                addStatement("var part = multipart.readPart()")
                beginControlFlow("while (part != null)")
                beginControlFlow("when (part.name)")
                endpoint.arguments.forEachIndexed { ind, arg ->
                    if (arg.source != ParameterSource.Multipart)
                        return@forEachIndexed

                    val localName = "arg$ind"
                    val argType = ClassName.bestGuess(arg.type)
                    addStatement(
                        "%S -> %L = %T.getMultipartParameter(part, %T::class)",
                        arg.name,
                        localName,
                        ktorUtility,
                        argType
                    )
                }
                endControlFlow()
                addStatement("part.dispose()")
                addStatement("part = multipart.readPart()")
                endControlFlow()
            }

            endpoint.arguments.forEachIndexed { ind, arg ->
                if (arg.source != ParameterSource.Multipart || arg.canBeNull)
                    return@forEachIndexed

                val localName = "arg$ind"
                beginControlFlow("if (%L == null)", localName)
                addStatement("throw RuntimeException(%S)", "Missing required form parameter '${arg.name}'")
                endControlFlow()
            }

            val allWrappers = resolveWrappers(bundle, endpoint)
            val apiClass = ClassName.bestGuess(bundle.cls.qualifiedName!!.asString())
            addStatement("val api = %T()", apiClass)

            if (allWrappers.isEmpty()) {
                addStatement("val result = api.%L(%L)", endpoint.fnName, args.joinToString(", "))
            } else {
                add("val result = ")
                for (wrapper in allWrappers) {
                    var wrpName = wrapper
                    var wrpParams: String? = null
                    if (wrpName.contains("(") && wrpName.endsWith(")")) {
                        wrpName = wrapper.substring(0, wrapper.indexOf('('))
                        wrpParams = wrapper.substring(wrapper.indexOf('(') + 1, wrapper.length - 1)
                    }
                    if (wrpParams != null) {
                        beginControlFlow("%L(%L).wrap", wrpName, wrpParams)
                    } else {
                        beginControlFlow("%L().wrap", wrpName)
                    }
                }
                addStatement("api.%L(%L)", endpoint.fnName, args.joinToString(", "))
                repeat(allWrappers.size) {
                    endControlFlow()
                }
            }

            val returnType = ClassName.bestGuess(endpoint.returnType)
            if (endpoint.pairWithCode) {
                addStatement(
                    "%T.respond(call, result.first, result.second, %T::class)",
                    ktorUtility,
                    returnType
                )
            } else {
                addStatement(
                    "%T.respond(call, %L, result, %T::class)",
                    ktorUtility,
                    endpoint.codeOnSuccess ?: 200,
                    returnType
                )
            }

            endControlFlow()
        }
    }

    private fun parameterGetterCode(arg: ArgumentInfo): CodeBlock {
        val argType = ClassName.bestGuess(arg.type)
        return when (arg.source) {
            ParameterSource.Path -> {
                val fnName = if (arg.canBeNull) "getOptionalPathParameter" else "getRequiredPathParameter"
                CodeBlock.of("%T.%L(call, %S, %T::class)", ktorUtility, fnName, arg.name, argType)
            }
            ParameterSource.Query -> {
                val fnName = if (arg.canBeNull) "getOptionalQueryParameter" else "getRequiredQueryParameter"
                CodeBlock.of("%T.%L(call, %S, %T::class)", ktorUtility, fnName, arg.name, argType)
            }
            ParameterSource.Body -> {
                CodeBlock.of("call.%M<%T>()", receiveMember, argType)
            }
            ParameterSource.Form -> {
                val fnName = if (arg.canBeNull) "getOptionalFormParameter" else "getRequiredFormParameter"
                CodeBlock.of("%T.%L(form, %S, %T::class)", ktorUtility, fnName, arg.name, argType)
            }
            ParameterSource.Multipart -> {
                CodeBlock.of("null")
            }
        }
    }

    private fun resolveWrappers(bundle: RoutesBundleInfo, endpoint: EndpointInfo): List<String> {
        val allWrappers = mutableListOf<String>()
        val globalWrappers = KastApiProcessor.collectIndexedValues(options, "org.darchest.kastapi.defaultWrappers")
        allWrappers.addAll(globalWrappers)

        allWrappers.addAll(bundle.wrappers.distinct())
        allWrappers.removeAll { bundle.removedWrappers.contains(if (it.contains('(')) it.substring(0, it.indexOf('(')) else it) }
        allWrappers.addAll(endpoint.wrappers.distinct())
        allWrappers.removeAll { endpoint.removedWrappers.contains(if (it.contains('(')) it.substring(0, it.indexOf('(')) else it) }

        if (endpoint.removeAllWrappers)
            allWrappers.clear()

        return allWrappers
    }

    companion object {
        private const val applicationCallFqn = "io.ktor.server.application.ApplicationCall"

        private val routeClass = ClassName("io.ktor.server.routing", "Route")
        private val ktorUtility = ClassName("org.darchest.kastapi.ktor.utility", "KtorUtility")

        private val routeMember = MemberName("io.ktor.server.routing", "route")
        private val receiveMember = MemberName("io.ktor.server.request", "receive")
        private val receiveParametersMember = MemberName("io.ktor.server.request", "receiveParameters")
        private val receiveMultipartMember = MemberName("io.ktor.server.request", "receiveMultipart")
    }
}
