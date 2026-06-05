package dev.jskrzypczak.photovault.server.routes

import dev.jskrzypczak.photovault.server.dto.CreateTagRequest
import dev.jskrzypczak.photovault.server.dto.TagListResponse
import dev.jskrzypczak.photovault.server.dto.UpdateTagRequest
import dev.jskrzypczak.photovault.server.errors.ApiException
import dev.jskrzypczak.photovault.server.metadata.TagService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.tagRoutes(tagService: TagService) {
    authenticate("auth-jwt") {
        route("/tags") {
            get {
                val usedOnly = call.parameters["usedOnly"]?.lowercase() == "true"
                val tags = tagService.listTags(usedOnly)
                call.respond(HttpStatusCode.OK, TagListResponse(tags))
            }

            post {
                val req = try {
                    call.receive<CreateTagRequest>()
                } catch (e: Exception) {
                    throw ApiException(
                        slug = "validation-failed",
                        httpStatus = HttpStatusCode.BadRequest,
                        title = "Validation Failed",
                        detail = "Request body must contain 'name'",
                    )
                }
                val tag = tagService.createTag(req.name)
                call.response.headers.append(HttpHeaders.Location, "/v1/tags/${tag.id}")
                call.respond(HttpStatusCode.Created, tag)
            }

            patch("/{id}") {
                val id = call.parameters["id"]!!
                val req = try {
                    call.receive<UpdateTagRequest>()
                } catch (e: Exception) {
                    throw ApiException(
                        slug = "validation-failed",
                        httpStatus = HttpStatusCode.BadRequest,
                        title = "Validation Failed",
                        detail = "Request body must be a valid JSON object",
                    )
                }
                val tag = tagService.updateTag(id, req.name, req.autoEnabled, req.rolledOut)
                call.respond(HttpStatusCode.OK, tag)
            }

            delete("/{id}") {
                val id = call.parameters["id"]!!
                tagService.deleteTag(id)
                call.respond(HttpStatusCode.NoContent, "")
            }
        }
    }
}
