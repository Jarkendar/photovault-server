package dev.jskrzypczak.photovault.server.routes

import dev.jskrzypczak.photovault.server.dto.CategoryListResponse
import dev.jskrzypczak.photovault.server.dto.CreateCategoryRequest
import dev.jskrzypczak.photovault.server.dto.UpdateCategoryRequest
import dev.jskrzypczak.photovault.server.errors.ApiException
import dev.jskrzypczak.photovault.server.metadata.CategoryService
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

fun Route.categoryRoutes(categoryService: CategoryService) {
    authenticate("auth-jwt") {
        route("/categories") {
            get {
                val usedOnly = call.parameters["usedOnly"]?.lowercase() == "true"
                val cats = categoryService.listCategories(usedOnly)
                call.respond(HttpStatusCode.OK, CategoryListResponse(cats))
            }

            post {
                val req = try {
                    call.receive<CreateCategoryRequest>()
                } catch (e: Exception) {
                    throw ApiException(
                        slug = "validation-failed",
                        httpStatus = HttpStatusCode.BadRequest,
                        title = "Validation Failed",
                        detail = "Request body must contain 'name' and 'colorHex'",
                    )
                }
                val cat = categoryService.createCategory(req.name, req.colorHex)
                call.response.headers.append(HttpHeaders.Location, "/v1/categories/${cat.id}")
                call.respond(HttpStatusCode.Created, cat)
            }

            patch("/{id}") {
                val id = call.parameters["id"]!!
                val req = try {
                    call.receive<UpdateCategoryRequest>()
                } catch (e: Exception) {
                    throw ApiException(
                        slug = "validation-failed",
                        httpStatus = HttpStatusCode.BadRequest,
                        title = "Validation Failed",
                        detail = "Request body must be a valid JSON object",
                    )
                }
                val cat = categoryService.updateCategory(id, req.name, req.colorHex, req.autoEnabled, req.rolledOut)
                call.respond(HttpStatusCode.OK, cat)
            }

            delete("/{id}") {
                val id = call.parameters["id"]!!
                categoryService.deleteCategory(id)
                call.respond(HttpStatusCode.NoContent, "")
            }
        }
    }
}
