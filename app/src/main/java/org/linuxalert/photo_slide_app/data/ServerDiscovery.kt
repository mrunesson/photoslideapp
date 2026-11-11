package org.linuxalert.photo_slide_app.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellableContinuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class DiscoveredService(val name: String, val type: String) {
    val id: String get() = "$name\u0000$type"
}

data class ResolvedServer(val host: String, val port: Int)

sealed interface NsdServiceEvent {
    data class Found(val service: DiscoveredService) : NsdServiceEvent
    data class Lost(val service: DiscoveredService) : NsdServiceEvent
    data class Failure(val message: String) : NsdServiceEvent
}

data class DiscoveryState(
    val address: String? = null,
    val error: String? = null,
)

interface NsdServiceBrowser {
    fun events(): Flow<NsdServiceEvent>
    suspend fun resolve(service: DiscoveredService): ResolvedServer
}

interface ServerDiscovery {
    fun discover(): Flow<DiscoveryState>
}

@Singleton
class NsdServerDiscovery @Inject constructor(
    private val browser: NsdServiceBrowser,
) : ServerDiscovery {
    override fun discover(): Flow<DiscoveryState> = channelFlow {
        val events = Channel<CoordinatorEvent>(Channel.UNLIMITED)
        val worker = launch {
            browser.events().collect { events.send(CoordinatorEvent.Browser(it)) }
        }
        val services = linkedMapOf<String, DiscoveredService>()
        var selectedServiceId: String? = null
        var resolvingJob: kotlinx.coroutines.Job? = null
        var state = DiscoveryState()
        send(state)

        suspend fun resolveNext() {
            if (selectedServiceId != null) return
            val service = services.values.firstOrNull() ?: return
            selectedServiceId = service.id
            resolvingJob = launch {
                var retryDelay = INITIAL_RESOLVE_RETRY_DELAY_MS
                while (isActive) {
                    try {
                        val address = browser.resolve(service).let { resolvedServerAddress(it.host, it.port) }
                        events.send(CoordinatorEvent.Resolved(service, address))
                        return@launch
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        events.send(CoordinatorEvent.Failed(service, error.message ?: DISCOVERY_ERROR))
                        delay(retryDelay)
                        retryDelay = (retryDelay * 2).coerceAtMost(MAX_RESOLVE_RETRY_DELAY_MS)
                    }
                }
            }
        }

        try {
            for (event in events) {
                when (event) {
                    is CoordinatorEvent.Browser -> when (val browserEvent = event.event) {
                        is NsdServiceEvent.Found -> {
                            services[browserEvent.service.id] = browserEvent.service
                            if (selectedServiceId == null) {
                                state = state.copy(error = null)
                                send(state)
                                resolveNext()
                            }
                        }

                        is NsdServiceEvent.Lost -> {
                            services.remove(browserEvent.service.id)
                            if (selectedServiceId == browserEvent.service.id) {
                                resolvingJob?.cancel()
                                resolvingJob = null
                                selectedServiceId = null
                                state = DiscoveryState()
                                send(state)
                                resolveNext()
                            }
                        }

                        is NsdServiceEvent.Failure -> {
                            resolvingJob?.cancel()
                            resolvingJob = null
                            selectedServiceId = null
                            services.clear()
                            state = DiscoveryState(error = browserEvent.message)
                            send(state)
                        }
                    }

                    is CoordinatorEvent.Resolved -> {
                        if (selectedServiceId == event.service.id && event.service.id in services) {
                            resolvingJob = null
                            state = DiscoveryState(
                                address = event.address,
                            )
                            send(state)
                        }
                    }

                    is CoordinatorEvent.Failed -> {
                        if (selectedServiceId == event.service.id && event.service.id in services) {
                            state = state.copy(error = event.message)
                            send(state)
                        }
                    }
                }
            }
        } finally {
            resolvingJob?.cancel()
            worker.cancel()
            events.cancel()
        }
    }

    private sealed interface CoordinatorEvent {
        data class Browser(val event: NsdServiceEvent) : CoordinatorEvent
        data class Resolved(val service: DiscoveredService, val address: String) : CoordinatorEvent
        data class Failed(val service: DiscoveredService, val message: String) : CoordinatorEvent
    }

    private companion object {
        const val INITIAL_RESOLVE_RETRY_DELAY_MS = 1_000L
        const val MAX_RESOLVE_RETRY_DELAY_MS = 30_000L
        const val DISCOVERY_ERROR = "Unable to discover the photo server. Retrying…"
    }
}

