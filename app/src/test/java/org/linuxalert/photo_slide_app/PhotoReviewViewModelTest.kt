package org.linuxalert.photo_slide_app

import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.linuxalert.photo_slide_app.data.DiscoveredService
import org.linuxalert.photo_slide_app.data.DiscoveryState
import org.linuxalert.photo_slide_app.data.FaultyTagRequestDto
import org.linuxalert.photo_slide_app.data.HistoryPhotoDto
import org.linuxalert.photo_slide_app.data.HistoryResponseDto
import org.linuxalert.photo_slide_app.data.PhotoApi
import org.linuxalert.photo_slide_app.data.PhotoConnection
import org.linuxalert.photo_slide_app.data.PhotoEventSource
import org.linuxalert.photo_slide_app.data.PhotoRepository
import org.linuxalert.photo_slide_app.data.PhotoStreamMessage
import org.linuxalert.photo_slide_app.data.RatingRequestDto
import org.linuxalert.photo_slide_app.data.ServerAddressStore
import org.linuxalert.photo_slide_app.data.ServerDiscovery
import org.linuxalert.photo_slide_app.ui.PhotoReviewViewModel
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoReviewViewModelTest {
    @Test
    fun `given a manual server address when reconnect is requested then restart photo feed immediately`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val eventSource = CountingEventSource()
        val discovery = FakeServerDiscovery()
        val viewModel = PhotoReviewViewModel(
            PhotoRepository(FakePhotoApi(), eventSource, Gson()),
            FakeServerAddressStore("http://server.test:8080"),
            discovery,
        )

        try {
            runCurrent()
            assertEquals(1, eventSource.connections)
            assertEquals(0, discovery.starts)

            viewModel.reconnect()
            runCurrent()

            assertEquals(2, eventSource.connections)
        } finally {
            viewModel.viewModelScope.cancel()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `given automatic discovery finds then loses a server when services change then clear stale photos`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val eventSource = CountingEventSource()
        val discovery = FakeServerDiscovery()
        val viewModel = PhotoReviewViewModel(
            PhotoRepository(FakePhotoApi(), eventSource, Gson()),
            FakeServerAddressStore(""),
            discovery,
        )
        val serviceAddress = "http://192.168.1.25:8080"

        try {
            runCurrent()
            assertEquals(1, discovery.starts)

            discovery.emit(DiscoveryState(address = serviceAddress))
            runCurrent()
            eventSource.emit(
                PhotoStreamMessage.PhotoSelected(
                    HistoryPhotoDto("album/photo.jpg", imageUrl = "/api/photos/1/image", shownAt = "2026-01-01T00:00:00Z"),
                ),
            )
            runCurrent()
            assertEquals(1, viewModel.state.value.photos.size)
            assertTrue(eventSource.urls.last().startsWith(serviceAddress))

            discovery.emit(DiscoveryState())
            runCurrent()

            assertTrue(viewModel.state.value.photos.isEmpty())
            assertNull(viewModel.state.value.discoveredServerAddress)
            assertEquals(PhotoConnection.CONNECTING, viewModel.state.value.connection)
            assertEquals(1, eventSource.cancellations)
        } finally {
            viewModel.viewModelScope.cancel()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `given automatic discovery when a manual address is saved and automatic mode is restored then use each mode`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val eventSource = CountingEventSource()
        val discovery = FakeServerDiscovery()
        val store = FakeServerAddressStore("")
        val viewModel = PhotoReviewViewModel(PhotoRepository(FakePhotoApi(), eventSource, Gson()), store, discovery)

        try {
            runCurrent()
            discovery.emit(DiscoveryState(address = "http://photo.local:8080"))
            runCurrent()

            assertTrue(viewModel.saveServerAddress("http://manual.local:9000"))
            runCurrent()

            assertEquals("http://manual.local:9000", viewModel.state.value.serverAddress)
            assertTrue(eventSource.urls.last().startsWith("http://manual.local:9000"))
            assertFalse(eventSource.urls.last().startsWith("http://photo.local:8080"))
            assertEquals(1, discovery.starts)

            viewModel.useAutomaticDiscovery()
            runCurrent()

            assertEquals("", viewModel.state.value.serverAddress)
            assertTrue(discovery.starts >= 2)
            assertTrue(eventSource.urls.last().startsWith("http://photo.local:8080"))
        } finally {
            viewModel.viewModelScope.cancel()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    private class FakeServerAddressStore(initialAddress: String) : ServerAddressStore {
        private val mutableAddress = MutableStateFlow(initialAddress)
        override val address = mutableAddress.asStateFlow()

        override suspend fun save(address: String) {
            mutableAddress.value = address
        }

        override suspend fun clear() {
            mutableAddress.value = ""
        }
    }

    private class FakeServerDiscovery : ServerDiscovery {
        private val states = MutableSharedFlow<DiscoveryState>(replay = 1, extraBufferCapacity = 8)
        var starts = 0

        init {
            states.tryEmit(DiscoveryState())
        }

        override fun discover(): Flow<DiscoveryState> = states.onStart { starts += 1 }

        fun emit(state: DiscoveryState) {
            check(states.tryEmit(state))
        }
    }

    private class CountingEventSource : PhotoEventSource {
        var connections = 0
        var cancellations = 0
        val urls = mutableListOf<String>()
        private val messages = MutableSharedFlow<PhotoStreamMessage>(extraBufferCapacity = 8)

        override fun events(url: String): Flow<PhotoStreamMessage> = flow {
            connections += 1
            urls += url
            emit(PhotoStreamMessage.Connected)
            try {
                messages.collect { emit(it) }
            } finally {
                cancellations += 1
            }
        }

        fun emit(message: PhotoStreamMessage) {
            check(messages.tryEmit(message))
        }
    }

    private class FakePhotoApi : PhotoApi {
        override suspend fun history(url: String) = HistoryResponseDto(emptyList())

        override suspend fun rate(url: String, body: RatingRequestDto): Response<Unit> = Response.success(Unit)

        override suspend fun reject(url: String): Response<Unit> = Response.success(Unit)

        override suspend fun reportFaultyTag(url: String, body: FaultyTagRequestDto): Response<Unit> =
            Response.success(Unit)
    }
}