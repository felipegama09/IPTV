package br.nova.iptv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var osd: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val hideOsd = Runnable { osd.visibility = View.GONE }

    private var index = 0
    private var triedTs = false
    private var resumeMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        view = findViewById(R.id.player)
        osd = findViewById(R.id.osd)
        index = Store.playIndex
        view.useController = !Store.zap
        if (Store.playList.isEmpty()) finish()
    }

    override fun onStart() {
        super.onStart()
        if (Store.playList.isEmpty()) return

        // Buffers pequenos: TVs com pouca RAM ficam fluidas e o zapping é rápido.
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 30_000, 2_000, 4_000)
            .setTargetBufferBytes(20 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Net.UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val renderers = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        val p = ExoPlayer.Builder(this, renderers)
            .setLoadControl(load)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val cur = Store.playList.getOrNull(index)?.url
                if (Store.zap && !triedTs && cur != null && cur.contains("/live/") && cur.endsWith(".m3u8")) {
                    triedTs = true
                    start(cur.removeSuffix(".m3u8") + ".ts", 0)
                    return
                }
                showOsd("Não foi possível reproduzir (${error.errorCodeName})")
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_ENDED) return
                if (Store.autoNext && index < Store.playList.size - 1) play(index + 1) else finish()
            }
        })
        player = p
        view.player = p
        view.requestFocus()
        play(index, resumeMs)
        resumeMs = 0
    }

    override fun onStop() {
        super.onStop()
        player?.let {
            if (!Store.zap) resumeMs = it.currentPosition
            it.release()
        }
        player = null
        view.player = null
    }

    private fun play(i: Int, startMs: Long = 0) {
        val item = Store.playList.getOrNull(i) ?: return
        index = i
        triedTs = false
        showOsd(if (Store.zap) "${i + 1}  ·  ${item.name}" else item.name)
        start(item.url ?: return, startMs)
    }

    private fun start(url: String, startMs: Long) {
        val p = player ?: return
        p.setMediaItem(MediaItem.fromUri(url), startMs)
        p.prepare()
        p.playWhenReady = true
    }

    private fun zap(delta: Int) {
        val n = Store.playList.size
        if (n == 0) return
        play(((index + delta) % n + n) % n)
    }

    private fun showOsd(text: String) {
        osd.text = text
        osd.visibility = View.VISIBLE
        handler.removeCallbacks(hideOsd)
        handler.postDelayed(hideOsd, 3500)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (Store.zap && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { zap(1); return true }
                KeyEvent.KEYCODE_CHANNEL_UP -> { zap(1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    showOsd("${index + 1}  ·  ${Store.playList[index].name}")
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        handler.removeCallbacks(hideOsd)
        super.onDestroy()
    }
}
