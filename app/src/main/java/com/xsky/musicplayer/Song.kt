package com.xsky.musicplayer

import android.net.Uri

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val uri: Uri,
    val albumId: Long = 0L,
    val folder: String = "Unknown folder"
) {
    val artwork: Uri
        get() = Uri.parse("content://media/external/audio/albumart/$albumId")
}

data class Album(
    val name: String,
    val artist: String,
    val songs: List<Song>
) {
    val artwork: Uri get() = songs.firstOrNull()?.artwork
        ?: Uri.parse("content://media/external/audio/albumart/0")
}
