/*
 * Copyright 2021-2025, Darchest and contributors.
 * Licensed under the Apache License, Version 2.0
 */

package org.darchest.kastapi.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver

abstract class KastAPIGenerator {

    protected lateinit var resolver: Resolver
    protected lateinit var codeGenerator: CodeGenerator
    protected lateinit var logger: KSPLogger
    protected lateinit var options: Map<String, String>

    fun init(resolver: Resolver, codeGenerator: CodeGenerator, logger: KSPLogger, options: Map<String, String>) {
        this.resolver = resolver
        this.codeGenerator = codeGenerator
        this.logger = logger
        this.options = options
    }

    abstract fun generateFiles(packages: Set<PackageInfo>)

    protected fun constructorPathArguments(bundle: RoutesBundleInfo, endpoint: EndpointInfo): List<ArgumentInfo> {
        val paths = (bundle.pathChain() + endpoint.path).toTypedArray()
        val pathVars = PathParamAliases.variableNames(*paths)
        val ctor = bundle.cls.primaryConstructor ?: return emptyList()
        return ctor.parameters.mapNotNull { param ->
            if (param.isVararg) return@mapNotNull null
            val name = param.name?.asString() ?: return@mapNotNull null
            if (name !in pathVars) return@mapNotNull null
            val type = param.type.resolve()
            ArgumentInfo(
                name,
                type.declaration.qualifiedName!!.asString(),
                type.isMarkedNullable,
                ParameterSource.Path,
            ).apply {
                openApiName = PathParamAliases.displayNameFor(name, *paths)
            }
        }
    }
}