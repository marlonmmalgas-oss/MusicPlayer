package com.xsky.musicplayer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import coil.compose.AsyncImage
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

private val Black = Color(0xFF08090B)
private val Charcoal = Color(0xFF14171B)
private val Glass = Color(0xFF20242A)
private val Silver = Color(0xFFD7DCE2)
private val SilverBright = Color(0xFFF4F6F8)
private val Muted = Color(0xFF929AA4)
private val Edge = Color(0xFF68717C).copy(alpha = .48f)

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
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Silver, secondary = Silver, background = Black,
                surface = Charcoal, onPrimary = Black, onBackground = SilverBright,
                onSurface = SilverBright
            )) {
                XskyMusic(this, controller)
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
private fun XskyMusic(context: Context, player: MediaController?) {
    val prefs = remember { context.getSharedPreferences("xsky_music", Context.MODE_PRIVATE) }
    var songs by remember { mutableStateOf(emptyList<Song>()) }
    var tab by remember { mutableStateOf("Songs") }
    var search by remember { mutableStateOf("") }
    var current by remember { mutableStateOf<Song?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var fullPlayer by remember { mutableStateOf(false) }
    var selectedAlbum by remember { mutableStateOf<Album?>(null) }
    var selectedArtist by remember { mutableStateOf<String?>(null) }
    var favorites by remember { mutableStateOf(prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()) }
    var recent by remember { mutableStateOf(prefs.getStringSet("recent", emptySet())?.toList() ?: emptyList()) }
    var showQueue by remember { mutableStateOf(false) }
    var queue by remember { mutableStateOf(emptyList<Song>()) }
    var showEq by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var sleepMinutes by remember { mutableFloatStateOf(30f) }
    var sleepRemaining by remember { mutableIntStateOf(0) }
    var sleepActive by remember { mutableStateOf(false) }

    fun scanLibrary() {
        val list = mutableListOf<Song>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DATA
        )
        context.contentResolver.query(
            collection, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val dataCol = c.getColumnIndex(MediaStore.Audio.Media.DATA)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val path = if (dataCol >= 0) c.getString(dataCol).orEmpty() else ""
                val folder = path.substringBeforeLast("/", "Device music")
                    .substringAfterLast("/", "Device music").ifBlank { "Device music" }
                list += Song(
                    id = id,
                    title = c.getString(titleCol) ?: "Unknown title",
                    artist = c.getString(artistCol)?.takeIf { it != "<unknown>" } ?: "Unknown artist",
                    album = c.getString(albumCol)?.takeIf { it != "<unknown>" } ?: "Unknown album",
                    duration = c.getLong(durationCol),
                    uri = Uri.withAppendedPath(collection, id.toString()),
                    albumId = c.getLong(albumIdCol),
                    folder = folder
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
        if (it) scanLibrary()
    }
    LaunchedEffect(hasPermission) { if (hasPermission) scanLibrary() }

    LaunchedEffect(player, songs) {
        while (true) {
            player?.let { p ->
                isPlaying = p.isPlaying
                position = p.currentPosition.coerceAtLeast(0)
                duration = p.duration.takeIf { it > 0 } ?: current?.duration ?: 0L
                val mediaId = p.currentMediaItem?.mediaId?.toLongOrNull()
                current = songs.firstOrNull { it.id == mediaId } ?: current
            }
            delay(400)
        }
    }
    LaunchedEffect(sleepActive) {
        while (sleepActive && sleepRemaining > 0) {
            delay(60_000)
            sleepRemaining--
            if (sleepRemaining <= 0) {
                player?.pause()
                sleepActive = false
            }
        }
    }

    val albums = remember(songs) {
        songs.groupBy { it.album.trim().ifBlank { "Unknown album" } }
            .map { (name, tracks) -> Album(name, tracks.first().artist, tracks) }
            .sortedBy { it.name.lowercase() }
    }
    val artists = remember(songs) { songs.map { it.artist }.distinct().sorted() }
    val visibleSongs = when {
        selectedAlbum != null -> selectedAlbum!!.songs
        selectedArtist != null -> songs.filter { it.artist == selectedArtist }
        tab == "Favorites" -> songs.filter { favorites.contains(it.id.toString()) }
        tab == "Recent" -> recent.mapNotNull { id -> songs.firstOrNull { it.id.toString() == id } }
        tab == "Folders" -> songs.filter { it.folder.contains(search, true) || it.title.contains(search, true) }
        else -> songs
    }.filter { it.title.contains(search, true) || it.artist.contains(search, true) || it.album.contains(search, true) }

    fun playSong(song: Song, list: List<Song> = songs, openPlayer: Boolean = true) {
        val playList = list.ifEmpty { songs }
        val mediaItems = playList.map { s ->
            MediaItem.Builder().setMediaId(s.id.toString()).setUri(s.uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(s.title)
                    .setArtist(s.artist).setAlbumTitle(s.album).build()).build()
        }
        val index = playList.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        player?.apply { setMediaItems(mediaItems, index, 0L); prepare(); play() }
        current = song
        queue = playList
        recent = (listOf(song.id.toString()) + recent.filterNot { it == song.id.toString() }).take(100)
        prefs.edit().putStringSet("recent", recent.toSet()).apply()
        if (openPlayer) fullPlayer = true
    }

    fun toggleFavorite(song: Song) {
        val id = song.id.toString()
        favorites = if (favorites.contains(id)) favorites - id else favorites + id
        prefs.edit().putStringSet("favorites", favorites).apply()
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF171B20), Black)))) {
        if (fullPlayer && current != null) {
            FullPlayerScreen(
                song = current!!, playing = isPlaying, position = position, duration = duration,
                favorite = favorites.contains(current!!.id.toString()),
                onBack = { fullPlayer = false },
                onPlayPause = { if (isPlaying) player?.pause() else player?.play() },
                onPrevious = { player?.seekToPreviousMediaItem() },
                onNext = { player?.seekToNextMediaItem() },
                onSeek = { player?.seekTo(it) },
                onFavorite = { toggleFavorite(current!!) },
                onQueue = { showQueue = true },
                onEqualizer = { showEq = true }
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 18.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("X S K Y", color = Silver, fontSize = 10.sp, letterSpacing = 5.sp, fontWeight = FontWeight.Bold)
                        Text("MUSIC", color = SilverBright, fontSize = 26.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Black)
                    }
                    IconButton(onClick = { showEq = true }) { Icon(Icons.Default.Equalizer, null, tint = Silver) }
                    IconButton(onClick = { showSleep = true }) { Icon(Icons.Default.Timer, null, tint = Silver) }
                }
                OutlinedTextField(
                    value = search, onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    placeholder = { Text("Search songs, albums, artists", color = Muted) },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = Muted) },
                    singleLine = true, shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Silver, unfocusedBorderColor = Edge,
                        focusedTextColor = SilverBright, unfocusedTextColor = SilverBright,
                        cursorColor = Silver, focusedContainerColor = Glass.copy(alpha = .55f),
                        unfocusedContainerColor = Glass.copy(alpha = .35f)
                    )
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Songs", "Albums", "Artists", "Folders", "Favorites", "Recent").forEach { label ->
                        FilterChip(
                            selected = tab == label && selectedAlbum == null && selectedArtist == null,
                            onClick = { tab = label; selectedAlbum = null; selectedArtist = null },
                            label = { Text(label, fontSize = 10.sp) },
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true, selected = tab == label && selectedAlbum == null && selectedArtist == null,
                                borderColor = Edge, selectedBorderColor = Silver
                            ),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Silver, selectedLabelColor = Black,
                                labelColor = Muted, containerColor = Glass.copy(alpha = .35f)
                            )
                        )
                    }
                }

                if (selectedAlbum != null || selectedArtist != null) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { selectedAlbum = null; selectedArtist = null }) {
                            Icon(Icons.Default.ArrowBack, null, tint = Silver)
                        }
                        Text(selectedAlbum?.name ?: selectedArtist.orEmpty(), color = SilverBright, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }

                if (!hasPermission) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.LibraryMusic, null, tint = Silver, modifier = Modifier.size(52.dp))
                            Text("Your music library is waiting", color = SilverBright, fontWeight = FontWeight.SemiBold)
                            Text("Allow access to scan audio stored on this device.", color = Muted)
                            Button(onClick = { permissionLauncher.launch(permission) }) { Text("Allow music access") }
                        }
                    }
                } else if (tab == "Albums" && selectedAlbum == null) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${albums.size} ALBUMS", color = Muted, fontSize = 11.sp, letterSpacing = 1.4.sp)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { scanLibrary() }) { Text("Rescan", color = Silver) }
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(albums) { album ->
                            AlbumCard(album) { selectedAlbum = album }
                        }
                    }
                } else if (tab == "Artists" && selectedArtist == null) {
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 14.dp)) {
                        items(artists) { artist ->
                            Row(
                                Modifier.fillMaxWidth().clickable { selectedArtist = artist }.padding(horizontal = 18.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.size(48.dp).clip(CircleShape).background(Glass), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Person, null, tint = Silver)
                                }
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(artist, color = SilverBright, fontWeight = FontWeight.Medium)
                                    Text("${songs.count { it.artist == artist }} tracks", color = Muted, fontSize = 11.sp)
                                }
                                Icon(Icons.Default.ChevronRight, null, tint = Muted)
                            }
                        }
                    }
                } else if (tab == "Folders" && selectedAlbum == null && selectedArtist == null) {
                    val folders = visibleSongs.groupBy { it.folder }.toSortedMap()
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 14.dp)) {
                        folders.forEach { (folder, tracks) ->
                            item(key = folder) {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Folder, null, tint = Silver)
                                        Text(folder, color = SilverBright, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 9.dp))
                                        Spacer(Modifier.weight(1f))
                                        Text("${tracks.size}", color = Muted, fontSize = 11.sp)
                                    }
                                    tracks.forEach { song ->
                                        TrackRow(song, favorites.contains(song.id.toString()),
                                            { playSong(song, tracks) }, { toggleFavorite(song) },
                                            { enqueue(song, player, queue) { queue = it } })
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${visibleSongs.size} TRACKS", color = Muted, fontSize = 11.sp, letterSpacing = 1.4.sp)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { scanLibrary() }) { Text("Rescan", color = Silver) }
                    }
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 10.dp)) {
                        items(visibleSongs, key = { it.id }) { song ->
                            TrackRow(song, favorites.contains(song.id.toString()),
                                { playSong(song, visibleSongs) }, { toggleFavorite(song) },
                                { enqueue(song, player, queue) { queue = it } })
                        }
                        if (visibleSongs.isEmpty()) item {
                            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                                Text("No music found in this section.", color = Muted)
                            }
                        }
                    }
                }

                if (current != null) MiniPlayer(
                    song = current!!, playing = isPlaying, position = position, duration = duration,
                    onOpen = { fullPlayer = true },
                    onPlayPause = { if (isPlaying) player?.pause() else player?.play() },
                    onPrevious = { player?.seekToPreviousMediaItem() },
                    onNext = { player?.seekToNextMediaItem() }
                )
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF111418).copy(alpha = .96f))
                        .border(BorderStroke(1.dp, Edge.copy(alpha = .25f))).padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    BottomAction("Library", Icons.Default.LibraryMusic) { tab = "Songs"; selectedAlbum = null; selectedArtist = null }
                    BottomAction("Albums", Icons.Default.Album) { tab = "Albums"; selectedAlbum = null; selectedArtist = null }
                    BottomAction("Queue", Icons.Default.QueueMusic) { showQueue = true }
                    BottomAction("Sound", Icons.Default.Equalizer) { showEq = true }
                }
            }
        }
    }

    if (showQueue) {
        AlertDialog(
            onDismissRequest = { showQueue = false },
            title = { Text("Playback queue") },
            text = {
                Column {
                    Text("${player?.mediaItemCount ?: queue.size} tracks", color = Muted)
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(queue) { s ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Artwork(s.artwork, Modifier.size(42.dp))
                                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                    Text(s.title, color = SilverBright, maxLines = 1)
                                    Text(s.artist, color = Muted, fontSize = 11.sp)
                                }
                                IconButton(onClick = {
                                    val index = player?.currentMediaItemIndex ?: -1
                                    val found = queue.indexOfFirst { it.id == s.id }
                                    if (found >= 0 && found != index) player?.removeMediaItem(found)
                                    queue = queue.filterNot { it.id == s.id }
                                }) { Icon(Icons.Default.Close, null, tint = Muted) }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showQueue = false }) { Text("Done", color = Silver) } },
            containerColor = Charcoal
        )
    }
    if (showEq) EqualizerPanel { showEq = false }
    if (showSleep) {
        AlertDialog(
            onDismissRequest = { showSleep = false },
            title = { Text("Sleep timer") },
            text = {
                Column {
                    Text(if (sleepActive) "$sleepRemaining minutes remaining" else "Stop playback after ${sleepMinutes.toInt()} minutes")
                    Slider(value = sleepMinutes, onValueChange = { sleepMinutes = it }, valueRange = 5f..180f)
                }
            },
            confirmButton = {
                TextButton(onClick = { sleepRemaining = sleepMinutes.toInt(); sleepActive = true; showSleep = false }) {
                    Text("Start", color = Silver)
                }
            },
            dismissButton = {
                TextButton(onClick = { sleepActive = false; sleepRemaining = 0; showSleep = false }) {
                    Text("Cancel timer", color = Muted)
                }
            },
            containerColor = Charcoal
        )
    }
}

