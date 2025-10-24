package org.darchest.kastapi.processor

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import org.darchest.kastapi.annotations.Get
import org.darchest.kastapi.annotations.Post
import java.io.OutputStreamWriter

class KastApiProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
): SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        var functions = resolver.getSymbolsWithAnnotation(Get::class.qualifiedName!!)
            .filterIsInstance<KSFunctionDeclaration>()

        functions += (resolver.getSymbolsWithAnnotation(Post::class.qualifiedName!!)
            .filterIsInstance<KSFunctionDeclaration>())

        if (functions.none()) return emptyList()

        val packageName = "org.darchest.kastapi.generated"
        val className = "GeneratedRoutes"

        val files = functions.map { fn -> fn.containingFile!! }.toList().toTypedArray()

        val file = codeGenerator.createNewFile(
            Dependencies(false, *files),
            packageName,
            className
        )

        OutputStreamWriter(file, Charsets.UTF_8).use { writer ->
            writer.write("package $packageName\n\n")
            writer.write("import io.ktor.server.application.*\n")
            writer.write("import io.ktor.server.routing.*\n")
            writer.write("import io.ktor.server.response.*\n")
            writer.write("import ${functions.first().parentDeclaration!!.packageName.asString()}.*\n\n")

            writer.write("fun Route.generatedRoutes() {\n")

            for (func in functions) {
                val parent = func.parentDeclaration as KSClassDeclaration
                val parentName = parent.simpleName.asString()
                val funcName = func.simpleName.asString()

                val getAnn = func.annotations.find { it.shortName.asString() == "Get" }
                val postAnn = func.annotations.find { it.shortName.asString() == "Post" }

                val path = when {
                    getAnn != null -> getAnn.arguments.first().value as String
                    postAnn != null -> postAnn.arguments.first().value as String
                    else -> "/"
                }

                val httpMethod = if (getAnn != null) "get" else "post"

                writer.write("    $httpMethod(\"$path\") {\n")
                writer.write("        val api = $parentName()\n")

                // параметры
                val params = func.parameters.mapIndexed { idx, p ->
                    val name = p.name?.asString() ?: "p$idx"
                    val type = p.type.resolve()
                    val typeDecl = type.declaration.qualifiedName?.asString()
                        ?: type.declaration.simpleName.asString()

                    if (postAnn != null) {
                        // тело запроса
                        "call.receive<$typeDecl>()"
                    } else {
                        // query-параметры
                        "call.parameters[\"$name\"]?.let { it.to${typeDecl.replaceFirstChar { it.uppercase() }}() } ?: error(\"Missing param: $name\")"
                    }
                }

                val callExpr = if (params.isNotEmpty()) {
                    "api.$funcName(${params.joinToString(", ")})"
                } else {
                    "api.$funcName()"
                }

                writer.write("        call.respond($callExpr)\n")
                writer.write("    }\n")
            }

            writer.write("}\n")
        }

        return emptyList()
    }
}