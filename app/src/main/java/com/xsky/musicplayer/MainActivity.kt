package com.xsky.musicplayer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import com.google.common.util.concurrent.ListenableFuture

private val Ink = Color(0xFF101114)
private val Panel = Color(0xFF1B1D22)
private val Accent = Color(0xFFB9F36B)

class MainActivity : ComponentActivity() {
    private var controller by mutableStateOf<MediaController?>(null)
    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener({ try { controller = controllerFuture?.get() } catch (_: Exception) {} }, MoreExecutors.directExecutor())
    }
    override fun onStop() { controller?.release(); controller = null; super.onStop() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Ink, surface = Panel)) { MusicApp(this, controller) } }
    }
}

@Composable
private fun MusicApp(context: Context, player: MediaController?) {
    var songs by remember { mutableStateOf(emptyList<Song>()) }
    var selectedTab by remember { mutableStateOf("Songs") }
    var query by remember { mutableStateOf("") }
    var currentTitle by remember { mutableStateOf("Choose a song") }
    var currentArtist by remember { mutableStateOf("Your music, your way") }
    var isPlaying by remember { mutableStateOf(false) }
    var duration by remember { mutableLongStateOf(0L) }
    var position by remember { mutableLongStateOf(0L) }
    var showEqualizer by remember { mutableStateOf(false) }
    var permissionGranted by remember { mutableStateOf(false) }

    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
        if (granted) songs = scanSongs(context)
    }
    LaunchedEffect(Unit) {
        permissionGranted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        if (permissionGranted) songs = scanSongs(context)
    }
    LaunchedEffect(player) {
        while (true) {
            player?.let {
                isPlaying = it.isPlaying
                position = it.currentPosition.coerceAtLeast(0)
                duration = it.duration.coerceAtLeast(0)
                it.currentMediaItem?.mediaMetadata?.let { m ->
                    currentTitle = m.title?.toString() ?: currentTitle
                    currentArtist = m.artist?.toString() ?: currentArtist
                }
            }
            delay(500)
        }
    }

    Column(Modifier.fillMaxSize().background(Ink).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("LOCAL MUSIC", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text("Your sound.", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = { showEqualizer = true }) { Icon(Icons.Default.Equalizer, null, tint = Accent) }
        }
        OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp), placeholder = { Text("Search your music") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(18.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = Color.DarkGray, focusedTextColor = Color.White, unfocusedTextColor = Color.White))
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Songs", "Albums", "Artists", "Folders").forEach { tab ->
                FilterChip(selected = selectedTab == tab, onClick = { selectedTab = tab }, label = { Text(tab) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent, selectedLabelColor = Ink))
            }
        }
        if (!permissionGranted) {
            Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Default.LibraryMusic, null, tint = Accent, modifier = Modifier.size(54.dp))
                Text("Let’s find your music", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Allow access to audio files on your device to build your library.", color = Color.LightGray)
                Button(onClick = { launcher.launch(permission) }, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink)) { Text("Scan my music") }
            }
        } else {
            val filtered = songs.filter { (it.title + it.artist + it.album).contains(query, true) }
            if (filtered.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (songs.isEmpty()) "No music found on this device" else "No matching songs", color = Color.LightGray) }
            else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                items(filtered, key = { it.id }) { song ->
                    Row(Modifier.fillMaxWidth().clickable { playSongs(player, songs, song) }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).background(Panel, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, tint = Accent) }
                        Column(Modifier.weight(1f).padding(start = 13.dp)) {
                            Text(song.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text(song.artist, color = Color.Gray, fontSize = 12.sp, maxLines = 1)
                        }
                        Text(formatTime(song.duration), color = Color.Gray, fontSize = 11.sp)
                        IconButton(onClick = { playSongs(player, songs, song) }) { Icon(Icons.Default.PlayArrow, null, tint = Accent) }
                    }
                }
            }
        }
        // Persistent mini-player
        Column(Modifier.fillMaxWidth().background(Panel).clickable { }.padding(horizontal = 18.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).background(Color(0xFF30343B), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Album, null, tint = Accent) }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(currentTitle, color = Color.White, maxLines = 1, fontWeight = FontWeight.SemiBold)
                    Text(currentArtist, color = Color.Gray, fontSize = 11.sp, maxLines = 1)
                }
                IconButton(onClick = { player?.seekToPreviousMediaItem() }) { Icon(Icons.Default.SkipPrevious, null, tint = Color.White) }
                IconButton(onClick = { if (player?.isPlaying == true) player.pause() else player?.play() }) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Accent, modifier = Modifier.size(30.dp)) }
                IconButton(onClick = { player?.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, null, tint = Color.White) }
            }
            Slider(value = if (duration > 0) position.toFloat().coerceIn(0f, duration.toFloat()) else 0f, onValueChange = { player?.seekTo(it.toLong()) }, valueRange = 0f..(duration.coerceAtLeast(1).toFloat()), modifier = Modifier.height(20.dp), colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
        }
    }
    if (showEqualizer) EqualizerDialog(onDismiss = { showEqualizer = false })
}