private fun enqueue(song: Song, player: MediaController?, queue: List<Song>, update: (List<Song>) -> Unit) {
    update(queue + song)
    val item = MediaItem.Builder().setMediaId(song.id.toString()).setUri(song.uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist)
            .setAlbumTitle(song.album).build()).build()
    if (player != null) {
        if (player.mediaItemCount == 0) {
            player.setMediaItem(item); player.prepare(); player.play()
        } else player.addMediaItem(item)
    }
}

@Composable
private fun Artwork(uri: Uri, modifier: Modifier = Modifier) {
    AsyncImage(
        model = uri, contentDescription = "Album artwork",
        modifier = modifier.clip(RoundedCornerShape(12.dp)).background(Glass),
        contentScale = androidx.compose.ui.layout.ContentScale.Crop
    )
}

@Composable
private fun AlbumCard(album: Album, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF292F36), Color(0xFF171B20))))
            .border(1.dp, Edge, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(10.dp)
    ) {
        Artwork(album.artwork, Modifier.fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(9.dp))
        Text(album.name, color = SilverBright, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(album.artist, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${album.songs.size} tracks", color = Silver, fontSize = 10.sp)
    }
}

@Composable
private fun TrackRow(song: Song, favorite: Boolean, onClick: () -> Unit, onFavorite: () -> Unit, onQueue: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Artwork(song.artwork, Modifier.size(52.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
            Text(song.title, color = SilverBright, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${song.artist}  •  ${song.album}", color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onFavorite) {
            Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null,
                tint = if (favorite) SilverBright else Muted, modifier = Modifier.size(19.dp))
        }
        IconButton(onClick = onQueue) { Icon(Icons.Default.AddToQueue, null, tint = Muted, modifier = Modifier.size(19.dp)) }
    }
}

@Composable
private fun MiniPlayer(
    song: Song, playing: Boolean, position: Long, duration: Long,
    onOpen: () -> Unit, onPlayPause: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF30363E), Color(0xFF171B20))))
            .border(1.dp, Edge, RoundedCornerShape(18.dp))
    ) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 10.dp, top = 9.dp, end = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Artwork(song.artwork, Modifier.size(46.dp))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(song.title, color = SilverBright, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, color = Muted, fontSize = 11.sp)
            }
            IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, null, tint = SilverBright) }
            IconButton(onClick = onPlayPause) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = SilverBright) }
            IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, null, tint = SilverBright) }
        }
        LinearProgressIndicator(
            progress = { if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = Silver, trackColor = Color.Transparent
        )
    }
}

