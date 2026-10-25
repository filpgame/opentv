/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.pairing

import android.content.Context
import android.util.Log
import app.opentv.R
import app.opentv.data.model.Source
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Lets someone fill in their provider details from a phone instead of a TV remote.
 *
 * ## Why the TV is the server
 *
 * The obvious way to build "scan a code, type on your phone, it appears on the telly" is a
 * website: the TV registers a code with a server, the phone posts to the same server, the TV
 * polls for it. Every streaming app does it that way.
 *
 * It is the wrong choice here. That server has to be paid for every month forever, and it
 * would hold IPTV credentials for every user of the app. It is precisely the dependency that
 * means an app dies when its maintainer loses interest — which is the failure this whole
 * project exists in response to.
 *
 * So there is no server. The TV runs one, on the local network, for the couple of minutes the
 * pairing screen is open. Credentials go phone → TV over the user's own wifi and never leave
 * the building. It works with the internet unplugged.
 *
 * ## What stops the neighbours using it
 *
 * - It binds to the LAN on a **random high port**, and only while the pairing screen is open.
 * - The URL carries a **192-bit random token**. Knowing the IP and port is not enough.
 * - The phone must also enter a **six-digit code shown on the TV**. That is the part the user
 *   sees, and it is what defends against someone who somehow obtained the link.
 * - Requests from non-private addresses are refused outright.
 * - After [SESSION_TIMEOUT_MINUTES], or on success, the socket closes and the token is void.
 * - Five wrong codes and the session is burned.
 */