@Composable
private fun EqualizerDialog(onDismiss: () -> Unit) {
    val service = PlaybackService.active
    var refresh by remember { mutableIntStateOf(0) }
    var bass by remember { mutableFloatStateOf(0f) }
    var virtual by remember { mutableFloatStateOf(0f) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Panel, title = { Text("Equalizer", color = Color.White) }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            Text("Adjust the sound to your taste", color = Color.LightGray, fontSize = 12.sp)
            val count = service?.bandCount() ?: 0
            val range = service?.bandRange() ?: (-1500 to 1500)
            if (count == 0) Text("The equalizer is unavailable on this device or will appear when playback starts.", color = Color.LightGray)
            repeat(count) { i ->
                val level = service?.bandLevel(i) ?: 0
                Column {
                    Text("${(i + 1) * 1000} Hz  ·  ${level / 100} dB", color = Color.White, fontSize = 11.sp)
                    var value by remember(refresh, i) { mutableFloatStateOf(level.toFloat()) }
                    Slider(value = value, onValueChange = { value = it; service?.setBand(i, it.toInt()) }, valueRange = range.first.toFloat()..range.second.toFloat(), colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
                }
            }
            Text("Bass boost", color = Color.White); Slider(bass, { bass = it; service?.setBass((it * 1000).toInt()) }, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
            Text("Virtualizer", color = Color.White); Slider(virtual, { virtual = it; service?.setVirtual((it * 1000).toInt()) }, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
            Text("Sound effects depend on your phone's audio hardware and Android version.", color = Color.Gray, fontSize = 11.sp)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = Accent) } })
}

private fun playSongs(player: MediaController?, all: List<Song>, selected: Song) {
    if (player == null) return
    val index = all.indexOfFirst { it.id == selected.id }.coerceAtLeast(0)
    val items = all.map { song -> MediaItem.Builder().setUri(song.uri).setMediaId(song.id.toString()).setMediaMetadata(MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).setAlbumTitle(song.album).build()).build() }
    player.setMediaItems(items, index, 0L); player.prepare(); player.play()
}

private fun scanSongs(context: Context): List<Song> {
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION)
    val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
    val sort = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
    val result = mutableListOf<Song>()
    context.contentResolver.query(collection, projection, selection, null, sort)?.use { c ->
        val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID); val title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE); val artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST); val album = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM); val duration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        while (c.moveToNext()) { val songId = c.getLong(id); result += Song(songId, c.getString(title) ?: "Unknown title", c.getString(artist) ?: "Unknown artist", c.getString(album) ?: "Unknown album", c.getLong(duration), Uri.withAppendedPath(collection, songId.toString())) }
    }
    return result
}
private fun formatTime(ms: Long): String { val sec = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(sec / 60, sec % 60) }
