package org.linuxalert.photo_slide_app.domain

data class Photo(
    val path: String,
    val album: String?,
    val metadata: PhotoMetadata,
    val imageUrl: String,
    val shownAt: String,
) {
    val selectionKey: String
        get() = "$shownAt\u0000$imageUrl"
}

data class PhotoMetadata(
    val capturedAt: String?,
    val latitude: Double?,
    val longitude: Double?,
    val exif: Map<String, String>,
)