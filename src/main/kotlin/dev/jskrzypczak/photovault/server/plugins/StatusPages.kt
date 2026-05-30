package dev.jskrzypczak.photovault.server.plugins

import dev.jskrzypczak.photovault.server.dto.ProblemDetails
import dev.jskrzypczak.photovault.server.errors.ApiException
import dev.jskrzypczak.photovault.server.errors.problemType
import dev.jskrzypczak.photovault.server.errors.respondProblem
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.uri
import io.ktor.util.logging.KtorSimpleLogger

private val log = KtorSimpleLogger("dev.jskrzypczak.photovault.server.plugins.StatusPages")

/**
 * Installs [StatusPages] to map exceptions to RFC 7807 Problem Details responses.
 *
 * - [ApiException] → exact slug, status, and title from the exception.
 * - Any other [Throwable] → 500 `internal-error` (stack trace is logged, not leaked to the client).
 */
fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<ApiException> { call, e ->
            call.respondProblem(e.toProblem(call.request.uri))
        }
        exception<Throwable> { call, e ->
            log.error("Unhandled exception on ${call.request.uri}", e)
            call.respondProblem(
                ProblemDetails(
                    type = problemType("internal-error"),
                    title = "Internal Server Error",
                    status = HttpStatusCode.InternalServerError.value,
                    instance = call.request.uri,
                )
            )
        }
    }
}
