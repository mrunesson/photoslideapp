package org.linuxalert.photo_slide_app

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.linuxalert.photo_slide_app.data.DiscoveredService
import org.linuxalert.photo_slide_app.data.DiscoveryState
import org.linuxalert.photo_slide_app.data.NsdServiceBrowser
import org.linuxalert.photo_slide_app.data.NsdServiceEvent
import org.linuxalert.photo_slide_app.data.NsdServerDiscovery
import org.linuxalert.photo_slide_app.data.ResolvedServer
import org.linuxalert.photo_slide_app.data.resolvedServerAddress

@OptIn(ExperimentalCoroutinesApi::class)
class ServerDiscoveryTest {
    @Test
    fun `given a service is found when it resolves then publish its server address`() = runTest {
        val service = DiscoveredService("Photo Server", "_photo-server._tcp.")
        val browser = FakeNsdServiceBrowser().apply {
            resolvedAddress = ResolvedServer("192.168.1.25", 8123)
        }
        val states = mutableListOf<DiscoveryState>()
        val discoveryJob = backgroundScope.launch {
            NsdServerDiscovery(browser).discover().collect(states::add)
        }
        runCurrent()

        browser.emit(NsdServiceEvent.Found(service))
        runCurrent()

        assertEquals("http://192.168.1.25:8123", states.last().address)
        discoveryJob.cancelAndJoin()
    }

    @Test
    fun `given a resolved service is lost when the loss arrives then clear its address`() = runTest {
        val service = DiscoveredService("Photo Server", "_photo-server._tcp.")
        val browser = FakeNsdServiceBrowser().apply {
            resolvedAddress = ResolvedServer("192.168.1.25", 8123)
        }
        val states = mutableListOf<DiscoveryState>()
        val discoveryJob = backgroundScope.launch {
            NsdServerDiscovery(browser).discover().collect(states::add)
        }
        runCurrent()
        browser.emit(NsdServiceEvent.Found(service))
        runCurrent()
        assertEquals("http://192.168.1.25:8123", states.last().address)

        browser.emit(NsdServiceEvent.Lost(service))
        runCurrent()

        assertNull(states.last().address)
        discoveryJob.cancelAndJoin()
    }

    @Test
    fun `given service resolution fails when retry succeeds then publish the resolved address`() = runTest {
        val service = DiscoveredService("Photo Server", "_photo-server._tcp.")
        val browser = FakeNsdServiceBrowser().apply {
            resolutionFailures = 1
            resolvedAddress = ResolvedServer("photo.local", 8080)
        }
        val states = mutableListOf<DiscoveryState>()
        val discoveryJob = backgroundScope.launch {
            NsdServerDiscovery(browser).discover().collect(states::add)
        }
        runCurrent()
        browser.emit(NsdServiceEvent.Found(service))
        runCurrent()

        assertEquals("Temporary resolver failure", states.last().error)
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(2, browser.resolutionCalls)
        assertEquals("http://photo.local:8080", states.last().address)
        discoveryJob.cancelAndJoin()
    }

    @Test
    fun `given discovery reports an error when it resumes then resolve newly found service`() = runTest {
        val service = DiscoveredService("Photo Server", "_photo-server._tcp.")
        val browser = FakeNsdServiceBrowser().apply {
            resolvedAddress = ResolvedServer("192.168.1.25", 8080)
        }
        val states = mutableListOf<DiscoveryState>()
        val discoveryJob = backgroundScope.launch {
            NsdServerDiscovery(browser).discover().collect(states::add)
        }
        runCurrent()
        browser.emit(NsdServiceEvent.Failure("Discovery temporarily failed"))
        runCurrent()

        assertNull(states.last().address)
        assertEquals("Discovery temporarily failed", states.last().error)

        browser.emit(NsdServiceEvent.Found(service))
        runCurrent()

        assertEquals("http://192.168.1.25:8080", states.last().address)
        discoveryJob.cancelAndJoin()
    }

    @Test
    fun `given one service is resolving when it is lost then resolve the next service without overlap`() = runTest {
        val primary = DiscoveredService("Primary", "_photo-server._tcp.")
        val backup = DiscoveredService("Backup", "_photo-server._tcp.")
        val browser = FakeNsdServiceBrowser().apply {
            blockedServiceName = primary.name
            resolvedAddress = ResolvedServer("192.168.1.26", 8080)
        }
        val states = mutableListOf<DiscoveryState>()
        val discoveryJob = backgroundScope.launch {
            NsdServerDiscovery(browser).discover().collect(states::add)
        }
        runCurrent()
        browser.emit(NsdServiceEvent.Found(primary))
        runCurrent()
        browser.emit(NsdServiceEvent.Found(backup))
        runCurrent()
        assertEquals(1, browser.resolutionCalls)

        browser.emit(NsdServiceEvent.Lost(primary))
        runCurrent()

        assertEquals(2, browser.resolutionCalls)
        assertEquals(1, browser.maximumConcurrentResolutions)
        assertEquals("http://192.168.1.26:8080", states.last().address)
        discoveryJob.cancelAndJoin()
    }

    @Test
    fun `given IPv4 and IPv6 hosts when making server URLs then bracket IPv6 correctly`() {
        assertEquals("http://192.168.1.25:8080", resolvedServerAddress("192.168.1.25", 8080))
        assertEquals("http://[2001:db8::1]:8080", resolvedServerAddress("2001:db8::1", 8080))
        assertEquals("http://[fe80::1]:8080", resolvedServerAddress("fe80::1%wlan0", 8080))
    }

    @Test
    fun `given discovery is cancelled when collection ends then release the browser`() = runTest {
        val browser = FakeNsdServiceBrowser()
        val discoveryJob = backgroundScope.launch {
            NsdServerDiscovery(browser).discover().collect()
        }
        runCurrent()

        discoveryJob.cancelAndJoin()

        assertTrue(browser.closed)
    }

    private class FakeNsdServiceBrowser : NsdServiceBrowser {
        private val mutableEvents = MutableSharedFlow<NsdServiceEvent>(extraBufferCapacity = 8)
        var resolvedAddress = ResolvedServer("192.168.1.25", 8080)
        var resolutionFailures = 0
        var resolutionCalls = 0
        var blockedServiceName: String? = null
        var activeResolutions = 0
        var maximumConcurrentResolutions = 0
        var closed = false

        override fun events(): Flow<NsdServiceEvent> = kotlinx.coroutines.flow.flow {
            try {
                mutableEvents.collect { emit(it) }
            } finally {
                closed = true
            }
        }

        override suspend fun resolve(service: DiscoveredService): ResolvedServer {
            resolutionCalls += 1
            activeResolutions += 1
            maximumConcurrentResolutions = maxOf(maximumConcurrentResolutions, activeResolutions)
            try {
                if (service.name == blockedServiceName) CompletableDeferred<ResolvedServer>().await()
                if (resolutionFailures > 0) {
                    resolutionFailures -= 1
                    throw IOException("Temporary resolver failure")
                }
                return resolvedAddress
            } finally {
                activeResolutions -= 1
            }
        }

        fun emit(event: NsdServiceEvent) {
            check(mutableEvents.tryEmit(event))
        }
    }
}