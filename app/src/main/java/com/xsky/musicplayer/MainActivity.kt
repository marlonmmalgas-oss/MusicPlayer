package com.xsky.musicplayer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private val Ink = Color(0xFF101113)
private val Panel = Color(0xFF1B1D20)
private val Panel2 = Color(0xFF24272B)
private val Gold = Color(0xFFD8BD83)
private val Muted = Color(0xFF9A9DA3)
private val White = Color(0xFFF4F1EA)

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = SessionToken(this, ComponentName(this, MusicPlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener({
            runCatching { controller = controllerFuture?.get() }
        }, ContextCompat.getMainExecutor(this))
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Gold,
                    secondary = Gold,
                    background = Ink,
                    surface = Panel,
                    onPrimary = Ink,
                    onBackground = White,
                    onSurface = White
                )
            ) {
                MusicApp(this, controller)
            }
        }
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onDestroy()
    }
}

@Composable
private fun MusicApp(context: Context, player: MediaController?) {
    val prefs = remember { context.getSharedPreferences("xsky_music", Context.MODE_PRIVATE) }
    var songs by remember { mutableStateOf(emptyList<Song>()) }
    var tab by remember { mutableStateOf("Songs") }
    var query by remember { mutableStateOf("") }
    var current by remember { mutableStateOf<Song?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var favorites by remember { mutableStateOf(prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()) }
    var recent by remember { mutableStateOf(prefs.getStringSet("recent", emptySet())?.toList() ?: emptyList()) }
    var showEq by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var sleepMinutes by remember { mutableFloatStateOf(30f) }
    var sleepActive by remember { mutableStateOf(false) }
    var sleepRemaining by remember { mutableIntStateOf(0) }
    var showQueue by remember { mutableStateOf(false) }
    var queue by remember { mutableStateOf(emptyList<Song>()) }
    val scope = rememberCoroutineScope()

    fun scan() {
        val list = mutableListOf<Song>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sort = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        context.contentResolver.query(collection, projection, selection, null, sort)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                list += Song(
                    id, c.getString(titleCol) ?: "Unknown title",
                    c.getString(artistCol) ?: "Unknown artist",
                    c.getString(albumCol) ?: "Unknown album",
                    c.getLong(durationCol),
                    Uri.withAppendedPath(collection, id.toString()),
                    c.getLong(albumIdCol)
                )
            }
        }
        songs = list
    }

    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
        if (it) scan()
    }
    LaunchedEffect(hasPermission) { if (hasPermission) scan() }
    LaunchedEffect(player) {
        while (true) {
            player?.let {
                isPlaying = it.isPlaying
                position = it.currentPosition.coerceAtLeast(0)
                duration = it.duration.takeIf { d -> d > 0 } ?: current?.duration ?: 0L
                val item = it.currentMediaItem
                if (item != null) {
                    val id = item.mediaId.toLongOrNull()
                    current = songs.firstOrNull { s -> s.id == id } ?: current
                }
            }
            delay(500)
        }
    }
    LaunchedEffect(sleepActive) {
        while (sleepActive && sleepRemaining > 0) {
            delay(60_000)
            sleepRemaining -= 1
            if (sleepRemaining <= 0) {
                player?.pause()
                sleepActive = false
            }
        }
    }

    val filtered = when (tab) {
        "Favorites" -> songs.filter { favorites.contains(it.id.toString()) }
        "Recent" -> recent.mapNotNull { id -> songs.firstOrNull { it.id.toString() == id } }
        "Albums" -> songs.distinctBy { it.album }
        "Artists" -> songs.distinctBy { it.artist }
        else -> songs
    }.filter { it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true) }

    fun play(song: Song, list: List<Song> = songs) {
        val chosen = if (list.isEmpty()) songs else list
        val items = chosen.map { s ->
            MediaItem.Builder()
                .setMediaId(s.id.toString())
                .setUri(s.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(s.title)
                        .setArtist(s.artist)
                        .setAlbumTitle(s.album)
                        .build()
                ).build()
        }
        val index = chosen.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        player?.apply {
            setMediaItems(items, index, 0L)
            prepare()
            play()
        }
        current = song
        queue = chosen
        recent = (listOf(song.id.toString()) + recent.filterNot { it == song.id.toString() }).take(100)
        prefs.edit().putStringSet("recent", recent.toSet()).apply()
    }

    Column(Modifier.fillMaxSize().background(Ink)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("XSKY", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                Text("MUSIC", color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            }
            IconButton(onClick = { showEq = true }) { Icon(Icons.Default.Equalizer, null, tint = Gold) }
            IconButton(onClick = { showSleep = true }) { Icon(Icons.Default.Bedtime, null, tint = Gold) }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("Search your music", color = Muted) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = Muted) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Gold, unfocusedBorderColor = Panel2,
                focusedTextColor = White, unfocusedTextColor = White,
                cursorColor = Gold
            )
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Songs", "Albums", "Artists", "Favorites", "Recent").forEach { label ->
                FilterChip(
                    selected = tab == label,
                    onClick = { tab = label },
                    label = { Text(label, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Gold, selectedLabelColor = Ink,
                        labelColor = Muted
                    )
                )
            }
        }
        if (!hasPermission) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.LibraryMusic, null, tint = Gold, modifier = Modifier.size(54.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Your music library is waiting", color = White, fontWeight = FontWeight.SemiBold)
                    Text("Allow access to scan audio stored on this device.", color = Muted)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { permissionLauncher.launch(permission) }) { Text("Allow music access") }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${filtered.size} TRACKS", color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { scan() }) { Text("Rescan", color = Gold) }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                items(filtered, key = { it.id }) { song ->
                    SongRow(
                        song = song,
                        favorite = favorites.contains(song.id.toString()),
                        onClick = { play(song, filtered) },
                        onFavorite = {
                            favorites = if (favorites.contains(song.id.toString())) favorites - song.id.toString()
                            else favorites + song.id.toString()
                            prefs.edit().putStringSet("favorites", favorites).apply()
                        },
                        onQueue = {
                            queue = queue + song
                            val item = MediaItem.Builder()
                                .setMediaId(song.id.toString())
                                .setUri(song.uri)
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(song.title)
                                        .setArtist(song.artist)
                                        .setAlbumTitle(song.album)
                                        .build()
                                ).build()
                            if (player != null) {
                                if (player.mediaItemCount == 0) {
                                    play(song)
                                } else {
                                    player.addMediaItem(item)
                                }
                            }
                        }
                    )
                }
                if (filtered.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                            Text(if (songs.isEmpty()) "No music found on this device." else "No matches found.", color = Muted)
                        }
                    }
                }
            }
        }
        current?.let { song ->
            Column(Modifier.fillMaxWidth().background(Panel).clickable { showQueue = true }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(9.dp)).background(Panel2), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.MusicNote, null, tint = Gold)
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(song.title, color = White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text(song.artist, color = Muted, fontSize = 12.sp)
                    }
                    IconButton(onClick = { player?.seekToPreviousMediaItem() }) { Icon(Icons.Default.SkipPrevious, null, tint = White) }
                    IconButton(onClick = { if (isPlaying) player?.pause() else player?.play() }) {
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Gold)
                    }
                    IconButton(onClick = { player?.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, null, tint = White) }
                }
                Slider(
                    value = if (duration > 0) position.toFloat().coerceIn(0f, duration.toFloat()) else 0f,
                    onValueChange = { position = it.toLong() },
                    onValueChangeFinished = { player?.seekTo(position) },
                    valueRange = 0f..(duration.coerceAtLeast(1L).toFloat()),
                    colors = SliderDefaults.colors(thumbColor = Gold, activeTrackColor = Gold, inactiveTrackColor = Panel2)
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(position), color = Muted, fontSize = 10.sp)
                    Text(formatTime(duration), color = Muted, fontSize = 10.sp)
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(Panel).padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            BottomAction("Library", Icons.Default.LibraryMusic) { tab = "Songs" }
            BottomAction("Favorites", Icons.Default.Favorite) { tab = "Favorites" }
            BottomAction("Queue", Icons.Default.QueueMusic) { showQueue = true }
            BottomAction("Equalizer", Icons.Default.Equalizer) { showEq = true }
        }
    }

    if (showQueue) {
        AlertDialog(
            onDismissRequest = { showQueue = false },
            title = { Text("Playback queue") },
            text = {
                Column {
                    Text("${player?.mediaItemCount ?: queue.size} items", color = Muted)
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(queue) { s ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(s.title, color = White, maxLines = 1)
                                    Text(s.artist, color = Muted, fontSize = 11.sp)
                                }
                                IconButton(onClick = { queue = queue.filterNot { it.id == s.id } }) {
                                    Icon(Icons.Default.Close, null, tint = Muted)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showQueue = false }) { Text("Done", color = Gold) } },
            containerColor = Panel
        )
    }

    if (showEq) EqualizerDialog(context, 0) { showEq = false }
    if (showSleep) {
        AlertDialog(
            onDismissRequest = { showSleep = false },
            title = { Text("Sleep timer") },
            text = {
                Column {
                    Text(if (sleepActive) "Timer active: $sleepRemaining minutes remaining" else "Pause playback after ${sleepMinutes.toInt()} minutes")
                    Slider(value = sleepMinutes, onValueChange = { sleepMinutes = it }, valueRange = 5f..180f, steps = 34)
                    Text("${sleepMinutes.toInt()} minutes", color = Gold)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    sleepRemaining = sleepMinutes.toInt()
                    sleepActive = true
                    showSleep = false
                }) { Text("Start", color = Gold) }
            },
            dismissButton = {
                TextButton(onClick = {
                    sleepActive = false
                    sleepRemaining = 0
                    showSleep = false
                }) { Text("Cancel timer", color = Muted) }
            },
            containerColor = Panel
        )
    }
}

