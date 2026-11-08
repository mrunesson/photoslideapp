package org.linuxalert.photo_slide_app.data

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Url

interface PhotoApi {
    @GET
    suspend fun history(@Url url: String): HistoryResponseDto

    @POST
    suspend fun rate(@Url url: String, @Body body: RatingRequestDto): Response<Unit>

    @POST
    suspend fun reject(@Url url: String): Response<Unit>

    @POST
    suspend fun reportFaultyTag(@Url url: String, @Body body: FaultyTagRequestDto): Response<Unit>
}

data class HistoryResponseDto(
    @SerializedName("photos") var photos: List<HistoryPhotoDto>,
)

data class HistoryPhotoDto(
    @SerializedName("path") var path: String,
    @SerializedName("album") var album: String? = null,
    @SerializedName("metadata") var metadata: PhotoMetadataDto? = null,
    @SerializedName("image_url") var imageUrl: String,
    @SerializedName("shown_at") var shownAt: String,
)

data class PhotoMetadataDto(
    @SerializedName("captured_at") var capturedAt: String? = null,
    @SerializedName("coordinates") var coordinates: CoordinatesDto? = null,
    @SerializedName("exif") var exif: Map<String, String>? = null,
)

data class CoordinatesDto(
    @SerializedName("latitude") var latitude: Double,
    @SerializedName("longitude") var longitude: Double,
)

data class RatingRequestDto(
    @SerializedName("rating") val rating: Int,
)

data class FaultyTagRequestDto(
    @SerializedName("tag") val tag: String,
)

data class ErrorResponseDto(
    @SerializedName("error") var error: String = "",
)