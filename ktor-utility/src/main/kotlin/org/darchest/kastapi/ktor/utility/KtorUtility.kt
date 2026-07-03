/*
 * Copyright 2021-2025, Darchest and contributors.
 * Licensed under the Apache License, Version 2.0
 */

package org.darchest.kastapi.ktor.utility

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import java.nio.charset.StandardCharsets
import java.util.*
import kotlin.reflect.KClass

typealias FromStringConverter = (String) -> Any

typealias FromFileItemConverter = (PartData.FileItem) -> Any

typealias Responder = suspend (ApplicationCall, Int, Any) -> Unit

class InMemoryFile(val name: String, val contentType: ContentType?, val data: ByteArray)

interface Wrapper {
    suspend fun <T> wrap(block: suspend () -> T): T
}

object KtorUtility {

    private val stringConverters = mutableMapOf<KClass<*>, FromStringConverter>()
    private val fileItemConverters = mutableMapOf<KClass<*>, FromFileItemConverter>()
    private val responders = mutableMapOf<KClass<*>, Responder>()

    val respondersRead: Map<KClass<*>, Responder>
        get() = responders

    init {
        registerStringConverter(String::class) { it }
        registerStringConverter(Long::class) { it.toLong() }
        registerStringConverter(Int::class) { it.toInt() }
        registerStringConverter(Double::class) { it.toDouble() }
        registerStringConverter(Float::class) { it.toFloat() }
        registerStringConverter(UUID::class) { UUID.fromString(it) }

        registerFileItemConverter(InMemoryFile::class) { InMemoryFile(
            it.originalFileName ?: "unnamed",
            it.contentType,
            it.streamProvider().readBytes()
        ) }

        registerResponder(InMemoryFile::class) { call, code, data ->
            val file = data as InMemoryFile

            val fallback = asciiFallbackFileName(file.name)
            val encoded = rfc5987Encode(file.name)
            call.response.header(
                HttpHeaders.ContentDisposition,
                "attachment; filename=\"$fallback\"; filename*=UTF-8''$encoded"
            )

            call.respondBytes(file.data, status = HttpStatusCode.fromValue(code), contentType = file.contentType)
        }
    }

    fun <T : Any> getOptionalPathParameter(call: ApplicationCall, paramName: String, type: KClass<T>): T? {
        val value = call.parameters[paramName] ?: return null
        return convertFromString(value, type)
    }

    fun <T : Any> getRequiredPathParameter(call: ApplicationCall, paramName: String, type: KClass<T>): T {
        val value = call.parameters[paramName] ?: throw IllegalArgumentException("Missing required path parameter '${paramName}'")
        return convertFromString(value, type)
    }

    fun <T : Any> getOptionalQueryParameter(call: ApplicationCall, paramName: String, type: KClass<T>): T? {
        val value = call.request.queryParameters[paramName] ?: return null
        return convertFromString(value, type)
    }

    fun <T : Any> getRequiredQueryParameter(call: ApplicationCall, paramName: String, type: KClass<T>): T {
        val value = call.request.queryParameters[paramName] ?: throw IllegalArgumentException("Missing required query parameter '${paramName}'")
        return convertFromString(value, type)
    }

    fun <T : Any> getOptionalFormParameter(params: Parameters, paramName: String, type: KClass<T>): T? {
        val value = params[paramName] ?: return null
        return convertFromString(value, type)
    }

    fun <T : Any> getRequiredFormParameter(params: Parameters, paramName: String, type: KClass<T>): T {
        val value = params[paramName] ?: throw IllegalArgumentException("Missing required form parameter '${paramName}'")
        return convertFromString(value, type)
    }

    fun <T : Any> getMultipartParameter(part: PartData, type: KClass<T>): T? {
        if (part is PartData.FormItem) {
            val value = part.value
            return convertFromString(value, type)
        } else if (part is PartData.FileItem) {
            return convertFromFileItem(part, type)
        }
        return null
    }

    suspend inline fun <reified T: Any> respond(call: ApplicationCall, code: Int, data: T, type: KClass<T>) {
        val responder = respondersRead[type] ?: return call.respond(HttpStatusCode.fromValue(code), data)
        responder(call, code, data)
    }

    fun registerStringConverter(type: KClass<*>, fn: FromStringConverter) {
        stringConverters[type] = fn
    }

    fun registerFileItemConverter(type: KClass<*>, fn: FromFileItemConverter) {
        fileItemConverters[type] = fn
    }

    fun registerResponder(type: KClass<*>, fn: Responder) {
        responders[type] = fn
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T: Any> convertFromString(value: String, type: KClass<T>): T {
        val converter = stringConverters[type] ?: throw RuntimeException("Converter from String to $type is undefined")
        return converter(value) as T
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T: Any> convertFromFileItem(value: PartData.FileItem, type: KClass<T>): T {
        val converter = fileItemConverters[type] ?: throw RuntimeException("Converter from FileItem to $type is undefined")
        return converter(value) as T
    }
}

private fun asciiFallbackFileName(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "download"

    val sb = StringBuilder(trimmed.length)
    for (ch in trimmed) {
        val isAscii = ch.code in 0x20..0x7E
        val isSafe = isAscii && ch != '"' && ch != '\\'
        sb.append(if (isSafe) ch else '_')
    }

    val sanitized = sb.toString().trim().ifEmpty { "download" }
    return sanitized
}

private fun rfc5987Encode(value: String): String {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    val sb = StringBuilder(bytes.size * 3)
    for (b in bytes) {
        val i = b.toInt() and 0xFF
        val ch = i.toChar()
        val isAlphaNum = (ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9')
        val isAttrChar = isAlphaNum || ch == '!' || ch == '#' || ch == '$' || ch == '&' || ch == '+' ||
            ch == '-' || ch == '.' || ch == '^' || ch == '_' || ch == '`' || ch == '|' || ch == '~'
        if (isAttrChar) {
            sb.append(ch)
        } else {
            sb.append('%')
            sb.append("0123456789ABCDEF"[i ushr 4])
            sb.append("0123456789ABCDEF"[i and 0x0F])
        }
    }
    return sb.toString()
}