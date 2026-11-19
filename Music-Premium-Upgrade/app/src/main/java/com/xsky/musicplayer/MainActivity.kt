package com.xsky.musicplayer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private val Bg=Color(0xFF0B0C0E); private val Card=Color(0xFF191B1F); private val Gold=Color(0xFFD7B978); private val Grey=Color(0xFF9B9DA3)

class MainActivity:ComponentActivity(){
    private var controller by mutableStateOf<MediaController?>(null)
    private var future:com.google.common.util.concurrent.ListenableFuture<MediaController>?=null
    override fun onStart(){super.onStart();future=MediaController.Builder(this,SessionToken(this,ComponentName(this,PlaybackService::class.java))).buildAsync();future?.addListener({try{controller=future?.get()}catch(_:Exception){}},MoreExecutors.directExecutor())}
    override fun onStop(){controller?.release();controller=null;super.onStop()}
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{MaterialTheme(colorScheme=darkColorScheme(primary=Gold,background=Bg,surface=Card)){MusicApp(this,controller)}}}
}

private class Store(c:Context){
    private val p=c.getSharedPreferences("music_store",0)
    fun ids(k:String)=p.getStringSet(k,emptySet())!!.mapNotNull{it.toLongOrNull()}.toMutableSet()
    fun save(k:String,v:Set<Long>){p.edit().putStringSet(k,v.map{it.toString()}.toSet()).apply()}
    fun playlists():MutableMap<String,MutableList<Long>>{val o=JSONObject(p.getString("playlists","{}")!!);val m=mutableMapOf<String,MutableList<Long>>();o.keys().forEach{n->val a=o.optJSONArray(n)?:JSONArray();m[n]=(0 until a.length()).map{a.optLong(it)}.toMutableList()};return m}
    fun saveLists(m:Map<String,List<Long>>){val o=JSONObject();m.forEach{(k,v)->o.put(k,JSONArray(v))};p.edit().putString("playlists",o.toString()).apply()}
    fun history()=(p.getString("recent","")?:"").split(",").mapNotNull{it.toLongOrNull()}.toMutableList()
    fun played(id:Long){val h=history();h.remove(id);h.add(0,id);p.edit().putString("recent",h.take(50).joinToString(",")).apply();val o=JSONObject(p.getString("counts","{}")!!);o.put(id.toString(),o.optInt(id.toString())+1);p.edit().putString("counts",o.toString()).apply()}
    fun counts():Map<Long,Int>{val o=JSONObject(p.getString("counts","{}")!!);val m=mutableMapOf<Long,Int>();o.keys().forEach{k->k.toLongOrNull()?.let{m[it]=o.optInt(k)}};return m}
}

