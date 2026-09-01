package com.sanka1610.reprodroid.runner

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.charset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal object StrictV2Json {
    private const val MAX_BODY_BYTES = 256 * 1024
    private const val MAX_DEPTH = 32
    private const val MAX_STRING_BYTES = 16 * 1024
    private val json = Json { explicitNulls = true; ignoreUnknownKeys = false }

    suspend fun receive(call: ApplicationCall): JsonObject {
        val type = call.request.contentType()
        if (type.withoutParameters() != ContentType.Application.Json) {
            throw ApiException.badRequest("INVALID_CONTENT_TYPE", "Content-Type must be application/json with UTF-8 encoding.")
        }
        val charset = type.charset()
        if (charset != null && charset != StandardCharsets.UTF_8) {
            throw ApiException.badRequest("INVALID_CONTENT_TYPE", "Content-Type must be application/json with UTF-8 encoding.")
        }
        call.request.headers[HttpHeaders.ContentLength]?.let { raw ->
            val length = raw.toLongOrNull()
                ?: throw ApiException.badRequest("INVALID_CONTENT_LENGTH", "Content-Length is invalid.")
            if (length < 0 || length > MAX_BODY_BYTES) requestTooLarge()
        }
        val channel = call.receiveChannel()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (!channel.isClosedForRead) {
            val read = channel.readAvailable(buffer)
            if (read == -1) break
            if (read == 0) continue
            if (output.size() + read > MAX_BODY_BYTES) requestTooLarge()
            output.write(buffer, 0, read)
        }
        val bytes = output.toByteArray()
        if (bytes.isEmpty()) throw ApiException.badRequest("INVALID_REQUEST", "The request body must be a JSON object.")
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            throw ApiException.badRequest("INVALID_JSON", "A UTF-8 BOM is not allowed.")
        }
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            throw ApiException.badRequest("INVALID_JSON", "The request body is not valid UTF-8 JSON.")
        }
        try {
            JsonAuditor(text).audit()
            return json.parseToJsonElement(text).jsonObject
        } catch (failure: ApiException) {
            throw failure
        } catch (_: SerializationException) {
            throw ApiException.badRequest("INVALID_JSON", "The request body is not valid JSON.")
        } catch (_: IllegalArgumentException) {
            throw ApiException.badRequest("INVALID_JSON", "The request body is not valid JSON.")
        }
    }

    private fun requestTooLarge(): Nothing = throw ApiException(
        io.ktor.http.HttpStatusCode.PayloadTooLarge,
        "REQUEST_TOO_LARGE",
        "The request body exceeds 256 KiB.",
    )

    private class JsonAuditor(private val source: String) {
        private var index = 0

        fun audit() {
            skipWhitespace()
            parseValue(depth = 1)
            skipWhitespace()
            if (index != source.length) invalid()
        }

        private fun parseValue(depth: Int) {
            if (depth > MAX_DEPTH) invalid("JSON nesting exceeds 32 levels.")
            skipWhitespace()
            when (peek()) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> parseString()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                '-', in '0'..'9' -> parseNumber()
                else -> invalid()
            }
        }

        private fun parseObject(depth: Int) {
            expect('{')
            skipWhitespace()
            if (consume('}')) return
            val keys = mutableSetOf<String>()
            while (true) {
                skipWhitespace()
                if (peek() != '"') invalid()
                val key = parseString()
                if (!keys.add(key)) invalid("Duplicate JSON object keys are not allowed.")
                skipWhitespace()
                expect(':')
                parseValue(depth + 1)
                skipWhitespace()
                if (consume('}')) return
                expect(',')
            }
        }

        private fun parseArray(depth: Int) {
            expect('[')
            skipWhitespace()
            if (consume(']')) return
            while (true) {
                parseValue(depth + 1)
                skipWhitespace()
                if (consume(']')) return
                expect(',')
            }
        }

        private fun parseString(): String {
            expect('"')
            val result = StringBuilder()
            while (index < source.length) {
                val character = source[index++]
                when {
                    character == '"' -> {
                        if (result.toString().toByteArray(StandardCharsets.UTF_8).size > MAX_STRING_BYTES) {
                            invalid("A JSON string exceeds 16 KiB.")
                        }
                        return result.toString()
                    }
                    character == '\\' -> parseEscape(result)
                    character.code < 0x20 -> invalid()
                    character.isHighSurrogate() -> {
                        if (index >= source.length || !source[index].isLowSurrogate()) invalid()
                        result.append(character).append(source[index++])
                    }
                    character.isLowSurrogate() -> invalid()
                    else -> result.append(character)
                }
            }
            invalid()
        }

        private fun parseEscape(result: StringBuilder) {
            if (index >= source.length) invalid()
            when (val escaped = source[index++]) {
                '"', '\\', '/' -> result.append(escaped)
                'b' -> result.append('\b')
                'f' -> result.append('\u000c')
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'u' -> {
                    val first = parseHexCodeUnit()
                    when {
                        first.isHighSurrogate() -> {
                            if (index + 1 >= source.length || source[index] != '\\' || source[index + 1] != 'u') invalid()
                            index += 2
                            val second = parseHexCodeUnit()
                            if (!second.isLowSurrogate()) invalid()
                            result.append(first).append(second)
                        }
                        first.isLowSurrogate() -> invalid()
                        else -> result.append(first)
                    }
                }
                else -> invalid()
            }
        }

        private fun parseHexCodeUnit(): Char {
            if (index + 4 > source.length) invalid()
            val raw = source.substring(index, index + 4)
            if (raw.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) invalid()
            index += 4
            return raw.toInt(16).toChar()
        }

        private fun parseNumber() {
            consume('-')
            when (peek()) {
                '0' -> {
                    index++
                    if (isDigit(peekOrNull())) invalid()
                }
                in '1'..'9' -> while (isDigit(peekOrNull())) index++
                else -> invalid()
            }
            if (consume('.')) {
                if (!isDigit(peekOrNull())) invalid()
                while (isDigit(peekOrNull())) index++
            }
            if (consume('e') || consume('E')) {
                consume('+') || consume('-')
                if (!isDigit(peekOrNull())) invalid()
                while (isDigit(peekOrNull())) index++
            }
        }

        private fun literal(value: String) {
            if (!source.startsWith(value, index)) invalid()
            index += value.length
        }

        private fun skipWhitespace() {
            while (peekOrNull() in setOf(' ', '\n', '\r', '\t')) index++
        }

        private fun expect(expected: Char) {
            if (!consume(expected)) invalid()
        }

        private fun consume(expected: Char): Boolean {
            if (peekOrNull() != expected) return false
            index++
            return true
        }

        private fun peek(): Char = peekOrNull() ?: invalid()
        private fun peekOrNull(): Char? = source.getOrNull(index)
        private fun isDigit(value: Char?): Boolean = value != null && value in '0'..'9'

        private fun invalid(message: String = "The request body is not valid JSON."): Nothing =
            throw ApiException.badRequest("INVALID_JSON", message)
    }
}
