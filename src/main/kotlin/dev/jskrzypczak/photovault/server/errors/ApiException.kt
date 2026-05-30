package dev.jskrzypczak.photovault.server.errors

import dev.jskrzypczak.photovault.server.dto.ProblemDetails
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val ERROR_BASE = "https://photovault.local/errors/"

/** Builds the full RFC 7807 type URI for a given error slug. */
fun problemType(slug: String): String = ERROR_BASE + slug

/**
 * Shared [Json] instance used for all API responses.
 * - [explicitNulls] = false: omits null optional fields (detail, instance, errors)
 * - [encodeDefaults] = true: always encodes required fields
 */
val appJson = Json {
    explicitNulls = false
    encodeDefaults = true
}

/**
 * Domain exception that maps to an RFC 7807 Problem Details response.
 *
 * @param slug  Short error identifier appended to the error base URI, e.g. "not-found".
 * @param httpStatus  HTTP status code to return.
 * @param title  Short, human-readable summary of the error type.
 * @param detail  Optional explanation specific to this occurrence.
 * @param errors  Optional field-level validation messages; use with the "validation-failed" slug only.
 */
class ApiException(
    val slug: String,
    val httpStatus: HttpStatusCode,
    val title: String,
    val detail: String? = null,
    val errors: Map<String, List<String>>? = null,
) : RuntimeException(detail ?: title) {

    fun toProblem(instance: String?): ProblemDetails = ProblemDetails(
        type = problemType(slug),
        title = title,
        status = httpStatus.value,
        detail = detail,
        instance = instance,
        errors = errors,
    )
}

/**
 * Writes [problem] as an `application/problem+json` response.
 *
 * Uses [respondText] directly so that [ContentNegotiation] does not override
 * the content type to plain `application/json`.
 */
suspend fun ApplicationCall.respondProblem(problem: ProblemDetails) {
    respondText(
        text = appJson.encodeToString(problem),
        contentType = ContentType("application", "problem+json"),
        status = HttpStatusCode.fromValue(problem.status),
    )
}