@Composable private fun MusicApp(ctx:Context,p:MediaController?){
    val store=remember{Store(ctx)}
    var songs by remember{mutableStateOf(emptyList<Song>())};var tab by remember{mutableStateOf("Home")};var search by remember{mutableStateOf("")}
    var fav by remember{mutableStateOf(store.ids("fav"))};var lists by remember{mutableStateOf(store.playlists())};var recent by remember{mutableStateOf(store.history())};var counts by remember{mutableStateOf(store.counts())}
    var permission by remember{mutableStateOf(false)};var expanded by remember{mutableStateOf(false)};var queue by remember{mutableStateOf(false)};var eq by remember{mutableStateOf(false)}
    var current by remember{mutableLongStateOf(-1L)};var title by remember{mutableStateOf("Choose a song")};var artist by remember{mutableStateOf("Your library")};var art by remember{mutableStateOf<Uri?>(null)}
    var playing by remember{mutableStateOf(false)};var pos by remember{mutableLongStateOf(0L)};var dur by remember{mutableLongStateOf(0L)}
    var playlistDialog by remember{mutableStateOf(false)};var newName by remember{mutableStateOf("")};var addSong by remember{mutableStateOf<Song?>(null)}
    var sleepDialog by remember{mutableStateOf(false)};var sleepText by remember{mutableStateOf("30")};var sleepJob by remember{mutableStateOf<Job?>(null)}
    val perm=if(Build.VERSION.SDK_INT>=33)Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val request=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){permission=it;if(it)songs=scan(ctx)}
    LaunchedEffect(Unit){permission=ContextCompat.checkSelfPermission(ctx,perm)==PackageManager.PERMISSION_GRANTED;if(permission)songs=scan(ctx)}
    LaunchedEffect(p){while(true){p?.let{playing=it.isPlaying;pos=it.currentPosition.coerceAtLeast(0);dur=it.duration.coerceAtLeast(0);it.currentMediaItem?.let{mi->val id=mi.mediaId.toLongOrNull()?:-1L;if(id!=current){current=id;val s=songs.firstOrNull{it.id==id};title=mi.mediaMetadata.title?.toString()?:s?.title?:"Unknown";artist=mi.mediaMetadata.artist?.toString()?:s?.artist?:"Unknown";art=s?.artworkUri;if(id>0){store.played(id);recent=store.history();counts=store.counts()}}}};delay(400)}}
    fun play(s:Song,collection:List<Song>){val items=collection.distinctBy{it.id}.map{MediaItem.Builder().setUri(it.uri).setMediaId(it.id.toString()).setMediaMetadata(MediaMetadata.Builder().setTitle(it.title).setArtist(it.artist).setAlbumTitle(it.album).setArtworkUri(it.artworkUri).build()).build()};val i=collection.indexOfFirst{it.id==s.id}.coerceAtLeast(0);p?.setMediaItems(items,i,0);p?.prepare();p?.play()}
    if(!permission){Column(Modifier.fillMaxSize().background(Bg).padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Icon(Icons.Default.LibraryMusic,null,tint=Gold,modifier=Modifier.size(60.dp));Text("Music",fontSize=30.sp,color=Color.White,fontWeight=FontWeight.Bold);Text("Allow access to scan music stored on this device.",color=Grey);Button(onClick={request.launch(perm)},colors=ButtonDefaults.buttonColors(containerColor=Gold,contentColor=Bg)){Text("Scan my music")}};return}
    if(expanded){Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF302A20),Bg))).statusBarsPadding().padding(22.dp)){Row(verticalAlignment=Alignment.CenterVertically){TextButton(onClick={expanded=false}){Text("⌄ Close",color=Gold)};Spacer(Modifier.weight(1f));Text("NOW PLAYING",color=Gold,fontSize=11.sp,letterSpacing=2.sp);Spacer(Modifier.weight(1f));TextButton(onClick={queue=true}){Text("Queue",color=Gold)}};Spacer(Modifier.weight(1f));Cover(art,Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(22.dp)));Spacer(Modifier.height(25.dp));Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(title,color=Color.White,fontSize=23.sp,fontWeight=FontWeight.Bold,maxLines=1);Text(artist,color=Grey)};IconButton(onClick={if(current>0){if(fav.contains(current))fav.remove(current)else fav.add(current);store.save("fav",fav);fav=fav.toMutableSet()}}){Icon(if(fav.contains(current))Icons.Default.Favorite else Icons.Default.FavoriteBorder,null,tint=Gold)}};Slider(value=if(dur>0)pos.toFloat().coerceIn(0f,dur.toFloat())else 0f,onValueChange={p?.seekTo(it.toLong())},valueRange=0f..dur.coerceAtLeast(1).toFloat(),colors=SliderDefaults.colors(thumbColor=Gold,activeTrackColor=Gold));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(fmt(pos),color=Grey);Text(fmt(dur),color=Grey)};Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically){IconButton(onClick={p?.shuffleModeEnabled=p?.shuffleModeEnabled!=true}){Icon(Icons.Default.Shuffle,null,tint=Gold)};IconButton(onClick={p?.seekToPreviousMediaItem()}){Icon(Icons.Default.SkipPrevious,null,tint=Color.White,modifier=Modifier.size(38.dp))};IconButton(onClick={if(playing)p?.pause()else p?.play()},modifier=Modifier.size(66.dp).background(Gold,CircleShape)){Icon(if(playing)Icons.Default.Pause else Icons.Default.PlayArrow,null,tint=Bg,modifier=Modifier.size(38.dp))};IconButton(onClick={p?.seekToNextMediaItem()}){Icon(Icons.Default.SkipNext,null,tint=Color.White,modifier=Modifier.size(38.dp))};IconButton(onClick={p?.repeatMode=when(p?.repeatMode){Player.REPEAT_MODE_OFF->Player.REPEAT_MODE_ALL;Player.REPEAT_MODE_ALL->Player.REPEAT_MODE_ONE;else->Player.REPEAT_MODE_OFF}}){Icon(Icons.Default.Repeat,null,tint=Gold)}};Spacer(Modifier.weight(1f))}}
    else{Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding()){Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=15.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("XSKY",color=Gold,fontSize=10.sp,letterSpacing=3.sp);Text("Music",color=Color.White,fontSize=30.sp,fontWeight=FontWeight.Bold)};IconButton(onClick={eq=true}){Icon(Icons.Default.Tune,null,tint=Gold)};IconButton(onClick={sleepDialog=true}){Icon(Icons.Default.Bedtime,null,tint=Gold)}}
    OutlinedTextField(search,{search=it},Modifier.fillMaxWidth().padding(horizontal=17.dp),placeholder={Text("Search your library")},leadingIcon={Icon(Icons.Default.Search,null)},singleLine=true,shape=RoundedCornerShape(16.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("Home","Songs","Albums","Artists","Folders","Favorites","Playlists","Recent","Most played").forEach{FilterChip(selected=tab==it,onClick={tab=it},label={Text(it)},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Gold,selectedLabelColor=Bg) )}}
    val q=songs.filter{(it.title+it.artist+it.album+it.folder).contains(search,true)}
    when(tab){
    "Home"->LazyColumn(Modifier.weight(1f)){item{Heading("Recently played")};item{Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){recent.take(10).mapNotNull{id->q.firstOrNull{it.id==id}}.forEach{s->Column(Modifier.width(110.dp).clickable{play(s,songs)}){Cover(s.artworkUri,Modifier.size(110.dp).clip(RoundedCornerShape(14.dp)));Text(s.album,color=Color.White,maxLines=1);Text(s.artist,color=Grey,fontSize=11.sp,maxLines=1)}}}};item{Heading("Your library")};item{Row(Modifier.padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){Tile("Songs",songs.size.toString()){tab="Songs"};Tile("Favorites",fav.size.toString()){tab="Favorites"};Tile("Playlists",lists.size.toString()){tab="Playlists"}}};item{Heading("All music")};items(q.take(30),key={it.id}){SongLine(it,fav.contains(it.id),{play(it,songs)},{if(fav.contains(it.id))fav.remove(it.id)else fav.add(it.id);store.save("fav",fav);fav=fav.toMutableSet()},{addSong=it})}}
    "Albums"->LazyColumn(Modifier.weight(1f)){q.groupBy{it.album}.toList().sortedBy{it.first}.forEach{(a,ss)->item(key=a){Row(Modifier.fillMaxWidth().clickable{play(ss.first(),ss)}.padding(14.dp),verticalAlignment=Alignment.CenterVertically){Cover(ss.first().artworkUri,Modifier.size(58.dp).clip(RoundedCornerShape(10.dp)));Column(Modifier.weight(1f).padding(start=12.dp)){Text(a,color=Color.White,fontWeight=FontWeight.SemiBold);Text("${ss.first().artist} · ${ss.size} tracks",color=Grey,fontSize=12.sp)}}}}}
    "Artists"->LazyColumn(Modifier.weight(1f)){q.groupBy{it.artist}.toList().sortedBy{it.first}.forEach{(a,ss)->item(key=a){Text("$a   ·   ${ss.size} songs",color=Color.White,modifier=Modifier.fillMaxWidth().clickable{play(ss.first(),ss)}.padding(20.dp))}}}
    "Folders"->LazyColumn(Modifier.weight(1f)){q.groupBy{it.folder}.toList().forEach{(a,ss)->item(key=a){Text("▣  $a   ·   ${ss.size} tracks",color=Color.White,modifier=Modifier.fillMaxWidth().clickable{play(ss.first(),ss)}.padding(20.dp))}}}
    "Playlists"->LazyColumn(Modifier.weight(1f)){item{Button(onClick={playlistDialog=true},Modifier.fillMaxWidth().padding(12.dp),colors=ButtonDefaults.buttonColors(containerColor=Gold,contentColor=Bg)){Text("+ Create playlist")}};lists.forEach{(name,ids)->item(key=name){Row(Modifier.fillMaxWidth().clickable{tab="List:$name"}.padding(18.dp)){Text("$name  ·  ${ids.size} tracks",color=Color.White,modifier=Modifier.weight(1f));TextButton(onClick={lists.remove(name);store.saveLists(lists);lists=lists.toMutableMap()}){Text("Delete",color=Grey)}}}}}
    else->{val chosen=when(tab){"Favorites"->q.filter{fav.contains(it.id)};"Recent"->recent.mapNotNull{id->q.firstOrNull{it.id==id}};"Most played"->q.sortedByDescending{counts[it.id]?:0};else->if(tab.startsWith("List:"))lists[tab.removePrefix("List:")].orEmpty().mapNotNull{id->q.firstOrNull{it.id==id}}else->q};LazyColumn(Modifier.weight(1f)){items(chosen,key={it.id}){SongLine(it,fav.contains(it.id),{play(it,chosen)},{if(fav.contains(it.id))fav.remove(it.id)else fav.add(it.id);store.save("fav",fav);fav=fav.toMutableSet()},{addSong=it})}}}
    }
    Column(Modifier.fillMaxWidth().background(Card).clickable{expanded=true}.padding(10.dp)){Row(verticalAlignment=Alignment.CenterVertically){Cover(art,Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)));Column(Modifier.weight(1f).padding(start=10.dp)){Text(title,color=Color.White,fontWeight=FontWeight.SemiBold,maxLines=1);Text(artist,color=Grey,fontSize=11.sp)};IconButton(onClick={p?.seekToPreviousMediaItem()}){Icon(Icons.Default.SkipPrevious,null,tint=Color.White)};IconButton(onClick={if(playing)p?.pause()else p?.play()}){Icon(if(playing)Icons.Default.Pause else Icons.Default.PlayArrow,null,tint=Gold)};IconButton(onClick={p?.seekToNextMediaItem()}){Icon(Icons.Default.SkipNext,null,tint=Color.White)}};LinearProgressIndicator(progress={if(dur>0)(pos.toFloat()/dur).coerceIn(0f,1f)else 0f},Modifier.fillMaxWidth().height(2.dp),color=Gold,trackColor=Card)}
    }}
    if(playlistDialog)AlertDialog(onDismissRequest={playlistDialog=false},containerColor=Card,title={Text("Create playlist",color=Color.White)},text={OutlinedTextField(newName,{newName=it},label={Text("Name")})},confirmButton={TextButton(onClick={if(newName.isNotBlank()){lists[newName.trim()]= mutableListOf();store.saveLists(lists);lists=lists.toMutableMap();newName="";playlistDialog=false}}){Text("Create",color=Gold)}})
    if(addSong!=null)AlertDialog(onDismissRequest={addSong=null},containerColor=Card,title={Text("Add to playlist",color=Color.White)},text={Column{lists.keys.forEach{n->TextButton(onClick={val ids=lists[n]?: mutableListOf();if(!ids.contains(addSong!!.id))ids.add(addSong!!.id);lists[n]=ids;store.saveLists(lists);lists=lists.toMutableMap();addSong=null}){Text(n,color=Gold)}};if(lists.isEmpty())Text("Create a playlist first",color=Grey)}},confirmButton={TextButton(onClick={addSong=null}){Text("Close",color=Gold)}})
    if(eq)EqDialog{eq=false}
    if(queue)QueueDialog(p){queue=false}
    if(sleepDialog)AlertDialog(onDismissRequest={sleepDialog=false},containerColor=Card,title={Text("Sleep timer",color=Color.White)},text={OutlinedTextField(sleepText,{sleepText=it.filter(Char::isDigit)},label={Text("Minutes")})},confirmButton={TextButton(onClick={val m=(sleepText.toLongOrNull()?:30).coerceIn(1,600);sleepJob?.cancel();sleepJob=CoroutineScope(Dispatchers.Main).launch{delay(m*60000);p?.pause()};sleepDialog=false}){Text("Start",color=Gold)}},dismissButton={TextButton(onClick={sleepJob?.cancel();sleepDialog=false}){Text("Cancel",color=Gold)}})
}

