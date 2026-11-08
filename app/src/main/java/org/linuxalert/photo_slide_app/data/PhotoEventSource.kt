package org.linuxalert.photo_slide_app.data

import com.google.gson.Gson
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener

sealed interface PhotoStreamMessage {
    data object Connected : PhotoStreamMessage
    data class PhotoSelected(val photo: HistoryPhotoDto) : PhotoStreamMessage
}

interface PhotoEventSource {
    fun events(url: String): Flow<PhotoStreamMessage>
}

@Singleton
class OkHttpPhotoEventSource @Inject constructor(
    private val eventSourceFactory: EventSource.Factory,
    private val gson: Gson,
) : PhotoEventSource {
    override fun events(url: String): Flow<PhotoStreamMessage> = callbackFlow {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .build()
        val source = eventSourceFactory.newEventSource(request, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: okhttp3.Response) {
                trySend(PhotoStreamMessage.Connected)
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (type != "photo") return
                runCatching { gson.fromJson(data, HistoryPhotoDto::class.java) }
                    .onSuccess { trySend(PhotoStreamMessage.PhotoSelected(it)) }
                    .onFailure { this@callbackFlow.close(it) }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                this@callbackFlow.close(t ?: IOException("The photo event stream disconnected"))
            }

            override fun onClosed(eventSource: EventSource) {
                this@callbackFlow.close()
            }
        })
        awaitClose { source.cancel() }
    }
}