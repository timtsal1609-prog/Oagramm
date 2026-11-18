@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.messenger

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// ---------- Данные ----------
enum class Kind { USER, GROUP, CHANNEL }

data class AppFile(val name: String, val size: Long, val uri: String? = null)
data class Msg(val id: Long, val from: String, val text: String = "", val app: AppFile? = null, val t: Long = System.currentTimeMillis())

class Chat(
    val id: Int, val kind: Kind, val name: String, val desc: String = "",
    joined: Boolean, val own: Boolean = false, val pub: Boolean = false,
    val members: List<String> = emptyList()
) {
    var joined by mutableStateOf(joined)
    val msgs = mutableStateListOf<Msg>()
}

object Store {
    private var seq = 100L
    private var cid = 10
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val replies = listOf("Понял 👍", "Интересно!", "Ха-ха, согласен", "Давай потом обсудим", "Круто, спасибо", "Ок")
    val chats = mutableStateListOf<Chat>()
    val saved = mutableStateMapOf<Long, String>()

    fun post(c: Chat, from: String, text: String = "", app: AppFile? = null) {
        c.msgs.add(Msg(seq++, from, text, app))
    }

    fun create(kind: Kind, name: String, desc: String): Chat {
        val c = Chat(cid++, kind, name, desc, true, true, true,
            if (kind == Kind.GROUP) listOf("Аня", "Макс") else emptyList())
        chats.add(c)
        post(c, "me", if (kind == Kind.GROUP) "Группа создана 🎉" else "Канал создан. Публикуйте посты и приложения!")
        return c
    }

    fun bot(c: Chat) {
        if (c.kind == Kind.CHANNEL || c.name == "Избранное") return
        scope.launch {
            delay(900L + (0..1200).random())
            val who = if (c.kind == Kind.GROUP) c.members.random() else c.name
            post(c, who, replies.random())
        }
    }

    init {
        fun add(c: Chat, vararg m: Pair<String, Any>) {
            chats.add(c)
            m.forEach { (f, v) -> if (v is AppFile) post(c, f, app = v) else post(c, f, v as String) }
        }
        add(Chat(1, Kind.USER, "Избранное", joined = true, own = true), "me" to "Здесь можно хранить заметки")
        add(Chat(2, Kind.USER, "Бот-помощник", joined = true), "Бот-помощник" to "Привет! Напиши мне что-нибудь 🙂")
        add(Chat(3, Kind.GROUP, "Друзья", joined = true, members = listOf("Аня", "Макс", "Лёва")),
            "Аня" to "Всем привет!", "Макс" to "Кидайте приложения сюда")
        add(Chat(4, Kind.CHANNEL, "Каталог приложений", "Подборка приложений", joined = true, pub = true),
            "ch" to "Свежая подборка на сегодня:",
            "ch" to AppFile("photo_editor_2.4.1.apk", 18_400_000),
            "ch" to AppFile("player_lite_1.9.apk", 6_200_000))
        add(Chat(5, Kind.CHANNEL, "Новости Android", "Всё об Android", joined = false, pub = true),
            "ch" to "Вышло новое обновление системы",
            "ch" to AppFile("launcher_pro_5.0.apk", 9_100_000))
        add(Chat(6, Kind.GROUP, "Игры и моды", "Открытая группа", joined = false, pub = true, members = listOf("Дэн", "Ира", "Кай")),
            "Дэн" to "Кто идёт в новую игру?", "Ира" to AppFile("racing3d_0.8.apk", 52_000_000))
    }
}

// ---------- Вспомогательное ----------
fun sz(b: Long) = if (b > 1_048_576) "%.1f МБ".format(b / 1_048_576.0) else "${maxOf(1, b / 1024)} КБ"
fun hm(t: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))

