package dev.jskrzypczak.photovault.server.dto

import kotlinx.serialization.Serializable

/**
 * RFC 7807 Problem Details response body.
 *
 * Null fields are omitted from JSON output (via [appJson] explicitNulls = false).
 * The [errors] map is only present for 400 "validation-failed" responses.
 */
@Serializable
data class ProblemDetails(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String? = null,
    val instance: String? = null,
    val errors: Map<String, List<String>>? = null,
)
