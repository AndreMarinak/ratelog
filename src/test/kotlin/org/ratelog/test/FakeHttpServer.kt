package org.ratelog.test

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** Tiny local HTTP server for client tests: canned responses, records the request URIs it got. */
class FakeHttpServer : AutoCloseable {
    data class Reply(val status: Int, val body: String = "", val headers: Map<String, String> = emptyMap())

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val requests = CopyOnWriteArrayList<String>()

    @Volatile
    var reply: (path: String) -> Reply = { Reply(404, """{"error":"not found"}""") }

    init {
        server.createContext("/") { exchange ->
            requests += exchange.requestURI.toString()
            val r = reply(exchange.requestURI.path)
            val bytes = r.body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            r.headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
            exchange.sendResponseHeaders(r.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    override fun close() = server.stop(0)
}
