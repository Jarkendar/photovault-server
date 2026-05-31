package dev.jskrzypczak.photovault.server.plugins

import dev.jskrzypczak.photovault.server.auth.AuthService
import dev.jskrzypczak.photovault.server.metadata.CategoryService
import dev.jskrzypczak.photovault.server.metadata.LabelService
import dev.jskrzypczak.photovault.server.metadata.TagService
import dev.jskrzypczak.photovault.server.photos.PhotoService
import dev.jskrzypczak.photovault.server.routes.authRoutes
import dev.jskrzypczak.photovault.server.routes.categoryRoutes
import dev.jskrzypczak.photovault.server.routes.healthRoutes
import dev.jskrzypczak.photovault.server.routes.labelRoutes
import dev.jskrzypczak.photovault.server.routes.photoRoutes
import dev.jskrzypczak.photovault.server.routes.tagRoutes
import dev.jskrzypczak.photovault.server.uploads.UploadService
import dev.jskrzypczak.photovault.server.uploads.uploadRoutes
import io.ktor.server.application.Application
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/** Registers all API routes under the `/v1` prefix. */
fun Application.configureRouting(
    version: String,
    authService: AuthService,
    photoService: PhotoService,
    tagService: TagService,
    categoryService: CategoryService,
    labelService: LabelService,
    uploadService: UploadService,
) {
    routing {
        route("/v1") {
            healthRoutes(version)
            authRoutes(authService)
            photoRoutes(photoService)
            tagRoutes(tagService)
            categoryRoutes(categoryService)
            labelRoutes(labelService)
            uploadRoutes(uploadService)
        }
    }
}
