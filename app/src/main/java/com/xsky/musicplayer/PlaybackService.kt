package com.xsky.musicplayer

import android.media.audiofx.Equalizer
import android.media.audiofx.BassBoost
import android.media.audiofx.Virtualizer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
        }
        session = MediaSession.Builder(this, player).build()
        try {
            equalizer = Equalizer(0, player.audioSessionId).also { it.enabled = true }
            bassBoost = BassBoost(0, player.audioSessionId)
            virtualizer = Virtualizer(0, player.audioSessionId)
        } catch (_: Exception) { }
        active = this
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        active = null
        equalizer?.release(); bassBoost?.release(); virtualizer?.release()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

    fun bandCount(): Int = equalizer?.numberOfBands?.toInt() ?: 0
    fun bandRange(): Pair<Int, Int> {
        val r = equalizer?.bandLevelRange
        return if (r != null) r[0].toInt() to r[1].toInt() else -1500 to 1500
    }
    fun bandLevel(index: Int): Int = try { equalizer?.getBandLevel(index.toShort())?.toInt() ?: 0 } catch (_: Exception) { 0 }
    fun setBand(index: Int, level: Int) { try { equalizer?.setBandLevel(index.toShort(), level.toShort()) } catch (_: Exception) {} }
    fun setBass(value: Int) { try { bassBoost?.setStrength(value.toShort()); bassBoost?.enabled = value > 0 } catch (_: Exception) {} }
    fun setVirtual(value: Int) { try { virtualizer?.setStrength(value.toShort()); virtualizer?.enabled = value > 0 } catch (_: Exception) {} }

    companion object { @Volatile var active: PlaybackService? = null }
}