@Composable
private fun FullPlayerScreen(
    song: Song, playing: Boolean, position: Long, duration: Long, favorite: Boolean,
    onBack: () -> Unit, onPlayPause: () -> Unit, onPrevious: () -> Unit,
    onNext: () -> Unit, onSeek: (Long) -> Unit, onFavorite: () -> Unit,
    onQueue: () -> Unit, onEqualizer: () -> Unit
) {
    var seek by remember(song.id) { mutableFloatStateOf(position.toFloat()) }
    LaunchedEffect(position) { seek = position.toFloat() }
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF303740), Color(0xFF111418), Black)))
            .padding(horizontal = 22.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.KeyboardArrowDown, null, tint = SilverBright, modifier = Modifier.size(32.dp)) }
            Spacer(Modifier.weight(1f))
            Text("NOW PLAYING", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onQueue) { Icon(Icons.Default.QueueMusic, null, tint = SilverBright) }
        }
        Spacer(Modifier.weight(.45f))
        Artwork(song.artwork, Modifier.fillMaxWidth().aspectRatio(1f).padding(horizontal = 8.dp))
        Spacer(Modifier.height(30.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(song.title, color = SilverBright, fontSize = 23.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(song.artist, color = Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 5.dp))
                Text(song.album, color = Muted, fontSize = 12.sp, maxLines = 1)
            }
            IconButton(onClick = onFavorite) {
                Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null,
                    tint = SilverBright, modifier = Modifier.size(27.dp))
            }
        }
        Spacer(Modifier.height(22.dp))
        Slider(
            value = seek.coerceIn(0f, duration.coerceAtLeast(1L).toFloat()),
            onValueChange = { seek = it },
            onValueChangeFinished = { onSeek(seek.toLong()) },
            valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
            colors = SliderDefaults.colors(thumbColor = SilverBright, activeTrackColor = SilverBright, inactiveTrackColor = Edge)
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(position), color = Muted, fontSize = 11.sp)
            Text(formatTime(duration), color = Muted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onEqualizer) { Icon(Icons.Default.Equalizer, null, tint = Muted, modifier = Modifier.size(25.dp)) }
            IconButton(onClick = onPrevious, modifier = Modifier.size(54.dp)) { Icon(Icons.Default.SkipPrevious, null, tint = SilverBright, modifier = Modifier.size(36.dp)) }
            IconButton(onClick = onPlayPause, modifier = Modifier.size(76.dp).clip(CircleShape).background(SilverBright)) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Black, modifier = Modifier.size(42.dp))
            }
            IconButton(onClick = onNext, modifier = Modifier.size(54.dp)) { Icon(Icons.Default.SkipNext, null, tint = SilverBright, modifier = Modifier.size(36.dp)) }
            IconButton(onClick = onQueue) { Icon(Icons.Default.QueueMusic, null, tint = Muted, modifier = Modifier.size(25.dp)) }
        }
        Spacer(Modifier.weight(.7f))
        Text("X S K Y   M U S I C", color = Muted.copy(alpha = .65f), fontSize = 9.sp,
            letterSpacing = 2.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 20.dp))
    }
}

@Composable
private fun BottomAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, action: () -> Unit) {
    Column(Modifier.clickable(onClick = action).padding(horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Silver)
        Text(label, color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun EqualizerPanel(close: () -> Unit) {
    var bass by remember { mutableFloatStateOf(25f) }
    var treble by remember { mutableFloatStateOf(50f) }
    var clarity by remember { mutableFloatStateOf(50f) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Sound controls") },
        text = {
            Column {
                Text("Playback equalizer controls will depend on device audio-effect support.", color = Muted, fontSize = 12.sp)
                Text("Bass", color = SilverBright); Slider(bass, { bass = it }, valueRange = 0f..100f)
                Text("Treble", color = SilverBright); Slider(treble, { treble = it }, valueRange = 0f..100f)
                Text("Clarity", color = SilverBright); Slider(clarity, { clarity = it }, valueRange = 0f..100f)
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Done", color = Silver) } },
        containerColor = Charcoal
    )
}

private fun formatTime(ms: Long): String {
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0))
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