@Composable private fun Heading(t:String){Text(t,color=Color.White,fontSize=18.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(start=18.dp,top=16.dp,bottom=10.dp))}
@Composable private fun Tile(t:String,v:String,click:()->Unit){Column(Modifier.width(105.dp).clip(RoundedCornerShape(14.dp)).background(Card).clickable{click()}.padding(12.dp)){Text(v,color=Gold,fontSize=22.sp,fontWeight=FontWeight.Bold);Text(t,color=Grey,fontSize=12.sp)}}
@Composable private fun SongLine(s:Song,f:Boolean,play:()->Unit,fav:()->Unit,add:()->Unit){Row(Modifier.fillMaxWidth().clickable{play()}.padding(horizontal=16.dp,vertical=7.dp),verticalAlignment=Alignment.CenterVertically){Cover(s.artworkUri,Modifier.size(50.dp).clip(RoundedCornerShape(10.dp)));Column(Modifier.weight(1f).padding(start=11.dp)){Text(s.title,color=Color.White,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis);Text(s.artist,color=Grey,fontSize=12.sp,maxLines=1)};IconButton(onClick=fav){Icon(if(f)Icons.Default.Favorite else Icons.Default.FavoriteBorder,null,tint=if(f)Gold else Grey)};var menu by remember{mutableStateOf(false)};Box{IconButton(onClick={menu=true}){Icon(Icons.Default.MoreVert,null,tint=Grey)};DropdownMenu(menu,{menu=false}){DropdownMenuItem(text={Text("Add to playlist")},onClick={menu=false;add()})}}}}
@Composable private fun Cover(uri:Uri?,mod:Modifier){val c=LocalContext.current;val bmp=remember(uri){try{uri?.let{c.contentResolver.openInputStream(it)?.use{st->BitmapFactory.decodeStream(st)?.asImageBitmap()}}}catch(_:Exception){null}};Box(mod.background(Brush.linearGradient(listOf(Color(0xFF494033),Color(0xFF191B1F)))),contentAlignment=Alignment.Center){if(bmp!=null)Image(bmp,null,Modifier.fillMaxSize(),contentScale=androidx.compose.ui.layout.ContentScale.Crop)else Icon(Icons.Default.Album,null,tint=Gold)}}
@Composable private fun EqDialog(close:()->Unit){val s=PlaybackService.active;var preset by remember{mutableStateOf("Flat")};var bass by remember{mutableFloatStateOf(0f)};var space by remember{mutableFloatStateOf(0f)};AlertDialog(onDismissRequest=close,containerColor=Card,title={Text("Sound studio",color=Color.White)},text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState())){listOf("Flat","Bass","Rock","Pop","Classical","Jazz","Vocal").forEach{FilterChip(selected=preset==it,onClick={preset=it;val v=when(it){"Bass"->listOf(700,500,100,-100,-200);"Rock"->listOf(500,250,-100,300,500);"Pop"->listOf(-100,250,500,250,-100);"Classical"->listOf(300,200,-100,200,400);"Jazz"->listOf(400,200,-100,200,300);"Vocal"->listOf(-200,100,500,400,100);else->listOf(0,0,0,0,0)};repeat(s?.bandCount()?:0){i->s?.setBand(i,v.getOrElse(i){0})}},label={Text(it)},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Gold,selectedLabelColor=Bg))};repeat(s?.bandCount()?:0){i->var v by remember{mutableFloatStateOf((s?.bandLevel(i)?:0).toFloat())};Text("Band ${i+1}",color=Grey);Slider(v,{v=it;s?.setBand(i,it.toInt());preset="Custom"},valueRange=(s?.bandRange()?.first?:-1500).toFloat()..(s?.bandRange()?.second?:1500).toFloat())};Text("Bass boost",color=Color.White);Slider(bass,{bass=it;s?.setBass((it*1000).toInt())});Text("Virtualizer",color=Color.White);Slider(space,{space=it;s?.setVirtual((it*1000).toInt())}},confirmButton={TextButton(onClick=close){Text("Done",color=Gold)}})}
@Composable private fun QueueDialog(p:MediaController?,close:()->Unit){AlertDialog(onDismissRequest=close,containerColor=Card,title={Text("Play queue",color=Color.White)},text={Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState())){if(p==null||p.mediaItemCount==0)Text("Queue is empty",color=Grey)else repeat(p.mediaItemCount){i->val m=p.getMediaItemAt(i).mediaMetadata;Row(verticalAlignment=Alignment.CenterVertically){Text("${i+1}",color=Grey);Column(Modifier.weight(1f).padding(8.dp)){Text(m.title?.toString()?:"Unknown",color=Color.White);Text(m.artist?.toString()?:"",color=Grey,fontSize=11.sp)};IconButton(onClick={if(i>0)p.moveMediaItem(i,i-1)}){Icon(Icons.Default.KeyboardArrowUp,null,tint=Gold)};IconButton(onClick={if(i<p.mediaItemCount-1)p.moveMediaItem(i,i+1)}){Icon(Icons.Default.KeyboardArrowDown,null,tint=Gold)};IconButton(onClick={p.removeMediaItem(i)}){Icon(Icons.Default.Close,null,tint=Grey)}}}}},confirmButton={TextButton(onClick=close){Text("Done",color=Gold)}})}
private fun scan(c:Context):List<Song>{val u=MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;val cols=arrayOf(MediaStore.Audio.Media._ID,MediaStore.Audio.Media.TITLE,MediaStore.Audio.Media.ARTIST,MediaStore.Audio.Media.ALBUM,MediaStore.Audio.Media.DURATION,MediaStore.Audio.Media.ALBUM_ID,MediaStore.Audio.Media.DATA);val out=mutableListOf<Song>();c.contentResolver.query(u,cols,"${MediaStore.Audio.Media.IS_MUSIC} != 0",null,"${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use{x->while(x.moveToNext()){val id=x.getLong(0);val path=x.getString(6)?:"";out+=Song(id,x.getString(1)?:"Unknown title",x.getString(2)?:"Unknown artist",x.getString(3)?:"Unknown album",x.getLong(4),Uri.withAppendedPath(u,id.toString()),x.getLong(5),File(path).parentFile?.name?:"Music")}};return out}
private fun fmt(ms:Long):String{val s=(ms/1000).coerceAtLeast(0);return "%d:%02d".format(s/60,s%60)}
