package dev.jskrzypczak.photovault.server.dto

import kotlinx.serialization.Serializable

/**
 * Geographic location embedded in [PhotoDto].
 *
 * Present only when the photo has GPS coordinates; null otherwise.
 */
@Serializable
data class LocationDto(
    val latitude: Double,
    val longitude: Double,
    val placeName: String? = null,
)

/**
 * Minimal uploader info embedded in [PhotoDto].
 *
 * Returns only [id] and [displayName] — not [username] — to avoid leaking
 * login names in contexts where the caller isn't an admin.
 */
@Serializable
data class PhotoUploaderDto(
    val id: String,
    val displayName: String,
)

/**
 * Full photo resource as returned by `GET /v1/photos` and `GET /v1/photos/{id}`.
 *
 * Binary-content URLs ([thumbnailUrl], [mediumUrl], [originalUrl]) are relative paths;
 * the client resolves them against its configured base URL.
 *
 * [capturedAt], [camera], [location] are optional — present only when available.
 */
@Serializable
data class PhotoDto(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val capturedAt: String? = null,
    val uploadedAt: String,
    val camera: String? = null,
    val location: LocationDto? = null,
    val uploadedBy: PhotoUploaderDto,
    val tags: List<TagDto>,
    val categories: List<CategoryDto>,
    val labels: List<LabelDto>,
    val isFavorite: Boolean,
    val processingStatus: String,
    val thumbnailUrl: String,
    val mediumUrl: String,
    val originalUrl: String,
)

/**
 * Paginated response for `GET /v1/photos`.
 *
 * [nextCursor] is an opaque base64-encoded string; null on the last page.
 * [hasMore] is false when [nextCursor] is null.
 */
@Serializable
data class PhotoPage(
    val items: List<PhotoDto>,
    val nextCursor: String? = null,
    val hasMore: Boolean,
)