class PairingServer(
    private val scope: CoroutineScope,
    /** Where the page's strings come from; it is in the phone's language, not the TV's. */
    private val context: Context,
) {

    data class Session(
        /** What the QR code encodes, e.g. `http://192.168.0.14:41234/?t=…` */
        val url: String,
        /** Shown on the TV, typed on the phone. */
        val confirmCode: String,
        val host: String,
        val port: Int,
    ) {
        /** The address to read out if the QR will not scan. */
        val shortUrl: String get() = "$host:$port"
    }

    sealed interface State {
        data object Idle : State
        data class Listening(val session: Session) : State
        /** The phone submitted details. The UI takes it from here. */
        data class Received(val draft: Source) : State
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var token: String = ""
    private var confirmCode: String = ""
    private var wrongCodeAttempts = 0
    @Volatile private var running = false

    fun start() {
        if (running) return
        val host = LanAddress.find(context)
        if (host == null) {
            _state.value = State.Failed(
                "This device doesn't appear to be on a network, so it can't talk to your phone.",
            )
            return
        }

        val random = SecureRandom()
        token = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        confirmCode = "%06d".format(random.nextInt(1_000_000))
        wrongCodeAttempts = 0

        val socket = try {
            // Port 0 asks the OS for a free one. A fixed port would be predictable and would
            // clash with whatever else the user runs at home.
            ServerSocket(0)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open a socket", e)
            _state.value = State.Failed("Could not start the pairing server on this device.")
            return
        }

        serverSocket = socket
        running = true

        val session = Session(
            url = "http://$host:${socket.localPort}/?t=$token",
            confirmCode = confirmCode,
            host = host,
            port = socket.localPort,
        )
        _state.value = State.Listening(session)

        // A plain thread rather than a coroutine: ServerSocket.accept() blocks
        // uninterruptibly, and the only reliable way to stop it is to close the socket.
        thread(name = "opentv-pairing", isDaemon = true) {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break // socket closed, which is how we stop
                }
                runCatching { handle(client) }
                    .onFailure { Log.w(TAG, "Pairing request failed", it) }
                runCatching { client.close() }
            }
        }

        scope.launch(Dispatchers.Default) {
            kotlinx.coroutines.delay(TimeUnit.MINUTES.toMillis(SESSION_TIMEOUT_MINUTES))
            if (running && _state.value is State.Listening) {
                stop()
                _state.value = State.Failed("Pairing timed out. Start again when you're ready.")
            }
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        token = ""
        confirmCode = ""
        if (_state.value is State.Listening) _state.value = State.Idle
    }

    // ---- Request handling ------------------------------------------------------------------

    private fun handle(client: Socket) {
        client.soTimeout = SOCKET_TIMEOUT_MILLIS

        if (!PairingHttp.isPrivateAddress(client.inetAddress?.hostAddress)) {
            client.getOutputStream().write(
                PairingHttp.httpResponse("403 Forbidden", "text/plain", "Local network only."),
            )
            return
        }

        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        val (method, path, query) = PairingHttp.parseRequestLine(requestLine) ?: return

        val headers = HashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            PairingHttp.parseHeader(line)?.let { (name, value) -> headers[name] = value }
        }

        val contentLength = (headers["content-length"]?.toIntOrNull() ?: 0)
            .coerceAtMost(MAX_BODY_BYTES)
        val body = if (contentLength > 0) {
            val buffer = CharArray(contentLength)
            val read = reader.read(buffer, 0, contentLength)
            if (read > 0) String(buffer, 0, read) else ""
        } else {
            ""
        }

        val output = client.getOutputStream()
        val text = WebText.forRequest(context, headers["accept-language"])

        // Every route checks the token. Without it the page does not exist.
        val supplied = query["t"] ?: PairingHttp.parseFormEncoded(body)["t"] ?: ""
        if (token.isEmpty() || !PairingHttp.secretsMatch(supplied, token)) {
            output.write(PairingHttp.httpResponse("404 Not Found", "text/plain", "Not found."))
            return
        }

        when {
            method == "GET" -> output.write(
                PairingHttp.httpResponse("200 OK", "text/html", PairingPage.form(token, null, text)),
            )

            method == "POST" && path.startsWith("/submit") -> handleSubmit(output, body, text)

            else -> output.write(
                PairingHttp.httpResponse("405 Method Not Allowed", "text/plain", "No."),
            )
        }
    }

    private fun handleSubmit(output: java.io.OutputStream, body: String, text: WebText) {
        val form = PairingHttp.parseFormEncoded(body)

        val code = form["code"].orEmpty().filter { it.isDigit() }
        if (!PairingHttp.secretsMatch(code, confirmCode)) {
            wrongCodeAttempts++
            if (wrongCodeAttempts >= MAX_CODE_ATTEMPTS) {
                output.write(
                    PairingHttp.httpResponse(
                        "403 Forbidden",
                        "text/html",
                        PairingPage.message(
                            text.get(R.string.pairpage_too_many_title),
                            text.get(R.string.pairpage_too_many_body),
                            text,
                        ),
                    ),
                )
                stop()
                _state.value = State.Failed("Too many incorrect codes. Pairing cancelled.")
                return
            }
            output.write(
                PairingHttp.httpResponse(
                    "200 OK",
                    "text/html",
                    PairingPage.form(
                        token,
                        text.get(R.string.pairpage_err_code, MAX_CODE_ATTEMPTS - wrongCodeAttempts),
                        text,
                    ),
                ),
            )
            return
        }

        // The default name is shown on this device, so it comes from this device's language, not
        // from the phone's [text].
        val draft = when (
            val result = PairingHttp.sourceFromForm(
                form,
                providerName = context.getString(R.string.onboarding_default_provider_name),
                playlistName = context.getString(R.string.onboarding_default_playlist_name),
            )
        ) {
            is PairingHttp.FormResult.Ok -> result.source
            else -> {
                val error = if (result is PairingHttp.FormResult.MissingMac) {
                    text.get(R.string.webpage_mac_required)
                } else {
                    text.get(R.string.pairpage_err_url)
                }
                output.write(
                    PairingHttp.httpResponse("200 OK", "text/html", PairingPage.form(token, error, text)),
                )
                return
            }
        }

        output.write(
            PairingHttp.httpResponse(
                "200 OK",
                "text/html",
                PairingPage.message(
                    text.get(R.string.pairpage_sent_title),
                    text.get(R.string.pairpage_sent_body),
                    text,
                ),
            ),
        )
        output.flush()

        _state.value = State.Received(draft)
        // Nothing more to receive. Shut the door immediately rather than leaving a socket
        // open on the user's network for the rest of the timeout.
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        token = ""
    }

    private companion object {
        const val TAG = "PairingServer"
        const val SESSION_TIMEOUT_MINUTES = 5L
        const val SOCKET_TIMEOUT_MILLIS = 15_000
        const val MAX_BODY_BYTES = 16 * 1024
        const val MAX_CODE_ATTEMPTS = 5
    }
}
