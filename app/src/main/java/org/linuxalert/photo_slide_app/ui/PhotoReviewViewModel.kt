package org.linuxalert.photo_slide_app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.linuxalert.photo_slide_app.data.PhotoConnection
import org.linuxalert.photo_slide_app.data.PhotoRepository
import org.linuxalert.photo_slide_app.data.ServerAddressStore
import org.linuxalert.photo_slide_app.data.validateServerAddress
import org.linuxalert.photo_slide_app.domain.Photo

data class PhotoReviewUiState(
    val photos: List<Photo> = emptyList(),
    val connection: PhotoConnection = PhotoConnection.CONNECTING,
    val serverAddress: String = "",
    val connectionError: String? = null,
    val feedbackMessages: Map<String, String> = emptyMap(),
    val feedbackInProgress: Set<String> = emptySet(),
    val tagDrafts: Map<String, String> = emptyMap(),
)

@HiltViewModel
class PhotoReviewViewModel @Inject constructor(
    private val repository: PhotoRepository,
    private val serverAddressStore: ServerAddressStore,
) : ViewModel() {
    private val _state = MutableStateFlow(PhotoReviewUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            serverAddressStore.address.distinctUntilChanged().collectLatest { address ->
                _state.update {
                    it.copy(serverAddress = address, connection = PhotoConnection.CONNECTING, connectionError = null)
                }
                repository.observePhotos(address).collect { feed ->
                    _state.update {
                        it.copy(
                            photos = feed.photos,
                            connection = feed.connection,
                            connectionError = feed.error,
                        )
                    }
                }
            }
        }
    }

    fun saveServerAddress(address: String): Boolean {
        val normalized = try {
            validateServerAddress(address)
        } catch (error: Exception) {
            _state.update { it.copy(connectionError = error.message ?: "Unable to save server address") }
            return false
        }
        viewModelScope.launch {
            try {
                serverAddressStore.save(normalized)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(connectionError = error.message ?: "Unable to save server address") }
            }
        }
        return true
    }

    fun updateTagDraft(photo: Photo, tag: String) {
        _state.update { it.copy(tagDrafts = it.tagDrafts + (photo.selectionKey to tag)) }
    }

    fun rate(photo: Photo, rating: Int) {
        if (rating !in 1..5) return
        submitFeedback(photo) { repository.rate(photo, rating) }
    }

    fun reject(photo: Photo) {
        submitFeedback(photo) { repository.reject(photo) }
    }

    fun reportTag(photo: Photo, tag: String = _state.value.tagDrafts[photo.selectionKey].orEmpty()) {
        submitFeedback(photo) { repository.reportFaultyTag(photo, tag) }
    }

    private fun submitFeedback(photo: Photo, action: suspend () -> Unit) {
        val key = photo.selectionKey
        if (key in _state.value.feedbackInProgress) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    feedbackInProgress = it.feedbackInProgress + key,
                    feedbackMessages = it.feedbackMessages - key,
                )
            }
            try {
                action()
                _state.update { it.copy(feedbackMessages = it.feedbackMessages + (key to "Feedback saved")) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update {
                    it.copy(feedbackMessages = it.feedbackMessages + (key to (error.message ?: "Feedback failed")))
                }
            } finally {
                _state.update { it.copy(feedbackInProgress = it.feedbackInProgress - key) }
            }
        }
    }
}