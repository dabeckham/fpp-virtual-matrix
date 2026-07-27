package app.fppvm.tv.web

import android.content.Context
import android.util.Base64
import android.util.Log
import app.fppvm.tv.config.ConfigStore
import app.fppvm.tv.config.MatrixConfig
import app.fppvm.tv.fseq.SequenceStore
import app.fppvm.tv.panel.LedProfile
import app.fppvm.tv.panel.LedProfiles
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * Configuration and file-transfer surface for this device.
 *
 * Two reasons this is a web page and not just an API. First, tuning pitch, bloom and gamma means
 * standing in front of the panel judging it by eye, and a D-pad menu is a miserable way to do that
 * — a phone in your hand is right. Second, everything else in this ecosystem is configured through
 * a web page, so a remote that was not would be the odd one out.
 *
 * It also gives xLights somewhere to push to. FPP Connect uploads by asking for a token with
 * `POST /api/file/{dir}` and then sending the bytes with `PATCH` plus `Upload-Name`,
 * `Upload-Offset` and `Upload-Length` headers — which is why a plain multipart POST silently does
 * nothing. Implementing that turns this device from pull-only into a normal upload target.
 *
 * Config writes go through the same [ConfigStore.applyOverride] merge the `--es config` intent
 * extra uses, so the D-pad, adb and the browser are three doors onto one implementation rather
 * than three that drift apart.
 */
