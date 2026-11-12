package com.nightcast.sync

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaPlayer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.format.Formatter
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private var selectedVideoUri: Uri? = null
    private var mediaPlayer: MediaPlayer? = null
    private var httpServer: ServerSocket? = null
    private var wsServer: StreamWebSocketServer? = null
    private val threadPool = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var isServerRunning = false
    @Volatile private var tvPlaybackTimeMs: Long = 0
    @Volatile private var tvIsPlaying: Boolean = false
    @Volatile private var pendingTvCommand: String? = null

    // Hardware Encoder Pipeline (Capped at 1080p 60FPS for Snapdragon 695)
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var displayManager: DisplayManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaCodec: MediaCodec? = null
    private var encoderSurface: Surface? = null
    @Volatile private var isMirroring = false
    @Volatile private var currentMode = MODE_OFFLINE_FILE
    private var lastIsLandscape: Boolean? = null

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
    private lateinit var toggleMirrorBtn: Button

    companion object {
        const val MODE_OFFLINE_FILE = 0
        const val MODE_SCREEN_MIRROR = 1
        private const val REQUEST_MEDIA_PROJECTION = 2001
        private const val REQUEST_PICK_FILE = 1001
        const val HTTP_PORT = 8080
        const val WS_PORT = 8088

        private const val MAX_LONG_SIDE = 1920
        private const val MAX_SHORT_SIDE = 1080
        private const val MAX_FPS = 60
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            mainHandler.post {
                stopMirroring()
            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (!isMirroring) return
            val metrics = getRealScreenMetrics()
            val nowLandscape = metrics.widthPixels >= metrics.heightPixels
            if (lastIsLandscape != null && lastIsLandscape != nowLandscape) {
                mainHandler.post { restartEncoderForOrientation() }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences("NightCastPrefs", Context.MODE_PRIVATE)
        audioOffsetMs = prefs.getLong("audio_offset_ms", 0L)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, mainHandler)

        buildUi()
        startHttpServer()
        startWebSocketServer()
        startSyncLoop()
    }

    private fun buildUi() {
        val root = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#121212"))
            isFillViewport = true
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = "Movie Cast"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        layout.addView(title)

        ipText = TextView(this).apply {
            val ip = getLocalIpAddress()
            text = "Open on Samsung TV:\nhttp://$ip:$HTTP_PORT"
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
            text = "Live Mirror (Up to 1080p 60FPS)"
            setTextColor(Color.WHITE)
            id = View.generateViewId()
        }
        modeRadioGroup.addView(radioOffline)
        modeRadioGroup.addView(radioMirror)
        layout.addView(modeRadioGroup)

        // Offline Movie Controls
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

        // Live Mirror Controls
        mirrorControlsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }

        val mirrorNotice = TextView(this).apply {
            text = "Hardware H.264 Stream (Max 1080p @ 60FPS).\nSupports anything up to 1080p60.\nAudio stays in your wired earphones."
            setTextColor(Color.parseColor("#B0BEC5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        }
        mirrorControlsLayout.addView(mirrorNotice)

        toggleMirrorBtn = Button(this).apply {
            text = "Start Screen Mirroring"
            setBackgroundColor(Color.parseColor("#C2185B"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                if (isMirroring) {
                    stopMirroring()
                } else {
                    startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
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

    @Suppress("DEPRECATION")
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
        } else if (requestCode == REQUEST_MEDIA_PROJECTION && resultCode == Activity.RESULT_OK && data != null) {
            mirrorStatusText.text = "Starting foreground service..."
            mirrorStatusText.setTextColor(Color.parseColor("#FFD54F"))

            // Wait until ProjectionService confirms startForeground() before calling getMediaProjection()
            ProjectionService.onForegroundReady = {
                mainHandler.post {
                    try {
                        val projection = projectionManager.getMediaProjection(resultCode, data)
                        if (projection != null) {
                            projection.registerCallback(projectionCallback, mainHandler)
                            mediaProjection = projection
                            startHardwareMirroring()
                        } else {
                            mirrorStatusText.text = "Projection permission unavailable"
                            mirrorStatusText.setTextColor(Color.RED)
                        }
                    } catch (e: Exception) {
                        mirrorStatusText.text = "Projection error: ${e.message}"
                        mirrorStatusText.setTextColor(Color.RED)
                        stopMirroring()
                    }
                }
            }

            val serviceIntent = Intent(this, ProjectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getRealScreenMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    @Suppress("DEPRECATION")
    private fun getCappedFps(): Int {
        val refreshRate = windowManager.defaultDisplay?.refreshRate?.toInt() ?: 60
        return refreshRate.coerceIn(24, MAX_FPS)
    }

    private fun computeCappedDimensions(metrics: DisplayMetrics): Pair<Int, Int> {
        val rawW = metrics.widthPixels
        val rawH = metrics.heightPixels
        val isLandscape = rawW >= rawH
        lastIsLandscape = isLandscape

        val maxW = if (isLandscape) MAX_LONG_SIDE else MAX_SHORT_SIDE
        val maxH = if (isLandscape) MAX_SHORT_SIDE else MAX_LONG_SIDE

        val scale = minOf(1.0f, maxW.toFloat() / rawW.toFloat(), maxH.toFloat() / rawH.toFloat())
        val cappedW = (((rawW * scale).toInt() / 2) * 2).coerceAtLeast(320)
        val cappedH = (((rawH * scale).toInt() / 2) * 2).coerceAtLeast(240)
        return Pair(cappedW, cappedH)
    }

    private fun startHardwareMirroring() {
        val metrics = getRealScreenMetrics()
        val (width, height) = computeCappedDimensions(metrics)
        val fps = getCappedFps()
        val pixelRatio = (width * height).toFloat() / (1920f * 1080f)
        val bitrate = (8_000_000f * pixelRatio).toInt().coerceIn(2_000_000, 8_000_000)

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        try {
            wsServer?.clearStreamCache()
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()

            mediaCodec = codec
            encoderSurface = surface

            if (virtualDisplay == null) {
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "MovieCastHwMirror",
                    width, height, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface, null, null
                )
            } else {
                virtualDisplay?.resize(width, height, metrics.densityDpi)
                virtualDisplay?.surface = surface
            }

            isMirroring = true
            toggleMirrorBtn.text = "Stop Screen Mirroring"
            mirrorStatusText.text = "Mirroring: Active (${width}x${height} @ ${fps}FPS)"
            mirrorStatusText.setTextColor(Color.parseColor("#4CAF50"))

            threadPool.execute { drainEncoder(codec, width, height, fps) }

        } catch (e: Exception) {
            mirrorStatusText.text = "Encoder error: ${e.message}"
            mirrorStatusText.setTextColor(Color.RED)
            stopMirroring()
        }
    }

    private fun restartEncoderForOrientation() {
        if (!isMirroring) return
        isMirroring = false
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null
            encoderSurface?.release()
            encoderSurface = null
        } catch (_: Exception) {}
        startHardwareMirroring()
    }

    private fun drainEncoder(codec: MediaCodec, width: Int, height: Int, fps: Int) {
        val bufferInfo = MediaCodec.BufferInfo()
        val muxer = Fmp4StreamMuxer(width, height, fps)

        while (isMirroring && mediaCodec === codec) {
            try {
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val rawData = ByteArray(bufferInfo.size)
                        outputBuffer.get(rawData)

                        val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                        if (isConfig) {
                            val initResult = muxer.processCodecConfig(rawData)
                            if (initResult != null) {
                                wsServer?.setInitSegment(initResult.first, initResult.second)
                            }
                        } else {
                            if (!muxer.isInitialized && isKeyFrame) {
                                val initResult = muxer.processCodecConfig(rawData)
                                if (initResult != null) {
                                    wsServer?.setInitSegment(initResult.first, initResult.second)
                                }
                            }
                            if (muxer.isInitialized) {
                                val fragment = muxer.wrapFrame(rawData, bufferInfo.presentationTimeUs, isKeyFrame)
                                if (fragment != null) {
                                    wsServer?.broadcastData(fragment)
                                }
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            } catch (_: Exception) {
                break
            }
        }
    }

    private fun stopMirroring() {
        isMirroring = false
        ProjectionService.onForegroundReady = null
        wsServer?.clearStreamCache()
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null
            encoderSurface?.release()
            encoderSurface = null
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
            mediaProjection = null
            stopService(Intent(this, ProjectionService::class.java))
        } catch (_: Exception) {}

        if (::toggleMirrorBtn.isInitialized) {
            toggleMirrorBtn.text = "Start Screen Mirroring"
        }
        if (::mirrorStatusText.isInitialized) {
            mirrorStatusText.text = "Mirroring: Stopped"
            mirrorStatusText.setTextColor(Color.GRAY)
        }
    }

    private fun startWebSocketServer() {
        threadPool.execute {
            try {
                wsServer = StreamWebSocketServer(InetSocketAddress(WS_PORT)).apply {
                    isReuseAddr = true
                    start()
                }
            } catch (_: Exception) {}
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
                httpServer = ServerSocket(HTTP_PORT)
                while (isServerRunning) {
                    val client = httpServer?.accept() ?: break
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
                path == "/" || path.startsWith("/?view=") -> servePortalPage(socket)
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

    private fun servePortalPage(socket: Socket) {
        val ip = getLocalIpAddress()
        val html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>Movie Cast</title>
              <style>
                body, html { margin:0; padding:0; width:100%; height:100%; background:#000; overflow:hidden; font-family:sans-serif; text-align:center; }
                video { width:100%; height:100%; object-fit:contain; }
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
                  document.write('<video id="v" autoplay playsinline muted></video>');
                  const v = document.getElementById('v');
                  const ms = new MediaSource();
                  v.src = URL.createObjectURL(ms);
                  let sb = null;
                  let pendingMime = null;
                  const queue = [];

                  function flushQueue() {
                    if (!sb || sb.updating || queue.length === 0) return;
                    try {
                      sb.appendBuffer(queue.shift());
                      if (v.buffered.length > 0) {
                        const end = v.buffered.end(v.buffered.length - 1);
                        if (end - v.currentTime > 0.35) v.currentTime = end - 0.05;
                        if (v.currentTime > 15 && !sb.updating) {
                          sb.remove(0, v.currentTime - 5);
                        }
                      }
                    } catch (e) {}
                  }

                  function initBuffer(mime) {
                    if (sb || ms.readyState !== 'open') return;
                    sb = ms.addSourceBuffer(mime);
                    sb.mode = 'segments';
                    sb.addEventListener('updateend', flushQueue);
                    flushQueue();
                  }

                  ms.addEventListener('sourceopen', () => {
                    if (pendingMime) initBuffer(pendingMime);
                  });

                  function connectWs() {
                    const ws = new WebSocket('ws://$ip:$WS_PORT');
                    ws.binaryType = 'arraybuffer';
                    ws.onmessage = (e) => {
                      if (typeof e.data === 'string') {
                        if (e.data.startsWith('MIME:')) {
                          const mime = e.data.substring(5);
                          if (!sb) {
                            pendingMime = mime;
                            initBuffer(mime);
                          }
                        } else if (e.data === 'RESET') {
                          location.reload();
                        }
                      } else {
                        if (queue.length > 30) queue.shift();
                        queue.push(e.data);
                        flushQueue();
                      }
                    };
                    ws.onclose = () => setTimeout(connectWs, 1000);
                  }
                  connectWs();
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

    @Suppress("DEPRECATION")
    private fun getLocalIpAddress(): String {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return Formatter.formatIpAddress(wm.connectionInfo.ipAddress) ?: "192.168.x.x"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        displayManager.unregisterDisplayListener(displayListener)
        isServerRunning = false
        stopMirroring()
        try { httpServer?.close() } catch (_: Exception) {}
        try { wsServer?.stop() } catch (_: Exception) {}
        mediaPlayer?.release()
        mediaPlayer = null
    }
}

class ProjectionService : Service() {

    companion object {
        @Volatile var onForegroundReady: (() -> Unit)? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "screen_mirror_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Screen Mirroring Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Screen Mirroring Active")
            .setContentText("Streaming screen to TV...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(1, notification)
            }
            val callback = onForegroundReady
            onForegroundReady = null
            callback?.invoke()
        } catch (_: Exception) {
            stopSelf()
        }

        return START_NOT_STICKY
    }
}

class StreamWebSocketServer(address: InetSocketAddress) : WebSocketServer(address) {
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    @Volatile private var cachedMime: String? = null
    @Volatile private var cachedInitSegment: ByteArray? = null

    override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
        if (conn != null) {
            sockets.add(conn)
            val mime = cachedMime
            val init = cachedInitSegment
            if (mime != null && init != null) {
                try {
                    conn.send("MIME:$mime")
                    conn.send(init)
                } catch (_: Exception) {}
            }
        }
    }

    override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
        if (conn != null) sockets.remove(conn)
    }

    override fun onMessage(conn: WebSocket?, message: String?) {}
    override fun onError(conn: WebSocket?, ex: Exception?) {}
    override fun onStart() {}

    fun clearStreamCache() {
        val hadStream = cachedInitSegment != null
        cachedMime = null
        cachedInitSegment = null
        if (hadStream) {
            for (sock in sockets) {
                try { sock.send("RESET") } catch (_: Exception) {}
            }
        }
    }

    fun setInitSegment(mime: String, initBytes: ByteArray) {
        cachedMime = mime
        cachedInitSegment = initBytes
        for (sock in sockets) {
            try {
                sock.send("MIME:$mime")
                sock.send(initBytes)
            } catch (_: Exception) {
                sockets.remove(sock)
            }
        }
    }

    fun broadcastData(data: ByteArray) {
        for (sock in sockets) {
            try {
                sock.send(data)
            } catch (_: Exception) {
                sockets.remove(sock)
            }
        }
    }
}

class Fmp4StreamMuxer(
    private val width: Int,
    private val height: Int,
    private val fps: Int
) {
    @Volatile var isInitialized = false
        private set

    private var seqNum = 1
    private var basePtsUs: Long = -1L
    private var lastDecode90k: Long = 0L
    private val defaultDuration90k = 90000 / fps.coerceIn(1, 60)

    fun processCodecConfig(annexB: ByteArray): Pair<String, ByteArray>? {
        val nals = splitAnnexBNals(annexB)
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (nal in nals) {
            if (nal.isEmpty()) continue
            when (nal[0].toInt() and 0x1F) {
                7 -> sps = nal
                8 -> pps = nal
            }
        }
        if (sps != null && pps != null && sps.size >= 4) {
            val mime = String.format(
                "video/mp4; codecs=\"avc1.%02X%02X%02X\"",
                sps[1].toInt() and 0xFF,
                sps[2].toInt() and 0xFF,
                sps[3].toInt() and 0xFF
            )
            val initBox = buildInitSegment(sps, pps)
            isInitialized = true
            return Pair(mime, initBox)
        }
        return null
    }

    fun wrapFrame(annexB: ByteArray, ptsUs: Long, isKeyFrame: Boolean): ByteArray? {
        val nals = splitAnnexBNals(annexB)
        val avccOut = ByteArrayOutputStream()
        var hasVideoSlice = false

        for (nal in nals) {
            if (nal.isEmpty()) continue
            val nalType = nal[0].toInt() and 0x1F
            if (nalType == 7 || nalType == 8) continue
            if (nalType == 1 || nalType == 5) hasVideoSlice = true
            avccOut.write(intBytes(nal.size))
            avccOut.write(nal)
        }

        val payload = avccOut.toByteArray()
        if (payload.isEmpty() || !hasVideoSlice) return null

        if (basePtsUs < 0L) basePtsUs = ptsUs
        val current90k = ((ptsUs - basePtsUs).coerceAtLeast(0L) * 90L) / 1000L
        val decodeTime = if (seqNum == 1) 0L else maxOf(current90k, lastDecode90k + 1)
        val duration = if (seqNum == 1) defaultDuration90k else (decodeTime - lastDecode90k).toInt().coerceIn(300, 90000)
        lastDecode90k = decodeTime

        val sampleFlags = if (isKeyFrame) 0x02000000 else 0x01010000
        val mfhd = fullBox("mfhd", 0, 0, intBytes(seqNum++))
        val tfhd = fullBox("tfhd", 0, 0x020000, intBytes(1))
        val tfdt = fullBox("tfdt", 1, 0, longBytes(decodeTime))
        val trun = fullBox(
            "trun", 0, 0x000701,
            intBytes(1),
            intBytes(108),
            intBytes(duration),
            intBytes(payload.size),
            intBytes(sampleFlags)
        )
        val traf = box("traf", tfhd, tfdt, trun)
        val moof = box("moof", mfhd, traf)
        val mdat = box("mdat", payload)

        return concat(moof, mdat)
    }

    private fun buildInitSegment(sps: ByteArray, pps: ByteArray): ByteArray {
        val ftyp = box(
            "ftyp",
            "isom".toByteArray(Charsets.US_ASCII),
            intBytes(0x200),
            "isomiso6avc1mp41".toByteArray(Charsets.US_ASCII)
        )

        val unityMatrix = concat(
            intBytes(0x00010000), intBytes(0), intBytes(0),
            intBytes(0), intBytes(0x00010000), intBytes(0),
            intBytes(0), intBytes(0), intBytes(0x40000000)
        )

        val mvhd = fullBox(
            "mvhd", 0, 0,
            intBytes(0), intBytes(0),
            intBytes(90000), intBytes(0),
            intBytes(0x00010000),
            shortBytes(0x0100),
            ByteArray(10),
            unityMatrix,
            ByteArray(24),
            intBytes(2)
        )

        val tkhd = fullBox(
            "tkhd", 0, 3,
            intBytes(0), intBytes(0),
            intBytes(1), intBytes(0), intBytes(0),
            ByteArray(8),
            shortBytes(0), shortBytes(0), shortBytes(0), shortBytes(0),
            unityMatrix,
            intBytes(width shl 16),
            intBytes(height shl 16)
        )

        val mdhd = fullBox(
            "mdhd", 0, 0,
            intBytes(0), intBytes(0),
            intBytes(90000), intBytes(0),
            shortBytes(0x55C4), shortBytes(0)
        )

        val hdlr = fullBox(
            "hdlr", 0, 0,
            intBytes(0),
            "vide".toByteArray(Charsets.US_ASCII),
            ByteArray(12),
            "VideoHandler\u0000".toByteArray(Charsets.US_ASCII)
        )

        val vmhd = fullBox("vmhd", 0, 1, shortBytes(0), ByteArray(6))
        val urlBox = fullBox("url ", 0, 1)
        val dref = fullBox("dref", 0, 0, intBytes(1), urlBox)
        val dinf = box("dinf", dref)

        val avcC = box(
            "avcC",
            byteArrayOf(1, sps[1], sps[2], sps[3], (-1).toByte(), (-31).toByte()),
            shortBytes(sps.size),
            sps,
            byteArrayOf(1),
            shortBytes(pps.size),
            pps
        )

        val avc1 = box(
            "avc1",
            ByteArray(6),
            shortBytes(1),
            ByteArray(16),
            shortBytes(width),
            shortBytes(height),
            intBytes(0x00480000),
            intBytes(0x00480000),
            intBytes(0),
            shortBytes(1),
            ByteArray(32),
            shortBytes(0x0018),
            shortBytes(0xFFFF),
            avcC
        )

        val stsd = fullBox("stsd", 0, 0, intBytes(1), avc1)
        val stts = fullBox("stts", 0, 0, intBytes(0))
        val stsc = fullBox("stsc", 0, 0, intBytes(0))
        val stsz = fullBox("stsz", 0, 0, intBytes(0), intBytes(0))
        val stco = fullBox("stco", 0, 0, intBytes(0))
        val stbl = box("stbl", stsd, stts, stsc, stsz, stco)

        val minf = box("minf", vmhd, dinf, stbl)
        val mdia = box("mdia", mdhd, hdlr, minf)
        val trak = box("trak", tkhd, mdia)

        val trex = fullBox(
            "trex", 0, 0,
            intBytes(1),
            intBytes(1),
            intBytes(defaultDuration90k),
            intBytes(0),
            intBytes(0)
        )
        val mvex = box("mvex", trex)
        val moov = box("moov", mvhd, trak, mvex)

        return concat(ftyp, moov)
    }

    private fun splitAnnexBNals(data: ByteArray): List<ByteArray> {
        val result = ArrayList<ByteArray>()
        var i = 0
        var nalStart = -1

        while (i < data.size) {
            val is4Byte = i + 3 < data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            val is3Byte = !is4Byte && i + 2 < data.size && data[i] == 0.toByte() &&
                data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()

            if (is4Byte || is3Byte) {
                if (nalStart >= 0 && i > nalStart) {
                    result.add(data.copyOfRange(nalStart, i))
                }
                i += if (is4Byte) 4 else 3
                nalStart = i
            } else {
                i++
            }
        }
        if (nalStart >= 0 && data.size > nalStart) {
            result.add(data.copyOfRange(nalStart, data.size))
        }
        return result
    }

    private fun box(type: String, vararg payloads: ByteArray): ByteArray {
        val size = 8 + payloads.sumOf { it.size }
        val out = ByteArrayOutputStream(size)
        out.write(intBytes(size))
        out.write(type.toByteArray(Charsets.US_ASCII))
        for (p in payloads) out.write(p)
        return out.toByteArray()
    }

    private fun fullBox(type: String, version: Int, flags: Int, vararg payloads: ByteArray): ByteArray {
        val verFlags = (version shl 24) or (flags and 0x00FFFFFF)
        val totalSize = 12 + payloads.sumOf { it.size }
        val out = ByteArrayOutputStream(totalSize)
        out.write(intBytes(totalSize))
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(intBytes(verFlags))
        for (p in payloads) out.write(p)
        return out.toByteArray()
    }

    private fun intBytes(v: Int): ByteArray = byteArrayOf(
        (v shr 24).toByte(),
        (v shr 16).toByte(),
        (v shr 8).toByte(),
        v.toByte()
    )

    private fun shortBytes(v: Int): ByteArray = byteArrayOf(
        (v shr 8).toByte(),
        v.toByte()
    )

    private fun longBytes(v: Long): ByteArray = byteArrayOf(
        (v shr 56).toByte(), (v shr 48).toByte(), (v shr 40).toByte(), (v shr 32).toByte(),
        (v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte()
    )

    private fun concat(vararg arrays: ByteArray): ByteArray {
        val total = arrays.sumOf { it.size }
        val out = ByteArray(total)
        var pos = 0
        for (a in arrays) {
            System.arraycopy(a, 0, out, pos, a.size)
            pos += a.size
        }
        return out
    }
}
