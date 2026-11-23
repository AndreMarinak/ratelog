package org.ratelog.test

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A local HTTP server standing in for TMDB v3 (base URL [baseUrl], e.g. `http://127.0.0.1:PORT/3`).
 * Serves canned JSON by path and records every request. Unknown paths answer 404.
 */
class FakeTmdbServer : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val responses = ConcurrentHashMap<String, Pair<Int, String>>()

    /** Request URIs (path + query), in order. */
    val requests = CopyOnWriteArrayList<URI>()

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/3"

    init {
        server.createContext("/") { exchange ->
            requests += exchange.requestURI
            val path = exchange.requestURI.path.removePrefix("/3")
            val (status, body) = responses[path] ?: (404 to """{"status_code":34,"status_message":"Not found"}""")
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json;charset=utf-8")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    /** Answer `path` (without `/3`, e.g. `/trending/movie/week`) with this status and body. */
    fun respond(path: String, body: String, status: Int = 200) {
        responses[path] = status to body
    }

    fun requestsTo(path: String): List<URI> = requests.filter { it.path == "/3$path" }

    override fun close() = server.stop(0)

    companion object {
        /** A TMDB list page from `{"id": .., "title"/"name": .., ...}` objects written as JSON. */
        fun page(vararg results: String) = """{"page":1,"results":[${results.joinToString(",")}],"total_pages":1,"total_results":${results.size}}"""

        fun movie(id: Int, title: String, releaseDate: String? = "2026-01-01", poster: String? = "/m$id.jpg", adult: Boolean = false) =
            """{"id":$id,"title":${json(title)},"original_title":${json(title)},"release_date":${json(releaseDate)},"poster_path":${json(poster)},"adult":$adult,"media_type":"movie"}"""

        fun show(id: Int, name: String, firstAirDate: String? = "2025-03-01", poster: String? = "/t$id.jpg") =
            """{"id":$id,"name":${json(name)},"original_name":${json(name)},"first_air_date":${json(firstAirDate)},"poster_path":${json(poster)},"media_type":"tv"}"""

        private fun json(value: String?) = if (value == null) "null" else "\"" + value.replace("\"", "\\\"") + "\""
    }
}
