package org.linuxalert.photo_slide_app

import com.google.gson.Gson
import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import org.linuxalert.photo_slide_app.data.FaultyTagRequestDto
import org.linuxalert.photo_slide_app.data.HistoryPhotoDto
import org.linuxalert.photo_slide_app.data.HistoryResponseDto
import org.linuxalert.photo_slide_app.data.PhotoApi
import org.linuxalert.photo_slide_app.data.PhotoApiException
import org.linuxalert.photo_slide_app.data.PhotoEventSource
import org.linuxalert.photo_slide_app.data.PhotoRepository
import org.linuxalert.photo_slide_app.data.PhotoStreamMessage
import org.linuxalert.photo_slide_app.data.RatingRequestDto
import org.linuxalert.photo_slide_app.data.mergePhotos
import org.linuxalert.photo_slide_app.domain.Photo
import org.linuxalert.photo_slide_app.domain.PhotoMetadata
import retrofit2.Response

class PhotoRepositoryTest {
    @Test
    fun `given repeated selections when merging photos then deduplicate by timestamp and image URL`() {
        val first = photo("2026-11-08T10:00:00Z", "first")
        val duplicate = first.copy(path = "duplicate-path")
        val secondSelection = first.copy(shownAt = "2026-11-08T10:01:00Z")

        val result = mergePhotos(listOf(first, duplicate), listOf(secondSelection))

        assertEquals(listOf(secondSelection, first), result)
    }

    @Test
    fun `given more than ten selections when merging photos then keep newest ten in descending order`() {
        val selections = (0..11).map { index ->
            photo("2026-11-08T10:${index.toString().padStart(2, '0')}:00Z", "photo-$index")
        }

        val result = mergePhotos(selections, emptyList())

        assertEquals(10, result.size)
        assertEquals("photo-11", result.first().path)
        assertEquals("photo-2", result.last().path)
    }

    @Test
    fun `given fractional timestamps when merging photos then order by instant`() {
        val wholeSecond = photo("2026-11-08T10:00:00Z", "whole-second")
        val fractionalSecond = photo("2026-11-08T10:00:00.900Z", "fractional-second")

        val result = mergePhotos(listOf(wholeSecond, fractionalSecond), emptyList())

        assertEquals(listOf(fractionalSecond, wholeSecond), result)
    }

    @Test
    fun `given opaque image URL when rating then preserve its identifier in feedback endpoint`() = runTest {
        val api = FakePhotoApi()
        val repository = PhotoRepository(api, EmptyEventSource, Gson())

        repository.rate(photo("2026-11-08T10:00:00Z", "one", "opaque-id_17"), 5)

        assertEquals("http://server.test/api/photos/opaque-id_17/rating", api.ratingUrl)
        assertEquals(5, api.ratingRequest?.rating)
    }

    @Test
    fun `given server rejects feedback when reporting faulty tag then surface API error`() = runTest {
        val api = FakePhotoApi().apply {
            faultyTagResponse = Response.error(
                503,
                """{"error":"Feedback service unavailable"}"""
                    .toResponseBody("application/json".toMediaType()),
            )
        }
        val repository = PhotoRepository(api, EmptyEventSource, Gson())

        try {
            repository.reportFaultyTag(photo("2026-11-08T10:00:00Z", "one"), " Make ")
            throw AssertionError("Expected feedback failure")
        } catch (error: PhotoApiException) {
            assertEquals("Feedback service unavailable", error.message)
        }
        assertEquals("Make", api.faultyTagRequest?.tag)
    }

    @Test
    fun `given event stream disconnects when observing photos then reconnect and refresh history`() = runTest {
        val api = FakePhotoApi()
        var connections = 0
        val eventSource = object : PhotoEventSource {
            override fun events(url: String): Flow<PhotoStreamMessage> = flow {
                connections += 1
                emit(PhotoStreamMessage.Connected)
                if (connections == 1) {
                    delay(1)
                    throw IOException("connection lost")
                }
                emit(PhotoStreamMessage.PhotoSelected(historyPhoto("2026-11-08T10:01:00Z", "new-photo")))
            }
        }
        val repository = PhotoRepository(api, eventSource, Gson())

        val feed = repository.observePhotos("http://server.test:8080")
            .first { it.photos.isNotEmpty() }

        assertEquals(2, connections)
        assertEquals(2, api.historyCalls)
        assertEquals("new-photo", feed.photos.single().path)
    }

    private fun photo(
        shownAt: String,
        id: String,
        imageId: String = id,
    ) = Photo(
        path = id,
        album = null,
        metadata = PhotoMetadata(null, null, null, emptyMap()),
        imageUrl = "http://server.test/api/photos/$imageId/image",
        shownAt = shownAt,
    )

    private fun historyPhoto(shownAt: String, path: String) = HistoryPhotoDto(
        path = path,
        imageUrl = "http://server.test/api/photos/opaque/image",
        shownAt = shownAt,
    )

    private class FakePhotoApi : PhotoApi {
        var historyCalls = 0
        var ratingUrl: String? = null
        var ratingRequest: RatingRequestDto? = null
        var faultyTagRequest: FaultyTagRequestDto? = null
        var faultyTagResponse: Response<Unit> = Response.success(Unit)

        override suspend fun history(url: String): HistoryResponseDto {
            historyCalls += 1
            return HistoryResponseDto(emptyList())
        }

        override suspend fun rate(url: String, body: RatingRequestDto): Response<Unit> {
            ratingUrl = url
            ratingRequest = body
            return Response.success(Unit)
        }

        override suspend fun reject(url: String): Response<Unit> = Response.success(Unit)

        override suspend fun reportFaultyTag(
            url: String,
            body: FaultyTagRequestDto,
        ): Response<Unit> {
            faultyTagRequest = body
            return faultyTagResponse
        }
    }

    private object EmptyEventSource : PhotoEventSource {
        override fun events(url: String): Flow<PhotoStreamMessage> = flow {
            emit(PhotoStreamMessage.Connected)
            awaitCancellation()
        }
    }
}