class WebConfigServer(
    private val context: Context,
    port: Int,
    private val onConfigChanged: (MatrixConfig) -> Unit,
    private val statusJson: () -> JSONObject,
    private val identityJson: () -> JSONObject,
    /** Pulls the show back to the front — the TV may have dropped to its launcher. */
    private val onBringToFront: () -> Unit = {},
    private val storageJson: () -> JSONObject = { JSONObject() }
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "FppWeb"
        const val DEFAULT_PORT = 8080

        /** Uploads land here before being committed, so a torn transfer is never playable. */
        private const val PART_SUFFIX = ".upload"
    }

    private val configStore = ConfigStore(context)
    private val profileStore = ProfileStore(context)
    /** Spans every mounted volume, so a sequence on a USB stick is listed and served too. */
    private val sequences: SequenceStore
        get() = SequenceStore(
            app.fppvm.tv.fseq.SequenceStorage.writeDir(context, true),
            searchDirs = app.fppvm.tv.fseq.SequenceStorage.searchDirs(context)
        )

    /** Blank disables auth, matching how the rest of the show kit is usually run. */
    @Volatile
    var password: String = ""

    override fun serve(session: IHTTPSession): Response {
        return try {
            if (!authorised(session)) {
                newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "auth required")
                    .apply { addHeader("WWW-Authenticate", "Basic realm=\"FPP Virtual Matrix\"") }
            } else {
                route(session)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "request failed: ${session.uri}", t)
            json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", t.message ?: "failed"))
        }
    }

    private fun authorised(session: IHTTPSession): Boolean {
        val pw = password
        if (pw.isEmpty()) return true
        val header = session.headers["authorization"] ?: return false
        if (!header.startsWith("Basic ", ignoreCase = true)) return false
        return try {
            val decoded = String(Base64.decode(header.substring(6).trim(), Base64.DEFAULT))
            decoded.substringAfter(':') == pw
        } catch (t: Throwable) {
            false
        }
    }

    private fun route(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/').ifEmpty { "/" }
        val method = session.method

        // --- this app's own surface
        if (uri == "/" || uri == "/index.html") return page()
        if (uri == "/api/config") {
            return when (method) {
                Method.GET -> json(Response.Status.OK, configStore.load().toJson())
                Method.POST, Method.PUT -> {
                    val body = readBody(session)
                    val merged = configStore.applyOverride(body)
                    onConfigChanged(merged)
                    json(Response.Status.OK, merged.toJson())
                }
                else -> json(Response.Status.METHOD_NOT_ALLOWED, JSONObject().put("error", "method"))
            }
        }
        if (uri == "/api/profiles") {
            return when (method) {
                Method.GET -> {
                    val all = LedProfiles.BUILT_IN + profileStore.load()
                    newFixedLengthResponse(
                        Response.Status.OK, "application/json",
                        LedProfiles.listToJson(all).toString()
                    )
                }
                Method.POST -> {
                    val p = LedProfile.fromJson(JSONObject(readBody(session)))
                        ?: return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "bad profile"))
                    profileStore.save(p.copy(builtIn = false))
                    json(Response.Status.OK, p.toJson())
                }
                else -> json(Response.Status.METHOD_NOT_ALLOWED, JSONObject().put("error", "method"))
            }
        }
        if (uri.startsWith("/api/profiles/") && method == Method.DELETE) {
            profileStore.delete(uri.removePrefix("/api/profiles/"))
            return json(Response.Status.OK, JSONObject().put("status", "OK"))
        }
        if (uri == "/api/status") return json(Response.Status.OK, statusJson())
        if (uri == "/api/storage") return json(Response.Status.OK, storageJson())
        if (uri == "/api/focus" && method == Method.POST) {
            onBringToFront()
            return json(Response.Status.OK, JSONObject().put("status", "OK"))
        }

        // --- enough of FPP's own API that xLights and a player recognise this device
        if (uri == "/api/system/info") return json(Response.Status.OK, identityJson())
        if (uri == "/api/fppd/status") return json(Response.Status.OK, statusJson())
        if (uri == "/api/files/sequences") {
            val arr = JSONArray()
            sequences.listCached().forEach {
                arr.put(JSONObject().put("name", it.name).put("size", it.length()))
            }
            return newFixedLengthResponse(
                Response.Status.OK, "application/json",
                JSONObject().put("status", "ok").put("files", arr).toString()
            )
        }
        if (uri.startsWith("/api/file/")) return fileApi(session, uri)

        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
    }

    /**
     * FPP's chunked upload, which is what FPP Connect and the player's file copy actually speak.
     *
     * `POST /api/file/{dir}` hands back an opaque token. The bytes then arrive as one or more
     * `PATCH` requests carrying `Upload-Name`, `Upload-Offset` and `Upload-Length`. FPP stages each
     * chunk as a separate `.patch.<offset>` file and stitches them at the end; writing straight into
     * the destination at the given offset gets the same result without the stitching, and a rename
     * on completion means a partial transfer is never visible as a playable sequence.
     */
    private fun fileApi(session: IHTTPSession, uri: String): Response {
        val rest = uri.removePrefix("/api/file/")
        val dir = rest.substringBefore('/')
        if (!dir.equals("sequences", ignoreCase = true)) {
            return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "only sequences are accepted"))
        }

        if (session.method == Method.POST) {
            // Token only; the payload follows as PATCH.
            return newFixedLengthResponse(
                Response.Status.OK, "text/plain",
                java.lang.Long.toHexString(System.nanoTime())
            )
        }

        if (session.method == Method.PATCH) {
            val headers = session.headers
            val name = SequenceStore.sanitize(headers["upload-name"] ?: "")
                ?: return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "bad upload name"))
            val offset = headers["upload-offset"]?.toLongOrNull() ?: 0L
            val total = headers["upload-length"]?.toLongOrNull() ?: -1L
            val length = headers["content-length"]?.toLongOrNull() ?: 0L

            val dirFile = app.fppvm.tv.fseq.SequenceStorage
                .writeDir(context, true).apply { mkdirs() }
            val part = File(dirFile, name + PART_SUFFIX)
            if (offset == 0L) part.delete()

            try {
                RandomAccessFile(part, "rw").use { raf ->
                    raf.seek(offset)
                    val input = session.inputStream
                    val buf = ByteArray(64 * 1024)
                    var remaining = length
                    while (remaining > 0) {
                        val n = input.read(buf, 0, minOf(remaining, buf.size.toLong()).toInt())
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        remaining -= n
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "upload chunk failed for $name", t)
                return json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", t.message ?: "write failed"))
            }

            val size = part.length()
            if (total > 0 && size >= total) {
                val target = File(dirFile, name)
                target.delete()
                if (!part.renameTo(target)) {
                    part.delete()
                    return json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", "commit failed"))
                }
                Log.i(TAG, "received $name ($size bytes) over the file API")
            }
            return json(
                Response.Status.OK,
                JSONObject().put("status", "OK").put("file", name).put("dir", "sequences").put("size", size)
            )
        }

        if (session.method == Method.GET) {
            val name = rest.substringAfter('/', "")
            val f = sequences.localFile(name)
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no such sequence")
            return newFixedLengthResponse(
                Response.Status.OK, "application/octet-stream", f.inputStream(), f.length()
            )
        }

        if (session.method == Method.DELETE) {
            val name = rest.substringAfter('/', "")
            sequences.delete(name)
            return json(Response.Status.OK, JSONObject().put("status", "OK"))
        }

        return json(Response.Status.METHOD_NOT_ALLOWED, JSONObject().put("error", "method"))
    }

    private fun readBody(session: IHTTPSession): String {
        val len = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (len <= 0) return "{}"
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = session.inputStream.read(buf, read, len - read)
            if (n < 0) break
            read += n
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    private fun json(status: Response.Status, body: JSONObject): Response =
        newFixedLengthResponse(status, "application/json", body.toString())

    private fun page(): Response {
        val html = try {
            context.assets.open("config.html").bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            "<h1>FPP Virtual Matrix</h1><p>config.html missing from assets</p>"
        }
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }
}

/** Persists user-created profiles alongside the built-in library. */
class ProfileStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("fppvm-profiles", Context.MODE_PRIVATE)

    fun load(): List<LedProfile> = LedProfiles.listFromJson(prefs.getString(KEY, "[]") ?: "[]")

    fun save(profile: LedProfile) {
        val existing = load().filter { it.id != profile.id }
        prefs.edit().putString(KEY, LedProfiles.listToJson(existing + profile).toString()).apply()
    }

    fun delete(id: String) {
        prefs.edit().putString(KEY, LedProfiles.listToJson(load().filter { it.id != id }).toString()).apply()
    }

    companion object {
        private const val KEY = "profiles"
    }
}
