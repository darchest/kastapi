/*
 * Copyright 2021-2025, Darchest and contributors.
 * Licensed under the Apache License, Version 2.0
 */

package org.darchest.kastapi.processor

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import io.swagger.v3.core.util.Yaml
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.*
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import java.util.*

typealias SchemaConverter = (String) -> Schema<*>

class OpenAPIGenerator(
    private val resolver: Resolver,
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>
) {
    fun generateFiles(packages: Set<PackageInfo>) {
        val file = codeGenerator.createNewFileByPath(Dependencies(false), "openapi", "yaml")

        val api = OpenAPI()
            .info(Info().title(getOpt("title") ?: "KastAPI API").version(getOpt("version") ?: "1.0.0"))

        for (pkg in packages) {
            val pkgPath = getOpt("package.${pkg.name}.path") ?: ""
            val securityType = getOpt("package.${pkg.name}.security")
            val securityReq = SecurityRequirement().addList("bearerAuth")
            if (securityType == "bearer") {
                val bearerScheme = SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                api.components(Components().addSecuritySchemes("bearerAuth", bearerScheme))
            }

            for (bundle in pkg.bundles) {
                for (endpoint in bundle.endpoints) {

                    val responses = resolveResponses(endpoint)

                    val method = resolveHttpMethod(endpoint)
                    val operation = Operation()
                        .responses(responses)

                    for (arg in endpoint.arguments) {
                        if (arg.source == ParameterSource.Path && arg.type != "io.ktor.server.application.ApplicationCall") {
                            operation.addParametersItem(
                                Parameter().`in`("path")
                                    .name(arg.name)
                                    .required(arg.canBeNull.not())
                            )
                        } else if (arg.source == ParameterSource.Query) {
                            operation.addParametersItem(
                                Parameter().`in`("query")
                                    .name(arg.name)
                                    .required(arg.canBeNull.not())
                            )
                        }
                    }

                    var requestBody: RequestBody? = null

                    if (endpoint.arguments.find { it.source == ParameterSource.Form } != null) {
                        val formSchema = ObjectSchema()

                        for (arg in endpoint.arguments) {
                            if (arg.source != ParameterSource.Form)
                                continue

                            formSchema.addProperty(arg.name, resolver.buildSchemaByName(arg.type))
                        }

                        requestBody = RequestBody().content(
                            Content().addMediaType(
                                "application/x-www-form-urlencoded",
                                MediaType().schema(formSchema)
                            )
                        )
                    }

                    if (endpoint.arguments.find { it.source == ParameterSource.Multipart } != null) {
                        val formSchema = ObjectSchema()

                        for (arg in endpoint.arguments) {
                            if (arg.source != ParameterSource.Multipart)
                                continue

                            formSchema.addProperty(arg.name, resolver.buildSchemaByName(arg.type))
                        }

                        requestBody = RequestBody().content(
                            Content().addMediaType(
                                "multipart/form-data",
                                MediaType().schema(formSchema)
                            )
                        )
                    }

                    if (endpoint.arguments.find { it.source == ParameterSource.Body } != null) {
                        var formSchema: Schema<*>? = null

                        for (arg in endpoint.arguments) {
                            if (arg.source != ParameterSource.Body)
                                continue

                            formSchema = resolver.buildSchemaByName(arg.type)
                        }

                        requestBody = RequestBody().content(
                            Content().addMediaType(
                                "application/json",
                                MediaType().schema(formSchema)
                            )
                        )
                    }

                    requestBody?.let { operation.requestBody(it) }
                    securityType?.let { operation.security = listOf(securityReq) }

                    val pathItem = PathItem()
                    pathItem.operation(method, operation)

                    val localUrl = joinUrlParts(pkgPath, bundle.path, endpoint.path)
                    api.path("/$localUrl", pathItem)
                }
            }
        }

        Yaml.mapper().writeValue(file, api)
    }

    private fun resolveResponses(endpointInfo: EndpointInfo): ApiResponses {


        val code = if (endpointInfo.pairWithCode) "default" else
            (endpointInfo.codeOnSuccess ?: "200").toString()

        val response = resolveResponse(endpointInfo.returnType)

        return ApiResponses()
            .addApiResponse(code, response)
    }


    private val responsesCache = mutableMapOf<String, ApiResponse>()
    private fun resolveResponse(cls: String): ApiResponse {
        responsesCache[cls]?.let { return it }

        if (cls != "kotlin.String") {
            val openApiSchema = resolver.buildSchemaByName(cls)
            return ApiResponse()
                .content(
                    Content().addMediaType(
                        "application/json",
                        MediaType().schema(openApiSchema)
                    )
                )
        } else {
            return ApiResponse()
                .content(
                    Content().addMediaType(
                        "text/plain",
                        MediaType().schema(StringSchema())
                    )
                )
        }
    }

    private val schemaConverters = mutableMapOf<String, SchemaConverter>(

    )

    private fun getOpt(key: String): String? = options["org.darchest.kastapi.openapi.$key"]

    private fun joinUrlParts(vararg parts: String): String =
        parts.filterNot(String::isEmpty).joinToString("/") { it.trim('/') }

    private fun resolveHttpMethod(endpointInfo: EndpointInfo): PathItem.HttpMethod {
        return PathItem.HttpMethod.valueOf(endpointInfo.method!!.uppercase(Locale.getDefault()))
    }

    private fun Resolver.buildSchemaByName(className: String): Schema<*>? {
        val decl = getClassDeclarationByName(className) ?: return null
        return buildSchemaForClass(decl, mutableMapOf())
    }

    private fun Resolver.buildSchemaForClass(
        decl: KSClassDeclaration,
        cache: MutableMap<String, ObjectSchema>
    ): Schema<*> {
        val fqName = decl.qualifiedName?.asString() ?: return ObjectSchema()
        cache[fqName]?.let { return it }

        when (fqName) {
            "kotlin.String" -> return StringSchema()
            in setOf("kotlin.Int", "kotlin.Long") -> return IntegerSchema()
            in setOf("kotlin.Float", "kotlin.Double") -> return NumberSchema()
            "kotlin.Boolean" -> return BooleanSchema()
            "org.darchest.kastapi.ktor.utility.InMemoryFile" -> return Schema<Any>().type("string").format("binary")
        }

        val schema = ObjectSchema()
        cache[fqName] = schema

        val required = mutableListOf<String>()

        decl.getAllProperties().forEach { prop ->
            val name = prop.simpleName.asString()
            val type = prop.type.resolve()
            val typeDecl = type.declaration
            val typeName = typeDecl.qualifiedName?.asString()

            val propSchema: Schema<*> = when {
                // string
                typeName == "kotlin.String" -> StringSchema()
                // integer
                typeName in setOf("kotlin.Int", "kotlin.Long") -> IntegerSchema()
                // number
                typeName in setOf("kotlin.Float", "kotlin.Double") -> NumberSchema()
                // boolean
                typeName == "kotlin.Boolean" -> BooleanSchema()

                // list or set
                typeName == "kotlin.collections.List" || typeName == "kotlin.collections.Set" -> {
                    val argType = type.arguments.firstOrNull()?.type?.resolve()
                    val itemsSchema = if (argType != null) {
                        resolveSchemaForKSType(argType, cache)
                    } else StringSchema()
                    ArraySchema().items(itemsSchema)
                }

                // map
                typeName == "kotlin.collections.Map" -> {
                    val keyType = type.arguments.getOrNull(0)?.type?.resolve()
                    val valueType = type.arguments.getOrNull(1)?.type?.resolve()
                    val additionalProps = if (valueType != null) {
                        resolveSchemaForKSType(valueType, cache)
                    } else StringSchema()
                    ObjectSchema()
                        .additionalProperties(additionalProps)
                        .description("Map<${keyType?.declaration?.simpleName?.asString()}, ${valueType?.declaration?.simpleName?.asString()}>")
                }

                // nested object
                typeDecl is KSClassDeclaration -> buildSchemaForClass(typeDecl, cache)

                // fallback
                else -> StringSchema()
            }

            schema.addProperty(name, propSchema)
            if (!type.isMarkedNullable) required += name
        }

        // наследование
        decl.superTypes.forEach { superType ->
            val superDecl = superType.resolve().declaration
            if (superDecl is KSClassDeclaration && superDecl.classKind == ClassKind.CLASS) {
                val parentSchema = buildSchemaForClass(superDecl, cache)
                parentSchema.properties?.forEach { (k, v) ->
                    if (!schema.properties.containsKey(k)) schema.addProperty(k, v)
                }
                parentSchema.required?.let { required.addAll(it) }
            }
        }

        if (required.isNotEmpty()) schema.required(required.distinct())
        return schema
    }

    private fun Resolver.resolveSchemaForKSType(type: KSType, cache: MutableMap<String, ObjectSchema>): Schema<*> {
        val decl = type.declaration
        val typeName = decl.qualifiedName?.asString()

        return when (typeName) {
            "kotlin.String" -> StringSchema()
            "kotlin.Int", "kotlin.Long" -> IntegerSchema()
            "kotlin.Float", "kotlin.Double" -> NumberSchema()
            "kotlin.Boolean" -> BooleanSchema()
            "kotlin.collections.List", "kotlin.collections.Set" -> {
                val arg = type.arguments.firstOrNull()?.type?.resolve()
                val itemSchema = if (arg != null) resolveSchemaForKSType(arg, cache) else StringSchema()
                ArraySchema().items(itemSchema)
            }
            "kotlin.collections.Map" -> {
                val valueArg = type.arguments.getOrNull(1)?.type?.resolve()
                val valueSchema = if (valueArg != null) resolveSchemaForKSType(valueArg, cache) else StringSchema()
                ObjectSchema().additionalProperties(valueSchema)
            }
            else -> if (decl is KSClassDeclaration) buildSchemaForClass(decl, cache) else StringSchema()
        }
    }
}