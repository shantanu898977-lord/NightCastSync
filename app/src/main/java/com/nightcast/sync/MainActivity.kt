package com.nightcast.sync

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaPlayer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.*
import android.text.format.Formatter
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
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

    // Mirroring components
    private lateinit var projectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    @Volatile private var isMirroring = false
    @Volatile private var currentMode = MODE_OFFLINE_FILE
    private val mirrorSockets = CopyOnWriteArrayList<Socket>()

    private var audioOffsetMs: Long = 0
    private lateinit var prefs: SharedPreferences

    private lateinit var statusText: TextView
    private lateinit var ipText: TextView
    private lateinit var offsetValueText: TextView
    private lateinit var offsetSeekBar: SeekBar
    private lateinit var playPauseButton: Button
    private lateinit var offlineControlsLayout: LinearLayout
    private lateinit var mirrorControlsLayout: LinearLayout
    private lateinit var modeRadioGroup: RadioGroup
    private lateinit var mirrorStatusText: TextView

    companion object {
        const val MODE_OFFLINE_FILE = 0
        const val MODE_SCREEN_MIRROR = 1
        private const val REQUEST_MEDIA_PROJECTION = 2001
        private const val REQUEST_PICK_FILE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences("NightCastPrefs", Context.MODE_PRIVATE)
        audioOffsetMs = prefs.getLong("audio_offset_ms", 0L)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

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
            setPadding(dp(20), dp(20), dp(20), dp(20))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = "NightCast Sync"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        layout.addView(title)

        ipText = TextView(this).apply {
            val ip = getLocalIpAddress()
            text = "Open on Samsung TV:\nhttp://$ip:8080"
            setTextColor(Color.parseColor("#FFD54F"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(12), 0, dp(16)) }
            layoutParams = params
        }
        layout.addView(ipText)

        // Mode Selector
        val modeTitle = TextView(this).apply {
            text = "Choose Operating Mode:"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(6))
        }
        layout.addView(modeTitle)

        modeRadioGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
        }

        val radioOffline = RadioButton(this).apply {
            text = "Offline Movie"
            setTextColor(Color.WHITE)
            id = View.generateViewId()
            isChecked = true
        }
        val radioMirror = RadioButton(this).apply {
            text = "Live Mirror"
            setTextColor(Color.WHITE)
            id = View.generateViewId()
        }
        modeRadioGroup.addView(radioOffline)
        modeRadioGroup.addView(radioMirror)
        layout.addView(modeRadioGroup)

        // Section A: Offline File Controls
        offlineControlsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val selectBtn = Button(this).apply {
            text = "Select Movie File"
            setBackgroundColor(Color.parseColor("#1E88E5"))
            setTextColor(Color.WHITE)
            setOnClickListener { openFilePicker() }
        }
        offlineControlsLayout.addView(selectBtn)

        statusText = TextView(this).apply {
            text = "No movie selected"
            setTextColor(Color.parseColor("#80D8FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(8), 0, dp(8))
        }
        offlineControlsLayout.addView(statusText)

        playPauseButton = Button(this).apply {
            text = "Pause"
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#2E7D32"))
            setTextColor(Color.WHITE)
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
        offlineControlsLayout.addView(playPauseButton)

        val syncTitle = TextView(this).apply {
            text = "Earphone Audio Delay Offset"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(14), 0, dp(4))
        }
        offlineControlsLayout.addView(syncTitle)

        offsetValueText = TextView(this).apply {
            text = "$audioOffsetMs ms"
            setTextColor(Color.parseColor("#4CAF50"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
        }
        offlineControlsLayout.addView(offsetValueText)

        offsetSeekBar = SeekBar(this).apply {
            max = 6000
            progress = (audioOffsetMs + 3000).toInt()
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    if (fromUser) setOffset(prog - 3000L)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        offlineControlsLayout.addView(offsetSeekBar)

        val stepRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
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
        stepRow.addView(makeStepBtn("-50 ms", -50))
        stepRow.addView(makeStepBtn("+50 ms", 50))
        offlineControlsLayout.addView(stepRow)
        layout.addView(offlineControlsLayout)

        // Section B: Live Mirror Controls
        mirrorControlsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }

        val mirrorNotice = TextView(this).apply {
            text = "Displays full phone screen on TV.\nAudio stays in your wired earphones."
            setTextColor(Color.parseColor("#B0BEC5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        }
        mirrorControlsLayout.addView(mirrorNotice)

        val toggleMirrorBtn = Button(this).apply {
            text = "Start Screen Mirroring"
            setBackgroundColor(Color.parseColor("#C2185B"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                if (isMirroring) {
                    stopMirroring()
                    text = "Start Screen Mirroring"
                } else {
                    startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
                    text = "Stop Screen Mirroring"
                }
            }
        }
        mirrorControlsLayout.addView(toggleMirrorBtn)

        mirrorStatusText = TextView(this).apply {
            text = "Mirroring: Stopped"
            setTextColor(Color.GRAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(8), 0, dp(8))
        }
        mirrorControlsLayout.addView(mirrorStatusText)
        layout.addView(mirrorControlsLayout)

        modeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == radioOffline.id) {
                currentMode = MODE_OFFLINE_FILE
                offlineControlsLayout.visibility = View.VISIBLE
                mirrorControlsLayout.visibility = View.GONE
            } else {
                currentMode = MODE_SCREEN_MIRROR
                offlineControlsLayout.visibility = View.GONE
                mirrorControlsLayout.visibility = View.VISIBLE
            }
        }

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
        startActivityForResult(intent, REQUEST_PICK_FILE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_FILE && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            selectedVideoUri = uri
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            statusText.text = "Loaded: ${uri.lastPathSegment ?: "Movie ready"}"
            initLocalAudioPlayer(uri)
        } else if (requestCode == REQUEST_MEDIA_PROJECTION && resultCode == Activity.RESULT_OK) {
            val serviceIntent = Intent(this, ProjectionService::class.java).apply {
                putExtra("code", resultCode)
                putExtra("data", data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            mediaProjection = projectionManager.getMediaProjection(resultCode, data!!)
            startScreenCapture()
        }
    }

    private fun startScreenCapture() {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val width = 1280
        val height = (1280 * metrics.heightPixels / metrics.widthPixels) / 2 * 2

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "NightCastMirror",
            width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        isMirroring = true
        mirrorStatusText.text = "Mirroring: Active (Broadcasting to TV)"
        mirrorStatusText.setTextColor(Color.parseColor("#4CAF50"))

        imageReader?.setOnImageAvailableListener({ reader ->
            val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (mirrorSockets.isNotEmpty()) {
                    val planes = img.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * img.width

                    val bitmap = Bitmap.createBitmap(
                        img.width + rowPadding / pixelStride,
                        img.height,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)

                    val cropped = Bitmap.createBitmap(bitmap, 0, 0, img.width, img.height)
                    val baos = ByteArrayOutputStream()
                    cropped.compress(Bitmap.CompressFormat.JPEG, 60, baos)
                    val jpegBytes = baos.toByteArray()

                    for (sock in mirrorSockets) {
                        try {
                            val os = sock.getOutputStream()
                            os.write(("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + jpegBytes.size + "\r\n\r\n").toByteArray())
                            os.write(jpegBytes)
                            os.write("\r\n".toByteArray())
                            os.flush()
                        } catch (_: Exception) {
                            mirrorSockets.remove(sock)
                            try { sock.close() } catch (_: Exception) {}
                        }
                    }
                    if (cropped != bitmap) cropped.recycle()
                    bitmap.recycle()
                }
            } catch (_: Exception) {
            } finally {
                img.close()
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun stopMirroring() {
        isMirroring = false
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        stopService(Intent(this, ProjectionService::class.java))
        mirrorStatusText.text = "Mirroring: Stopped"
        mirrorStatusText.setTextColor(Color.GRAY)
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
                if (currentMode == MODE_OFFLINE_FILE && player != null && tvPlaybackTimeMs > 0) {
                    val targetMs = (tvPlaybackTimeMs - audioOffsetMs).coerceAtLeast(0)
                    val currentAudioMs = player.currentPosition.toLong()
                    val diff = abs(currentAudioMs - targetMs)
                    if (diff > 80) player.seekTo(targetMs.toInt())
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
                path == "/" -> servePortalPage(socket)
                path.startsWith("/video") -> serveVideoFile(socket, rangeHeader)
                path.startsWith("/sync") -> handleSyncEndpoint(socket, path)
                path.startsWith("/stream") -> {
                    val out = socket.getOutputStream()
                    out.write(("HTTP/1.1 200 OK\r\n" +
                            "Content-Type: multipart/x-mixed-replace; boundary=--frame\r\n" +
                            "Cache-Control: no-cache, private\r\n" +
                            "Connection: close\r\n\r\n").toByteArray())
                    out.flush()
                    mirrorSockets.add(socket)
                }
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

    private fun servePortalPage(socket: Socket) {
        val html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>NightCast TV</title>
              <style>
                body, html { margin:0; padding:0; width:100%; height:100%; background:#000; overflow:hidden; font-family:sans-serif; text-align:center; }
                video, img { width:100%; height:100%; object-fit:contain; }
                .nav { position:fixed; top:10px; right:10px; z-index:99; background:rgba(0,0,0,0.6); padding:8px 12px; border-radius:6px; }
                .nav a { color:#fff; text-decoration:none; margin:0 8px; font-weight:bold; }
              </style>
            </head>
            <body>
              <div class="nav">
                <a href="/?view=file">Offline Movie</a> | 
                <a href="/?view=mirror">Live Screen Mirror</a>
              </div>
              <script>
                const p = new URLSearchParams(window.location.search);
                const view = p.get('view') || (document.referrer.includes('mirror') ? 'mirror' : 'file');
                if (view === 'mirror') {
                  document.write('<img src="/stream" />');
                } else {
                  document.write('<video id="v" src="/video" autoplay muted playsinline controls></video>');
                  setInterval(() => {
                    const v = document.getElementById('v');
                    if (!v) return;
                    fetch('/sync?time=' + Math.floor(v.currentTime * 1000) + '&playing=' + (!v.paused && !v.ended))
                      .then(r => r.text())
                      .then(cmd => {
                        if (cmd === 'PAUSE' && !v.paused) v.pause();
                        if (cmd === 'PLAY' && v.paused) v.play();
                      }).catch(()=>{});
                  }, 300);
                }
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
        var remaining = contentLength
        while (remaining > 0) {
            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
            val read = fis.read(buffer, 0, toRead)
            if (read == -1) break
            out.write(buffer, 0, read)
            remaining -= read
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

        val resp = pendingTvCommand ?: "OK"
        pendingTvCommand = null
        val bytes = resp.toByteArray(Charsets.UTF_8)
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        isServerRunning = false
        stopMirroring()
        try { serverSocket?.close() } catch (_: Exception) {}
        mediaPlayer?.release()
        mediaPlayer = null
    }
}

class ProjectionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "NightCastProjectionChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Screen Mirroring Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("NightCast Mirroring Active")
            .setContentText("Broadcasting screen to Samsung TV")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()

        startForeground(101, notification)
        return START_NOT_STICKY
    }
}
