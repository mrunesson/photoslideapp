package org.linuxalert.photo_slide_app.data

import com.google.gson.Gson
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.linuxalert.photo_slide_app.domain.Photo
import org.linuxalert.photo_slide_app.domain.PhotoMetadata
import retrofit2.Response

enum class PhotoConnection {
    CONNECTING,
    CONNECTED,
}

data class PhotoFeed(
    val photos: List<Photo> = emptyList(),
    val connection: PhotoConnection = PhotoConnection.CONNECTING,
    val error: String? = null,
)

@Singleton
class PhotoRepository @Inject constructor(
    private val api: PhotoApi,
    private val eventSource: PhotoEventSource,
    private val gson: Gson,
) {
    fun observePhotos(serverAddress: String): Flow<PhotoFeed> = flow {
        val baseUrl = serverAddress.toHttpUrl()
        val eventsUrl = baseUrl.newBuilder()
            .addPathSegments("api/photos/events")
            .build()
            .toString()
        var photos = emptyList<Photo>()
        var retryDelay = INITIAL_RETRY_DELAY_MS

        while (currentCoroutineContext().isActive) {
            emit(PhotoFeed(photos = photos, connection = PhotoConnection.CONNECTING))
            var connectedAtNanos: Long? = null
            try {
                var connected = false
                eventSource.events(eventsUrl).buffer(kotlinx.coroutines.channels.Channel.UNLIMITED).collect { event ->
                    when (event) {
                        PhotoStreamMessage.Connected -> {
                            connected = true
                            if (connectedAtNanos == null) connectedAtNanos = System.nanoTime()
                            emit(PhotoFeed(photos, PhotoConnection.CONNECTED))
                            try {
                                photos = mergePhotos(
                                    api.history(endpointUrl(baseUrl, "api/photos/history")).photos.map {
                                        it.toDomain(baseUrl)
                                    },
                                    emptyList(),
                                )
                                emit(PhotoFeed(photos, PhotoConnection.CONNECTED))
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                emit(PhotoFeed(photos, PhotoConnection.CONNECTED, error.userMessage()))
                            }
                        }

                        is PhotoStreamMessage.PhotoSelected -> {
                            photos = mergePhotos(photos, listOf(event.photo.toDomain(baseUrl)))
                            emit(PhotoFeed(photos, if (connected) PhotoConnection.CONNECTED else PhotoConnection.CONNECTING))
                        }
                    }
                }
                throw IOException("The photo event stream closed")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (connectedAtNanos?.let { System.nanoTime() - it >= STABLE_CONNECTION_NANOS } == true) {
                    retryDelay = INITIAL_RETRY_DELAY_MS
                }
                emit(PhotoFeed(photos, PhotoConnection.CONNECTING, error.userMessage()))
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
            }
        }
    }

    suspend fun rate(photo: Photo, rating: Int) {
        require(rating in 1..5) { "Rating must be between 1 and 5" }
        ensureSuccess(api.rate(actionUrl(photo, "rating"), RatingRequestDto(rating)))
    }

    suspend fun reject(photo: Photo) {
        ensureSuccess(api.reject(actionUrl(photo, "reject")))
    }

    suspend fun reportFaultyTag(photo: Photo, tag: String) {
        val cleanTag = tag.trim()
        require(cleanTag.isNotEmpty()) { "Enter an EXIF tag name" }
        ensureSuccess(api.reportFaultyTag(actionUrl(photo, "faulty-tag"), FaultyTagRequestDto(cleanTag)))
    }

    private fun ensureSuccess(response: Response<Unit>) {
        if (response.isSuccessful) return
        val body = response.errorBody()?.string().orEmpty()
        val serverMessage = try {
            gson.fromJson(body, ErrorResponseDto::class.java)?.error
        } catch (_: Exception) {
            null
        }
        throw PhotoApiException(serverMessage ?: "Server returned HTTP ${response.code()}")
    }

    private fun actionUrl(photo: Photo, action: String): String {
        val imageUrl = photo.imageUrl.toHttpUrlOrNull()
            ?: throw IOException("The server returned an invalid image URL")
        val pathSegments = imageUrl.encodedPathSegments
        require(pathSegments.size == 4 && pathSegments[0] == "api" &&
            pathSegments[1] == "photos" && pathSegments[3] == "image"
        ) {
            "The image URL does not contain a valid photo identifier"
        }
        return imageUrl.newBuilder()
            .removePathSegment(3)
            .addPathSegment(action)
            .query(null)
            .fragment(null)
            .build()
            .toString()
    }

    private fun HistoryPhotoDto.toDomain(baseUrl: HttpUrl): Photo {
        val resolvedImageUrl = baseUrl.resolve(imageUrl)?.toString()
            ?: throw IOException("The server returned an invalid image URL")
        return Photo(
            path = path,
            album = album,
            metadata = PhotoMetadata(
                capturedAt = metadata?.capturedAt,
                latitude = metadata?.coordinates?.latitude,
                longitude = metadata?.coordinates?.longitude,
                exif = metadata?.exif.orEmpty(),
            ),
            imageUrl = resolvedImageUrl,
            shownAt = shownAt,
        )
    }

    private fun endpointUrl(baseUrl: HttpUrl, path: String): String = baseUrl.newBuilder()
        .addPathSegments(path)
        .build()
        .toString()

    private fun Throwable.userMessage(): String = message?.takeIf(String::isNotBlank)
        ?: "Unable to reach the photo server. Retrying…"

    companion object {
        private const val INITIAL_RETRY_DELAY_MS = 1_000L
        private const val MAX_RETRY_DELAY_MS = 30_000L
        private const val STABLE_CONNECTION_NANOS = 10_000_000_000L
    }
}

class PhotoApiException(message: String) : IOException(message)

fun mergePhotos(first: List<Photo>, second: List<Photo>): List<Photo> =
    (first + second)
        .distinctBy(Photo::selectionKey)
        .sortedWith(
            compareByDescending<Photo> { it.shownAt.toInstantOrNull() }
                .thenByDescending(Photo::shownAt),
        )
        .take(10)

private fun String.toInstantOrNull(): Instant? = try {
    Instant.parse(this)
} catch (_: Exception) {
    null
}