@Composable
private fun SongRow(
    song: Song,
    favorite: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
    onQueue: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(Panel2), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Album, null, tint = Gold, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
            Text(song.title, color = White, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${song.artist}  •  ${song.album}", color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onFavorite) {
            Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (favorite) Gold else Muted)
        }
        IconButton(onClick = onQueue) { Icon(Icons.Default.AddToQueue, null, tint = Muted) }
    }
}

@Composable
private fun BottomAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, action: () -> Unit) {
    Column(Modifier.clickable(onClick = action).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Gold)
        Text(label, color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun EqualizerDialog(context: Context, audioSessionId: Int, close: () -> Unit) {
    var bass by remember { mutableFloatStateOf(0f) }
    var virtual by remember { mutableFloatStateOf(0f) }
    var preset by remember { mutableStateOf("Flat") }
    val eq = remember(audioSessionId) {
        if (audioSessionId > 0) runCatching { Equalizer(0, audioSessionId).apply { enabled = true } }.getOrNull() else null
    }
    val bassEffect = remember(audioSessionId) {
        if (audioSessionId > 0) runCatching { BassBoost(0, audioSessionId) }.getOrNull() else null
    }
    val virtualEffect = remember(audioSessionId) {
        if (audioSessionId > 0) runCatching { Virtualizer(0, audioSessionId) }.getOrNull() else null
    }
    val bandCount = eq?.numberOfBands?.toInt() ?: 0
    val range = eq?.bandLevelRange ?: shortArrayOf(-1500, 1500)
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Studio equalizer") },
        text = {
            Column(Modifier.heightIn(max = 500.dp)) {
                Text("Preset", color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Flat", "Bass", "Treble", "Vocal").forEach { p ->
                        FilterChip(selected = preset == p, onClick = {
                            preset = p
                            val values = when (p) {
                                "Bass" -> listOf(800, 600, 300, 100, 0)
                                "Treble" -> listOf(0, 100, 300, 600, 800)
                                "Vocal" -> listOf(-200, 200, 500, 300, -100)
                                else -> listOf(0, 0, 0, 0, 0)
                            }
                            for (i in 0 until bandCount) {
                                val value = values[(i * values.size / bandCount.coerceAtLeast(1)).coerceIn(0, 4)]
                                runCatching { eq?.setBandLevel(i.toShort(), value.coerceIn(range[0].toInt(), range[1].toInt()).toShort()) }
                            }
                        }, label = { Text(p, fontSize = 10.sp) })
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (bandCount == 0) Text("Audio effects will be available when playback starts on a supported device.", color = Muted)
                for (i in 0 until bandCount) {
                    var level by remember(audioSessionId, i) { mutableFloatStateOf((runCatching { eq?.getBandLevel(i.toShort())?.toFloat() }.getOrNull() ?: 0f)) }
                    Column {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${(runCatching { eq?.getCenterFreq(i.toShort())?.toInt()?.div(1000) }.getOrNull() ?: 0)} Hz", color = Muted, fontSize = 11.sp)
                            Text("${level.toInt()} mB", color = Gold, fontSize = 11.sp)
                        }
                        Slider(value = level, onValueChange = {
                            level = it
                            runCatching { eq?.setBandLevel(i.toShort(), it.toInt().coerceIn(range[0].toInt(), range[1].toInt()).toShort()) }
                        }, valueRange = range[0].toFloat()..range[1].toFloat())
                    }
                }
                Text("Bass boost", color = White)
                Slider(value = bass, onValueChange = {
                    bass = it
                    runCatching { bassEffect?.setStrength((it * 10).toInt().toShort()); bassEffect?.enabled = it > 0 }
                }, valueRange = 0f..100f)
                Text("Virtualizer", color = White)
                Slider(value = virtual, onValueChange = {
                    virtual = it
                    runCatching { virtualEffect?.setStrength((it * 10).toInt().toShort()); virtualEffect?.enabled = it > 0 }
                }, valueRange = 0f..100f)
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Done", color = Gold) } },
        containerColor = Panel
    )
    DisposableEffect(eq, bassEffect, virtualEffect) {
        onDispose {
            runCatching { eq?.release() }
            runCatching { bassEffect?.release() }
            runCatching { virtualEffect?.release() }
        }
    }
}

private fun formatTime(ms: Long): String {
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0))
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
