package de.sofahub.app.integration.homeassistant

import de.sofahub.app.core.model.HaError
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class HaRestClientTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.close() }

    private fun rest(token: String = "secret-token") = HaRestClient(server.url("/"), token, client)

    @Test fun statesAreParsedAndTokenSentAsBearer() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""[{"entity_id":"light.a","state":"on","attributes":{"friendly_name":"Lampe"},"last_changed":"x","last_updated":"x"},{"kaputt":1}]""").build())
        val r = rest().states() as HaResult.Ok
        assertEquals(1, r.value.size)
        assertEquals("Lampe", r.value[0].friendlyName)
        val req = server.takeRequest()
        assertEquals("/api/states", req.url.encodedPath)
        assertEquals("Bearer secret-token", req.headers["Authorization"])
    }

    @Test fun statusCodesMapToDistinctErrors() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).build())
        assertEquals(HaResult.Err(HaError.Unauthorized), rest().checkApi())
        server.enqueue(MockResponse.Builder().code(403).build())
        assertEquals(HaResult.Err(HaError.Forbidden), rest().checkApi())
        server.enqueue(MockResponse.Builder().code(502).build())
        assertEquals(HaResult.Err(HaError.Server(502)), rest().checkApi())
        server.enqueue(MockResponse.Builder().body("<html>").build())
        assertEquals(HaResult.Err(HaError.InvalidResponse), rest().checkApi())
    }

    @Test fun redirectIsNotFollowedSoTokenNeverLeaves() = runBlocking {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "http://evil.example/api/").build())
        assertEquals(HaResult.Err(HaError.Redirect), rest().checkApi())
        assertEquals(1, server.requestCount)
    }

    @Test fun unreachableHostIsNetworkError() = runBlocking {
        val url = server.url("/")
        server.close()
        val r = HaRestClient(url, "t", client).checkApi()
        assertEquals(HaResult.Err(HaError.Network), r)
    }

    @Test fun serviceCallPostsJsonPayload() = runBlocking {
        server.enqueue(MockResponse.Builder().body("[]").build())
        val call = HaServices.setBrightness("light.a", 50)
        assertTrue(rest().callService(call.domain, call.service, call.data) is HaResult.Ok)
        val req = server.takeRequest()
        assertEquals("/api/services/light/turn_on", req.url.encodedPath)
        val body = Json.parseToJsonElement(req.body!!.utf8()).jsonObject
        assertEquals(128, body["brightness"]!!.jsonPrimitive.int)
    }
}

/** Fake-HA über MockWebServer-WebSocket: prüft Handshake, Reihenfolge (Subscription vor Snapshot) und Event-Puffer. */
class HaWebSocketSessionTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.close() }

    private class FakeHa(val authOk: Boolean = true, val registryAllowed: Boolean = true, val burst: Int = 0) : WebSocketListener() {
        val received = CopyOnWriteArrayList<JsonObject>()
        var socket: WebSocket? = null
        private var subscribeId = 0

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            webSocket.send("""{"type":"auth_required","ha_version":"2026.10.0"}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = Json.parseToJsonElement(text).jsonObject
            received += msg
            val id = msg["id"]?.jsonPrimitive?.int
            when (msg["type"]!!.jsonPrimitive.content) {
                "auth" -> webSocket.send(if (authOk && msg["access_token"]!!.jsonPrimitive.content == "good") """{"type":"auth_ok"}""" else """{"type":"auth_invalid","message":"x"}""")
                "subscribe_events" -> {
                    subscribeId = id!!
                    webSocket.send("""{"id":$id,"type":"result","success":true,"result":null}""")
                    // Änderung zwischen Subscription und Snapshot: neuer als der Snapshot-Stand.
                    webSocket.send(event(id, "light.a", "on", "2026-10-31T19:00:09+00:00"))
                }
                "get_states" -> webSocket.send(
                    """{"id":$id,"type":"result","success":true,"result":[
                    {"entity_id":"light.a","state":"off","attributes":{},"last_changed":"2026-10-31T19:00:05+00:00","last_updated":"2026-10-31T19:00:05+00:00"},
                    {"entity_id":"switch.b","state":"on","attributes":{},"last_changed":"2026-10-31T19:00:05+00:00","last_updated":"2026-10-31T19:00:05+00:00"}]}""",
                ).also {
                    // Schub vieler Änderungen auf einmal (wie bei vielen Sensoren im Haus).
                    for (i in 1..burst) webSocket.send(event(subscribeId, "switch.b", "v$i", "2026-10-31T19:%02d:%02d+00:00".format(1 + i / 60, i % 60)))
                }
                "config/area_registry/list" -> webSocket.send(
                    if (registryAllowed) """{"id":$id,"type":"result","success":true,"result":[{"area_id":"wohnen","name":"Wohnzimmer"}]}"""
                    else """{"id":$id,"type":"result","success":false,"error":{"code":"unauthorized","message":"x"}}""",
                )
                "config/entity_registry/list" -> webSocket.send("""{"id":$id,"type":"result","success":true,"result":[{"entity_id":"light.a","area_id":null,"device_id":"d1"},{"entity_id":"switch.b","area_id":"wohnen","device_id":null}]}""")
                "config/device_registry/list" -> webSocket.send("""{"id":$id,"type":"result","success":true,"result":[{"id":"d1","area_id":"wohnen"}]}""")
                "subscribe_entities" -> {
                    webSocket.send("""{"id":$id,"type":"result","success":true,"result":null}""")
                    webSocket.send("""{"id":$id,"type":"event","event":{"a":{"sensor.wm":{"s":"Läuft","a":{"friendly_name":"Waschmaschine"},"c":"x","lc":1767268800.0}}}}""")
                    webSocket.send("""{"id":$id,"type":"event","event":{"c":{"sensor.wm":{"+":{"s":"Fertig","lc":1767272400.0}}}}}""")
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        fun event(subId: Int, entity: String, state: String, updated: String) =
            """{"id":$subId,"type":"event","event":{"event_type":"state_changed","data":{"entity_id":"$entity","old_state":null,"new_state":{"entity_id":"$entity","state":"$state","attributes":{},"last_changed":"$updated","last_updated":"$updated"}}}}"""
    }

    private fun url() = server.url("/api/websocket").toString().replace("http://", "ws://")

    @Test fun fullHandshakeSnapshotMergeAndRegistry() = runBlocking {
        val fake = FakeHa()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(fake).build())
        val session = HaWebSocketSession(client, url(), "good")
        val events = CopyOnWriteArrayList<SessionEvent>()
        val end = withTimeout(10_000) {
            session.run { e ->
                events += e
                if (e is SessionEvent.Registry || e is SessionEvent.RegistryUnavailable) session.close()
            }
        }
        assertTrue(end is SessionEnd.Lost)
        val types = fake.received.map { it["type"]!!.jsonPrimitive.content }
        assertEquals("auth", types[0])
        assertTrue("Subscription muss vor get_states kommen", types.indexOf("subscribe_events") < types.indexOf("get_states"))
        val ids = fake.received.mapNotNull { it["id"]?.jsonPrimitive?.int }
        assertEquals("IDs eindeutig", ids.size, ids.toSet().size)
        val snapshot = events.filterIsInstance<SessionEvent.Snapshot>().single()
        assertEquals("gepuffertes neueres Event gewinnt", "on", snapshot.entities["light.a"]!!.state)
        val registry = events.filterIsInstance<SessionEvent.Registry>().single()
        assertEquals("wohnen", registry.entityAreas["light.a"])
        assertEquals("wohnen", registry.entityAreas["switch.b"])
        assertEquals("Wohnzimmer", registry.areas["wohnen"]!!.name)
    }

    @Test fun changeBurstIsCoalescedWithoutLosingTheLatestState() = runBlocking {
        val burst = 200
        server.enqueue(MockResponse.Builder().webSocketUpgrade(FakeHa(burst = burst)).build())
        val session = HaWebSocketSession(client, url(), "good")
        val changed = CopyOnWriteArrayList<SessionEvent.Changed>()
        withTimeout(10_000) {
            session.run { e ->
                if (e is SessionEvent.Changed) {
                    changed += e
                    if (e.entities["switch.b"]?.state == "v$burst") session.close()
                }
            }
        }
        assertEquals("letzter Stand kommt an", "v$burst", changed.last().entities["switch.b"]!!.state)
        assertTrue("Schübe zusammengefasst (${changed.size} statt $burst Kopien)", changed.size < burst)
    }

    @Test fun backgroundWatchSubscribesOnlyToChosenEntities() = runBlocking {
        val fake = FakeHa()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(fake).build())
        val session = HaWebSocketSession(client, url(), "good", watch = setOf("sensor.wm"))
        val events = CopyOnWriteArrayList<SessionEvent>()
        withTimeout(10_000) {
            session.run { e ->
                events += e
                if (e is SessionEvent.Changed) session.close()
            }
        }
        val types = fake.received.map { it["type"]!!.jsonPrimitive.content }
        assertEquals("Nur Anmeldung und das gezielte Abo – keine Gesamtliste, keine Registry", listOf("auth", "subscribe_entities"), types)
        assertEquals(listOf("sensor.wm"), fake.received[1]["entity_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Läuft", events.filterIsInstance<SessionEvent.Snapshot>().single().entities["sensor.wm"]!!.state)
        assertEquals("Fertig", events.filterIsInstance<SessionEvent.Changed>().single().entities["sensor.wm"]!!.state)
    }

    @Test fun authInvalidEndsWithoutRetryLoop() = runBlocking {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(FakeHa()).build())
        val end = withTimeout(10_000) { HaWebSocketSession(client, url(), "bad").run { } }
        assertEquals(SessionEnd.AuthInvalid, end)
    }

    @Test fun missingRegistryRightsFallBackToManualRooms() = runBlocking {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(FakeHa(registryAllowed = false)).build())
        val session = HaWebSocketSession(client, url(), "good")
        val events = CopyOnWriteArrayList<SessionEvent>()
        withTimeout(10_000) {
            session.run { e ->
                events += e
                if (e is SessionEvent.RegistryUnavailable || e is SessionEvent.Registry) session.close()
            }
        }
        assertTrue(events.any { it is SessionEvent.RegistryUnavailable })
        assertTrue(events.any { it is SessionEvent.Snapshot })
    }

    @Test fun serverDisappearingIsReportedAsLost() = runBlocking {
        server.enqueue(MockResponse.Builder().code(503).build())
        val end = withTimeout(10_000) { HaWebSocketSession(client, url(), "good").run { } }
        assertTrue(end is SessionEnd.Lost)
    }
}
