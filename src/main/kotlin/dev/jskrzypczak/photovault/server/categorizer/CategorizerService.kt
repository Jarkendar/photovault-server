package dev.jskrzypczak.photovault.server.categorizer

import dev.jskrzypczak.photovault.server.db.tables.Photos
import dev.jskrzypczak.photovault.server.dto.CategorizerStatusDto
import dev.jskrzypczak.photovault.server.errors.ApiException
import dev.jskrzypczak.photovault.server.photos.PhotoQuery
import dev.jskrzypczak.photovault.server.photos.PhotoService
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val STATUS_PENDING = "pending_categorization"

class CategorizerService(
    private val config: CategorizerConfig,
    private val photoService: PhotoService,
    private val runner: CommandRunner = DefaultCommandRunner,
) {
    private val log = LoggerFactory.getLogger(CategorizerService::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val running = AtomicBoolean(false)
    private val batchTotal = AtomicInteger(0)
    private val lastStartedAt = AtomicReference<Instant?>(null)
    private val lastFinishedAt = AtomicReference<Instant?>(null)
    private val lastExitCode = AtomicReference<Int?>(null)
    private val lastError = AtomicReference<String?>(null)

    fun triggerRun() {
        if (!config.isConfigured) throw ApiException(
            slug = "categorizer-not-configured",
            httpStatus = HttpStatusCode.Conflict,
            title = "Categorizer Not Configured",
            detail = "CATEGORIZER_CMD is not set — configure photovault.categorizer.command in application.yaml",
        )
        if (!running.compareAndSet(false, true)) return  // already running — no-op
        batchTotal.set(photoService.countPhotos(PhotoQuery(processingStatus = STATUS_PENDING)).toInt())
        lastStartedAt.set(Instant.now())
        lastError.set(null)
        scope.launch {
            try {
                val exit = runner.run(config.command, config.workdir)
                lastExitCode.set(exit)
                log.info("Categorizer finished with exit code $exit")
            } catch (e: Exception) {
                lastError.set(e.message)
                log.error("Categorizer failed to start", e)
            } finally {
                lastFinishedAt.set(Instant.now())
                running.set(false)
            }
        }
    }

    fun status(): CategorizerStatusDto = CategorizerStatusDto(
        running = running.get(),
        batchTotal = batchTotal.get(),
        pendingCount = photoService.countPhotos(PhotoQuery(processingStatus = STATUS_PENDING)),
        readyCount = photoService.countPhotos(PhotoQuery(processingStatus = "ready")),
        lastStartedAt = lastStartedAt.get()?.toString(),
        lastFinishedAt = lastFinishedAt.get()?.toString(),
        lastExitCode = lastExitCode.get(),
        lastError = lastError.get(),
    )

    fun recategorizePhoto(photoId: String) {
        val updated = transaction {
            Photos.update({ Photos.id eq photoId }) {
                it[processingStatus] = STATUS_PENDING
            }
        }
        if (updated == 0) throw ApiException(
            slug = "photo-not-found",
            httpStatus = HttpStatusCode.NotFound,
            title = "Photo Not Found",
            detail = "Photo '$photoId' does not exist",
        )
    }
}