@Singleton
class AndroidNsdServiceBrowser @Inject constructor(
    @ApplicationContext context: Context,
) : NsdServiceBrowser {
    private val manager = context.getSystemService(NsdManager::class.java)
    private val services = mutableMapOf<String, NsdServiceInfo>()
    private val resolveQueue = ArrayDeque<ResolveRequest>()
    private var activeResolve: ResolveRequest? = null

    override fun events(): Flow<NsdServiceEvent> = kotlinx.coroutines.flow.flow {
        var retryDelay = INITIAL_DISCOVERY_RETRY_DELAY_MS
        while (kotlin.coroutines.coroutineContext.isActive) {
            try {
                discoveryEvents().collect { event -> emit(event) }
                throw IOException("NSD discovery stopped")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                emit(NsdServiceEvent.Failure(error.message ?: "Unable to discover the photo server. Retrying…"))
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(MAX_DISCOVERY_RETRY_DELAY_MS)
            }
        }
    }

    @Suppress("DEPRECATION")
    override suspend fun resolve(service: DiscoveredService): ResolvedServer =
        suspendCancellableCoroutine { continuation ->
            val serviceInfo = synchronized(services) { services[service.id] }
            if (serviceInfo == null) {
                continuation.resumeWithException(IOException("The photo server is no longer available"))
                return@suspendCancellableCoroutine
            }
            val request = ResolveRequest(serviceInfo, continuation)
            continuation.invokeOnCancellation {
                synchronized(resolveQueue) {
                    if (activeResolve !== request) resolveQueue.remove(request)
                }
            }
            synchronized(resolveQueue) {
                resolveQueue.addLast(request)
                startNextResolutionLocked()
            }
        }

    @Suppress("DEPRECATION")
    private fun startNextResolutionLocked() {
        if (activeResolve != null) return
        val request = resolveQueue.pollFirst() ?: return
        if (!request.continuation.isActive) {
            startNextResolutionLocked()
            return
        }
        activeResolve = request
        try {
            manager.resolveService(request.serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    finishResolution(
                        request,
                        Result.failure(IOException("Unable to resolve the photo server (error $errorCode)")),
                    )
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host?.hostAddress
                    val result = if (host.isNullOrBlank()) {
                        Result.failure(IOException("The photo server did not provide a host address"))
                    } else {
                        Result.success(ResolvedServer(host, serviceInfo.port))
                    }
                    finishResolution(request, result)
                }
            })
        } catch (error: Exception) {
            finishResolution(request, Result.failure(error))
        }
    }

    private fun finishResolution(request: ResolveRequest, result: Result<ResolvedServer>) {
        synchronized(resolveQueue) {
            if (activeResolve !== request) return
            activeResolve = null
            if (request.continuation.isActive) {
                result.fold(
                    onSuccess = request.continuation::resume,
                    onFailure = request.continuation::resumeWithException,
                )
            }
            startNextResolutionLocked()
        }
    }

    private fun discoveryEvents(): Flow<NsdServiceEvent> = callbackFlow {
        var started = false
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(IOException("Unable to start photo server discovery (error $errorCode)"))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(IOException("Photo server discovery stopped unexpectedly (error $errorCode)"))
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onDiscoveryStopped(serviceType: String) {
                close(IOException("Photo server discovery stopped unexpectedly"))
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val service = serviceInfo.toDiscoveredService()
                synchronized(services) { services[service.id] = serviceInfo }
                trySend(NsdServiceEvent.Found(service))
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val service = serviceInfo.toDiscoveredService()
                synchronized(services) { services.remove(service.id) }
                trySend(NsdServiceEvent.Lost(service))
            }
        }
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            started = true
        } catch (error: Exception) {
            close(error)
        }
        awaitClose {
            synchronized(services) { services.clear() }
            if (started) {
                runCatching { manager.stopServiceDiscovery(listener) }
            }
        }
    }

    private fun NsdServiceInfo.toDiscoveredService() = DiscoveredService(serviceName, serviceType)

    private class ResolveRequest(
        val serviceInfo: NsdServiceInfo,
        val continuation: CancellableContinuation<ResolvedServer>,
    )

    private companion object {
        const val SERVICE_TYPE = "_photo-server._tcp."
        const val INITIAL_DISCOVERY_RETRY_DELAY_MS = 1_000L
        const val MAX_DISCOVERY_RETRY_DELAY_MS = 30_000L
    }
}

fun resolvedServerAddress(host: String, port: Int): String {
    require(port in 1..65535) { "The photo server provided an invalid port" }
    val normalizedHost = host.trim().removePrefix("[").removeSuffix("]").substringBefore('%')
    require(normalizedHost.isNotBlank()) { "The photo server did not provide a host address" }
    val url = "http://${if (':' in normalizedHost) "[$normalizedHost]" else normalizedHost}:$port"
        .toHttpUrlOrNull()
        ?: throw IllegalArgumentException("The photo server provided an invalid host address")
    return url.toString().trimEnd('/')
}