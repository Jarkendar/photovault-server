package dev.jskrzypczak.photovault.server.dto

import kotlinx.serialization.Serializable

@Serializable
data class CategorizerStatusDto(
    /** Whether a categorizer process is currently running (started via this server). */
    val running: Boolean,
    /** Snapshot of pending_categorization count taken at run start (denominator for progress %). */
    val batchTotal: Int,
    /** Current number of photos with processingStatus = pending_categorization. */
    val pendingCount: Long,
    /** Current number of photos with processingStatus = ready. */
    val readyCount: Long,
    /** ISO-8601 timestamp of the last run start, or null if never triggered. */
    val lastStartedAt: String?,
    /** ISO-8601 timestamp of the last run finish, or null if never finished. */
    val lastFinishedAt: String?,
    /** Exit code of the last finished run, or null if never finished. */
    val lastExitCode: Int?,
    /** Last error message if the process failed to start, or null. */
    val lastError: String?,
)