fun fileInfo(ctx: Context, u: Uri): Pair<String, Long> {
    ctx.contentResolver.query(u, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val s = c.getColumnIndex(OpenableColumns.SIZE)
            return Pair(if (n >= 0) c.getString(n) else "file", if (s >= 0) c.getLong(s) else 0L)
        }
    }
    return Pair("file", 0L)
}

// Реальное сохранение файла в папку Download приложения
suspend fun download(ctx: Context, a: AppFile): String = withContext(Dispatchers.IO) {
    val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!
    val f = File(dir, a.name)
    if (a.uri != null) {
        ctx.contentResolver.openInputStream(Uri.parse(a.uri))!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
    } else f.writeText("Демо-файл: ${a.name}")
    f.absolutePath
}

// ---------- UI ----------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

@Composable
fun App() {
    var open by remember { mutableStateOf<Chat?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var showDl by remember { mutableStateOf(false) }
    BackHandler(open != null) { open = null }
    val c = open
    if (c == null) ChatList({ open = it }, { showDl = true }, { showCreate = true })
    else ChatScreen(c) { open = null }
    if (showCreate) CreateDialog({ showCreate = false }) { k, n, d -> open = Store.create(k, n, d); showCreate = false }
    if (showDl) DownloadsDialog { showDl = false }
}

@Composable
fun Avatar(name: String, size: Int = 44) {
    Box(
        Modifier.size(size.dp).clip(CircleShape)
            .background(Color.hsv(Math.floorMod(name.hashCode(), 360).toFloat(), 0.55f, 0.7f)),
        contentAlignment = Alignment.Center
    ) { Text(name.take(1), color = Color.White, fontWeight = FontWeight.Bold) }
}

fun icon(c: Chat) = when (c.kind) { Kind.CHANNEL -> "📢 "; Kind.GROUP -> "👥 "; else -> "" }

@Composable
fun ChatList(onOpen: (Chat) -> Unit, onDl: () -> Unit, onCreate: () -> Unit) {
    var q by remember { mutableStateOf("") }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Чаты") }, actions = { TextButton(onDl) { Text("⬇", fontSize = 20.sp) } }) },
        floatingActionButton = { FloatingActionButton(onCreate) { Text("＋", fontSize = 24.sp) } }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(12.dp, 4.dp),
                placeholder = { Text("Поиск чатов и каналов") }, singleLine = true)
            val list = Store.chats
                .filter { (it.joined || (q.isNotBlank() && it.pub)) && it.name.contains(q.trim(), true) }
                .sortedByDescending { it.msgs.lastOrNull()?.t ?: 0L }
            if (list.isEmpty()) Text("Ничего не найдено", Modifier.padding(24.dp))
            LazyColumn {
                items(list, key = { it.id }) { c ->
                    val last = c.msgs.lastOrNull()
                    Row(Modifier.fillMaxWidth().clickable { onOpen(c) }.padding(12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(c.name)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(icon(c) + c.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(
                                last?.let { if (it.app != null) "📦 ${it.app.name}" else it.text } ?: c.desc,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp
                            )
                        }
                        Text(if (c.joined) last?.let { hm(it.t) } ?: "" else "Найден", fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun ChatScreen(c: Chat, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ls = rememberLazyListState()
    var text by remember { mutableStateOf("") }
    val busy = remember { mutableStateListOf<Long>() }
    val canPost = c.kind != Kind.CHANNEL || c.own

    LaunchedEffect(c.msgs.size) { if (c.msgs.isNotEmpty()) ls.animateScrollToItem(c.msgs.lastIndex) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
        if (u != null) {
            try { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            val (n, s) = fileInfo(ctx, u)
            Store.post(c, "me", app = AppFile(n, s, u.toString()))
            Store.bot(c)
        }
    }

    fun send() {
        val v = text.trim(); if (v.isEmpty()) return
        text = ""; Store.post(c, "me", v); Store.bot(c)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { TextButton(onBack) { Text("←", fontSize = 22.sp) } },
                title = {
                    Column {
                        Text(icon(c) + c.name, maxLines = 1, fontSize = 17.sp)
                        Text(
                            when (c.kind) {
                                Kind.CHANNEL -> if (c.own) "ваш канал" else "канал"
                                Kind.GROUP -> "группа · ${c.members.size + 1} участн."
                                else -> "в сети"
                            }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (c.joined && c.kind != Kind.USER && !c.own)
                        TextButton({ c.joined = false; onBack() }) { Text("Покинуть") }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp, modifier = Modifier.imePadding()) {
                when {
                    !c.joined -> Button({ c.joined = true }, Modifier.fillMaxWidth().padding(8.dp)) {
                        Text(if (c.kind == Kind.CHANNEL) "Подписаться" else "Вступить")
                    }
                    !canPost -> Text("Писать в канал может только владелец", Modifier.fillMaxWidth().padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton({ pick.launch(arrayOf("*/*")) }) { Text("📎", fontSize = 20.sp) }
                        OutlinedTextField(text, { text = it }, Modifier.weight(1f),
                            placeholder = { Text("Сообщение") }, maxLines = 4)
                        TextButton(::send) { Text("➤", fontSize = 20.sp) }
                    }
                }
            }
        }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), state = ls) {
            items(c.msgs, key = { it.id }) { m ->
                val me = m.from == "me"
                Box(Modifier.fillMaxWidth().padding(10.dp, 3.dp), contentAlignment = if (me) Alignment.CenterEnd else Alignment.CenterStart) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (me) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.widthIn(max = 300.dp)
                    ) {
                        Column(Modifier.padding(12.dp, 7.dp)) {
                            if (c.kind == Kind.GROUP && !me)
                                Text(m.from, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            if (m.app != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary),
                                        contentAlignment = Alignment.Center) { Text("📦", fontSize = 20.sp) }
                                    Column(Modifier.padding(start = 10.dp)) {
                                        Text(m.app.name, fontWeight = FontWeight.SemiBold)
                                        Text(sz(m.app.size), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                when {
                                    Store.saved[m.id] != null -> Text("✓ Сохранено в «Загрузки»", fontSize = 13.sp)
                                    m.id in busy -> LinearProgressIndicator(Modifier.fillMaxWidth())
                                    else -> Button({
                                        scope.launch {
                                            busy.add(m.id)
                                            try { Store.saved[m.id] = download(ctx, m.app) }
                                            catch (e: Exception) { Toast.makeText(ctx, "Не удалось скачать", Toast.LENGTH_SHORT).show() }
                                            finally { busy.remove(m.id) }
                                        }
                                    }, contentPadding = PaddingValues(16.dp, 4.dp)) { Text("Скачать") }
                                }
                            } else Text(m.text)
                            Text(hm(m.t), Modifier.align(Alignment.End), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CreateDialog(onDismiss: () -> Unit, onOk: (Kind, String, String) -> Unit) {
    var kind by remember { mutableStateOf(Kind.GROUP) }
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Создать") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(kind == Kind.GROUP, { kind = Kind.GROUP }, { Text("👥 Группа") })
                    FilterChip(kind == Kind.CHANNEL, { kind = Kind.CHANNEL }, { Text("📢 Канал") })
                }
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("Название") }, singleLine = true)
                OutlinedTextField(desc, { desc = it.take(100) }, label = { Text("Описание") }, singleLine = true)
            }
        },
        confirmButton = { TextButton({ if (name.isNotBlank()) onOk(kind, name.trim(), desc.trim()) }) { Text("Создать") } },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } }
    )
}

@Composable
fun DownloadsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Загрузки") },
        text = {
            if (Store.saved.isEmpty()) Text("Пока ничего не скачано")
            else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Store.saved.values.forEach { Text("📦 " + it.substringAfterLast('/')) }
                Text("Папка: Android/data/<пакет>/files/Download", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Закрыть") } }
    )
}
