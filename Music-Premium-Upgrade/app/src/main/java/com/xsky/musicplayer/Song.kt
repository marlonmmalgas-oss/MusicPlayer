package com.xsky.musicplayer

import android.net.Uri

data class Song(
    val id: Long, val title: String, val artist: String, val album: String,
    val duration: Long, val uri: Uri, val albumId: Long = -1L, val folder: String = "Music"
) {
    val artworkUri: Uri? get() = if (albumId >= 0) Uri.parse("content://media/external/audio/albumart/$albumId") else null
}
