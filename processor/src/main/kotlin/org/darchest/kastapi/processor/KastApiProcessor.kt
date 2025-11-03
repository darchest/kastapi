/*
 * Copyright 2021-2025, Darchest and contributors.
 * Licensed under the Apache License, Version 2.0
 */

package org.darchest.kastapi.processor

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.isAnnotationPresent
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.*
import org.darchest.kastapi.annotations.*

class PackageInfo(
    val name: String
) {
    val bundles = mutableListOf<RoutesBundleInfo>()
}

class RoutesBundleInfo(
    val cls: KSClassDeclaration,
    val path: String
) {
    val wrappers = mutableListOf<String>()
    val removedWrappers = mutableListOf<String>()
    val endpoints = mutableListOf<EndpointInfo>()
}

class EndpointInfo(
    val method: String?,
    val fnName: String,
    val path: String,
) {
    val arguments = mutableListOf<ArgumentInfo>()
    var codeOnSuccess: Int? = null
    val wrappers = mutableListOf<String>()
    val removedWrappers = mutableListOf<String>()
    var pairWithCode: Boolean = false
    var fileResult: Boolean = false
    var returnType: String = "Unit"
}

class ArgumentInfo(
    val name: String,
    val type: String,
    val canBeNull: Boolean,
    val source: ParameterSource
)

enum class ParameterSource {
    Path,
    Query,
    Body,
    Form,
    Multipart,
}

class KastApiProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>
): SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val routes = resolver.getSymbolsWithAnnotation(Routes::class.qualifiedName!!)
            .filterIsInstance<KSClassDeclaration>()

        if (routes.none()) return emptyList()

        val packages = mutableMapOf<String, PackageInfo>()

        for (routeCls in routes) {
            val packageName = getPackageName(routeCls)

            val pkg = packages.getOrPut(packageName) { PackageInfo(packageName) }

            val bundlePath = getRouteBundlePath(routeCls)

            val bundle = RoutesBundleInfo(
                routeCls,
                bundlePath
            )

            bundle.wrappers.addAll(getAddWrappersList(routeCls))
            bundle.removedWrappers.addAll(getRemoveWrappersList(routeCls))

            pkg.bundles += bundle

            val functions = routeCls.getDeclaredFunctions()

            for (fn in functions) {
                val pair = getFnHttpMethodAndPath(fn) ?: continue
                val (httpMethod, path) = pair

                val endpointInfo = EndpointInfo(
                    httpMethod,
                    fn.simpleName.asString(),
                    path
                )
                bundle.endpoints += endpointInfo

                var returnType = fn.returnType?.resolve()
                if (returnType != null && returnType.declaration.qualifiedName?.asString() == "kotlin.Pair") {
                    endpointInfo.pairWithCode = returnType.arguments[0].type?.resolve()?.declaration?.qualifiedName?.asString() == "kotlin.Int"
                    returnType = returnType.arguments[1].type?.resolve()
                }

                if (returnType != null && returnType.declaration.qualifiedName?.asString() == "org.darchest.kastapi.ktor.utility.InMemoryFile") {
                    endpointInfo.fileResult = true
                }
                endpointInfo.returnType = returnType?.declaration?.qualifiedName?.asString() ?: "Unit"

                val codeAnno = fn.annotations.find { it.shortName.asString() == "CodeOnSuccess" }
                if (codeAnno != null)
                    endpointInfo.codeOnSuccess = codeAnno.arguments[0].value as Int

                endpointInfo.wrappers.addAll(getAddWrappersList(fn))
                endpointInfo.removedWrappers.addAll(getRemoveWrappersList(fn))

                for (param in fn.parameters) {
                    val source = detectParameterSourceByAnnotation(param)
                    val type = param.type.resolve()
                    val arg = ArgumentInfo(
                        param.name!!.asString(),
                        type.declaration.qualifiedName!!.asString(),
                        type.isMarkedNullable,
                        source
                    )
                    endpointInfo.arguments.add(arg)
                }
            }
        }

        val generators = collectIndexedValues(options, "org.darchest.kastapi.generators")

        if (generators.contains("org.darchest.kastapi.processor.KastApiProcessor") || generators.isEmpty()) {
            val generator = KtorGenerator(resolver, codeGenerator, logger, options)
            generator.generateFiles(packages.values.toSet())
        }

        if (generators.contains("org.darchest.kastapi.processor.OpenAPIGenerator") || generators.isEmpty()) {
            val docsGenerator = OpenAPIGenerator(resolver, codeGenerator, logger, options)
            docsGenerator.generateFiles(packages.values.toSet())
        }


        return emptyList()
    }

    private val annoToParameterSource = listOf(
        Body::class to ParameterSource.Body,
        Query::class to ParameterSource.Query,
        Form::class to ParameterSource.Form,
        Multipart::class to ParameterSource.Multipart,
    )

    @OptIn(KspExperimental::class)
    private fun detectParameterSourceByAnnotation(param: KSValueParameter): ParameterSource {
        for ((cls, parameterSource) in annoToParameterSource) {
            if (param.isAnnotationPresent(cls))
                return parameterSource
        }
        return ParameterSource.Path
    }

    private fun getPackageName(cls: KSClassDeclaration): String {
        val packageNameAnno = cls.annotations.find { it.shortName.asString() == "PackageName" }
        if (packageNameAnno != null)
            return packageNameAnno.arguments[0].value as String
        return options["org.darchest.kastapi.defaultRouteFnPrefix"] ?: "generated"
    }

    private fun getRouteBundlePath(cls: KSClassDeclaration): String {
        val packageNameAnno = cls.annotations.find { it.shortName.asString() == "Routes" }
        if (packageNameAnno != null)
            return packageNameAnno.arguments[0].value as String
        return ""
    }

    private fun getAddWrappersList(decl: KSDeclaration): List<String> {
        val list = mutableListOf<String>()
        val anno = decl.annotations.find { it.shortName.asString() == "AddWrappers" }
        if (anno != null) {
            val classes = anno.arguments[0].value as List<KSType>
            for (cls in classes)
                list += cls.declaration.qualifiedName!!.asString()
        }
        return list
    }

    private fun getRemoveWrappersList(decl: KSDeclaration): List<String> {
        val list = mutableListOf<String>()
        val anno = decl.annotations.find { it.shortName.asString() == "RemoveWrappers" }
        if (anno != null) {
            val classes = anno.arguments[0].value as List<KSType>
            for (cls in classes)
                list += cls.declaration.qualifiedName!!.asString()
        }
        return list
    }

    private val annoToHttpMethod = listOf(
        Delete::class.simpleName to "delete",
        Get::class.simpleName to "get",
        Head::class.simpleName to "head",
        Options::class.simpleName to "options",
        Patch::class.simpleName to "patch",
        Post::class.simpleName to "post",
        Put::class.simpleName to "put",
    )
    private fun getFnHttpMethodAndPath(fn: KSFunctionDeclaration): Pair<String, String>? {
        for ((cls, method) in annoToHttpMethod) {
            val anno = fn.annotations.find { it.shortName.getShortName() == cls }
            if (anno != null)
                return Pair(method, getFnAnnoPath(fn, anno))
        }
        return null
    }

    private fun getFnAnnoPath(fn: KSFunctionDeclaration, anno: KSAnnotation): String {
        val path = anno.arguments[0].value as String
        return path.ifBlank { fn.simpleName.asString() }
    }

    fun <T> collectIndexedValues(map: Map<String, T>, prefix: String): List<T> {
        val result = mutableListOf<T>()
        var index = 0
        while (true) {
            val key = "$prefix.$index"
            val value = map[key] ?: break
            result += value
            index++
        }
        return result
    }
}