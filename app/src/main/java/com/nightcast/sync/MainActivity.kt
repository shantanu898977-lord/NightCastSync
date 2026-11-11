package com.nightcast.sync

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private var selectedVideoUri: Uri? = null
    private var mediaPlayer: MediaPlayer? = null
    private var serverSocket: ServerSocket? = null
    private val threadPool = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var isServerRunning = false
    @Volatile private var tvPlaybackTimeMs: Long = 0
    @Volatile private var tvIsPlaying: Boolean = false
    @Volatile private var pendingTvCommand: String? = null

    private var audioOffsetMs: Long = 0
    private lateinit var prefs: SharedPreferences

    private lateinit var statusText: TextView
    private lateinit var ipText: TextView
    private lateinit var offsetValueText: TextView
    private lateinit var offsetSeekBar: SeekBar
    private lateinit var playPauseButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences("NightCastPrefs", Context.MODE_PRIVATE)
        audioOffsetMs = prefs.getLong("audio_offset_ms", 0L)

        buildUi()
        startHttpServer()
        startSyncLoop()
    }

    private fun buildUi() {
        val root = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#121212"))
            isFillViewport = true
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = "NightCast Sync"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        layout.addView(title)

        val subtitle = TextView(this).apply {
            text = "Silent TV Visuals • Wired Earphone Audio"
            setTextColor(Color.parseColor("#A0A0A0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(24))
        }
        layout.addView(subtitle)

        val selectBtn = Button(this).apply {
            text = "Select Movie File"
            setBackgroundColor(Color.parseColor("#1E88E5"))
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { openFilePicker() }
        }
        layout.addView(selectBtn)

        statusText = TextView(this).apply {
            text = "No movie selected"
            setTextColor(Color.parseColor("#80D8FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(8))
        }
        layout.addView(statusText)

        ipText = TextView(this).apply {
            val ip = getLocalIpAddress()
            text = "1. Open Samsung TV Browser\n2. Go to: http://$ip:8080"
            setTextColor(Color.parseColor("#FFD54F"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        layout.addView(ipText)

        playPauseButton = Button(this).apply {
            text = "Pause"
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#2E7D32"))
            setTextColor(Color.WHITE)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(16), 0, dp(16)) }
            layoutParams = params
            setOnClickListener {
                if (mediaPlayer?.isPlaying == true) {
                    mediaPlayer?.pause()
                    pendingTvCommand = "PAUSE"
                    text = "Play"
                } else {
                    mediaPlayer?.start()
                    pendingTvCommand = "PLAY"
                    text = "Pause"
                }
            }
        }
        layout.addView(playPauseButton)

        val syncTitle = TextView(this).apply {
            text = "Audio Sync Offset (Delay Slider)"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(24), 0, dp(8))
        }
        layout.addView(syncTitle)

        offsetValueText = TextView(this).apply {
            text = "$audioOffsetMs ms"
            setTextColor(Color.parseColor("#4CAF50"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        layout.addView(offsetValueText)

        offsetSeekBar = SeekBar(this).apply {
            max = 6000
            progress = (audioOffsetMs + 3000).toInt()
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    if (fromUser) {
                        setOffset(prog - 3000L)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        layout.addView(offsetSeekBar)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(16))
        }

        fun makeStepBtn(label: String, stepMs: Long): Button {
            return Button(this).apply {
                text = label
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#263238"))
                setOnClickListener {
                    val next = (audioOffsetMs + stepMs).coerceIn(-3000L, 3000L)
                    setOffset(next)
                    offsetSeekBar.progress = (next + 3000).toInt()
                }
            }
        }

        buttonRow.addView(makeStepBtn("-250 ms", -250))
        buttonRow.addView(makeStepBtn("-50 ms", -50))
        buttonRow.addView(makeStepBtn("+50 ms", 50))
        buttonRow.addView(makeStepBtn("+250 ms", 250))
        layout.addView(buttonRow)

        val resyncBtn = Button(this).apply {
            text = "Force Instant Resync"
            setBackgroundColor(Color.parseColor("#37474F"))
            setTextColor(Color.WHITE)
            setOnClickListener { forceSyncNow() }
        }
        layout.addView(resyncBtn)

        root.addView(layout)
        setContentView(root)
    }

    private fun setOffset(ms: Long) {
        audioOffsetMs = ms
        offsetValueText.text = "$ms ms"
        prefs.edit().putLong("audio_offset_ms", ms).apply()
        forceSyncNow()
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }
        startActivityForResult(intent, 1001)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            selectedVideoUri = uri
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}

            statusText.text = "Loaded: ${uri.lastPathSegment ?: "Movie ready"}"
            initLocalAudioPlayer(uri)
        }
    }

    private fun initLocalAudioPlayer(uri: Uri) {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(this@MainActivity, uri)
            isLooping = false
            prepare()
        }
        playPauseButton.visibility = View.VISIBLE
        playPauseButton.text = "Pause"
    }

    private fun forceSyncNow() {
        val player = mediaPlayer ?: return
        if (tvPlaybackTimeMs <= 0) return

        val targetAudioMs = (tvPlaybackTimeMs - audioOffsetMs).coerceAtLeast(0)
        player.seekTo(targetAudioMs.toInt())
        if (tvIsPlaying && !player.isPlaying) {
            player.start()
            playPauseButton.text = "Pause"
        }
    }

    private fun startSyncLoop() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                val player = mediaPlayer
                if (player != null && tvPlaybackTimeMs > 0) {
                    val targetMs = (tvPlaybackTimeMs - audioOffsetMs).coerceAtLeast(0)
                    val currentAudioMs = player.currentPosition.toLong()
                    val diff = abs(currentAudioMs - targetMs)

                    if (diff > 80) {
                        player.seekTo(targetMs.toInt())
                    }

                    if (tvIsPlaying && !player.isPlaying) {
                        player.start()
                        playPauseButton.text = "Pause"
                    } else if (!tvIsPlaying && player.isPlaying) {
                        player.pause()
                        playPauseButton.text = "Play"
                    }
                }
                mainHandler.postDelayed(this, 300)
            }
        }, 300)
    }

    private fun startHttpServer() {
        isServerRunning = true
        threadPool.execute {
            try {
                serverSocket = ServerSocket(8080)
                while (isServerRunning) {
                    val client = serverSocket?.accept() ?: break
                    threadPool.execute { handleHttpRequest(client) }
                }
            } catch (_: Exception) {}
        }
    }

    private fun handleHttpRequest(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val firstLine = reader.readLine() ?: return socket.close()
            val tokens = firstLine.split(" ")
            if (tokens.size < 2) return socket.close()

            val method = tokens[0]
            val path = tokens[1]

            var rangeHeader: String? = null
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrEmpty()) break
                if (line!!.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line!!.substring(6).trim()
                }
            }

            when {
                path == "/" -> serveTvWebPlayer(socket)
                path.startsWith("/video") -> serveVideoFile(socket, rangeHeader)
                path.startsWith("/sync") -> handleSyncEndpoint(socket, path)
                else -> {
                    val out = PrintWriter(socket.getOutputStream(), true)
                    out.println("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n")
                    socket.close()
                }
            }
        } catch (_: Exception) {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun serveTvWebPlayer(socket: Socket) {
        val html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <title>NightCast TV Player</title>
              <style>
                body, html { margin:0; padding:0; width:100%; height:100%; background:#000; overflow:hidden; }
                video { width:100%; height:100%; object-fit:contain; }
              </style>
            </head>
            <body>
              <video id="v" src="/video" autoplay muted playsinline controls></video>
              <script>
                const v = document.getElementById('v');
                v.muted = true;

                setInterval(() => {
                  const ms = Math.floor(v.currentTime * 1000);
                  const isPlaying = (!v.paused && !v.ended);
                  fetch('/sync?time=' + ms + '&playing=' + isPlaying)
                    .then(r => r.text())
                    .then(cmd => {
                      if (cmd === 'PAUSE' && !v.paused) v.pause();
                      if (cmd === 'PLAY' && v.paused) v.play();
                    })
                    .catch(() => {});
                }, 300);
              </script>
            </body>
            </html>
        """.trimIndent()

        val bytes = html.toByteArray(Charsets.UTF_8)
        val out = socket.getOutputStream()
        val writer = PrintWriter(out, false)
        writer.print("HTTP/1.1 200 OK\r\n")
        writer.print("Content-Type: text/html; charset=UTF-8\r\n")
        writer.print("Content-Length: ${bytes.size}\r\n")
        writer.print("Connection: close\r\n\r\n")
        writer.flush()
        out.write(bytes)
        out.flush()
        socket.close()
    }

    private fun serveVideoFile(socket: Socket, rangeHeader: String?) {
        val uri = selectedVideoUri ?: return socket.close()
        val pfd = contentResolver.openFileDescriptor(uri, "r") ?: return socket.close()
        val totalLength = pfd.statSize

        var start: Long = 0
        var end: Long = totalLength - 1

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val parts = rangeHeader.removePrefix("bytes=").split("-")
            start = parts[0].toLongOrNull() ?: 0
            if (parts.size > 1 && parts[1].isNotEmpty()) {
                end = parts[1].toLongOrNull() ?: (totalLength - 1)
            }
        }
        val contentLength = end - start + 1

        val out = socket.getOutputStream()
        val writer = PrintWriter(out, false)
        if (rangeHeader != null) {
            writer.print("HTTP/1.1 206 Partial Content\r\n")
            writer.print("Content-Range: bytes $start-$end/$totalLength\r\n")
        } else {
            writer.print("HTTP/1.1 200 OK\r\n")
        }
        writer.print("Content-Type: video/mp4\r\n")
        writer.print("Accept-Ranges: bytes\r\n")
        writer.print("Content-Length: $contentLength\r\n")
        writer.print("Connection: close\r\n\r\n")
        writer.flush()

        val fis = FileInputStream(pfd.fileDescriptor)
        fis.channel.position(start)

        val buffer = ByteArray(64 * 1024)
        var bytesRemaining = contentLength
        while (bytesRemaining > 0) {
            val toRead = minOf(buffer.size.toLong(), bytesRemaining).toInt()
            val read = fis.read(buffer, 0, toRead)
            if (read == -1) break
            out.write(buffer, 0, read)
            bytesRemaining -= read
        }
        out.flush()
        fis.close()
        pfd.close()
        socket.close()
    }

    private fun handleSyncEndpoint(socket: Socket, path: String) {
        val query = path.substringAfter("?", "")
        val params = query.split("&").associate {
            val kv = it.split("=")
            if (kv.size == 2) kv[0] to kv[1] else "" to ""
        }

        params["time"]?.toLongOrNull()?.let { tvPlaybackTimeMs = it }
        params["playing"]?.let { tvIsPlaying = (it == "true") }

        val response = pendingTvCommand ?: "OK"
        pendingTvCommand = null

        val bytes = response.toByteArray(Charsets.UTF_8)
        val out = socket.getOutputStream()
        val writer = PrintWriter(out, false)
        writer.print("HTTP/1.1 200 OK\r\n")
        writer.print("Content-Type: text/plain\r\n")
        writer.print("Content-Length: ${bytes.size}\r\n")
        writer.print("Connection: close\r\n\r\n")
        writer.flush()
        out.write(bytes)
        out.flush()
        socket.close()
    }

    private fun getLocalIpAddress(): String {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return Formatter.formatIpAddress(wm.connectionInfo.ipAddress) ?: "192.168.x.x"
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        isServerRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
