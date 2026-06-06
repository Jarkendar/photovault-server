package dev.jskrzypczak.photovault.server.dto

import kotlinx.serialization.Serializable

/**
 * A single detected face within a photo, as returned by the admin API.
 *
 * [bboxX]/[bboxY]/[bboxW]/[bboxH] are bounding-box coordinates in pixels of `medium.jpg`.
 * The web/admin tool fetches `GET /v1/photos/{photoId}/medium` and crops to the bbox.
 */
@Serializable
data class FaceDto(
    val faceId: String,
    val photoId: String,
    val bboxX: Int,
    val bboxY: Int,
    val bboxW: Int,
    val bboxH: Int,
    val detScore: Double,
)

/**
 * A face cluster as returned by `GET /v1/admin/face-clusters`.
 *
 * [representativePhotoId] and [representativeBbox*] come from the face with the highest
 * detection confidence — used to render a thumbnail preview of the cluster.
 * [tagId]/[categoryId] are null until the cluster is labelled via `POST …/label`.
 */
@Serializable
data class FaceClusterDto(
    val id: String,
    val faceCount: Int,
    val tagId: String? = null,
    val categoryId: String? = null,
    val representativePhotoId: String? = null,
    val representativeBboxX: Int? = null,
    val representativeBboxY: Int? = null,
    val representativeBboxW: Int? = null,
    val representativeBboxH: Int? = null,
)

/**
 * Request body for `POST /v1/admin/face-clusters/{id}/label`.
 *
 * Exactly one of [tagId] or [categoryId] must be provided.
 */
@Serializable
data class LabelClusterRequest(
    val tagId: String? = null,
    val categoryId: String? = null,
)

/** Request body for `POST /v1/admin/face-clusters/{id}/merge`. */
@Serializable
data class MergeClustersRequest(val sourceClusterIds: List<String>)

/** Request body for `POST /v1/admin/face-clusters/{id}/remove-faces`. */
@Serializable
data class RemoveFacesRequest(val faceIds: List<String>)

@Serializable data class FaceClusterListResponse(val items: List<FaceClusterDto>)
@Serializable data class FaceListResponse(val items: List<FaceDto>)
