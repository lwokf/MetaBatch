package app.metabatch

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MetaBatch · Modern UI V3.0 — 六屏移动端 UI (bo 2026-11-22 定稿，
 * ChatGPT 分享页 "UI设计重构方案" 最后一版)。
 * 定位：Creative Productivity — 简洁、专业、有质感。
 * 视觉：高级紫主色，雾白/浅灰底，减少装饰性渐变，强化留白与信息层级。
 * 六屏：首页 / 选择图片 / 编辑元数据 / 输出设置 / 导出进度 / 导出完成。
 * 另保留一点：点缩略图查看原信息（详情页）——bo 2026-11-22 03:36 定，
 * 先看原信息再决定改什么，六屏稿里没有单独画出，但功能必须在。
 */

/**
 * V3.0 设计常量。原 V1.0 规范表的 #7048F5 已被 V3.0 明确替代，不沿用。
 * v0.6.0：跟随系统深色模式。dark 是 State，所有颜色都是 getter，
 * 读到 dark 的组合函数会在主题切换时自动重组；调用处 Mb.X 不用改。
 */
object Mb {
    var dark by mutableStateOf(false)
    val Purple get() = Color(0xFF6D5DF6)
    val PurpleDark get() = if (dark) Color(0xFFC3B8FF) else Color(0xFF5B4BE0)
    val PurpleSoft get() = if (dark) Color(0xFF26233D) else Color(0xFFF4F2FF)
    val ChipBg get() = if (dark) Color(0xFF2E2A4D) else Color(0xFFEDEBFF)
    val Page get() = if (dark) Color(0xFF121215) else Color(0xFFF7F7F9)
    val Card get() = if (dark) Color(0xFF1E1E24) else Color.White
    val FieldBg get() = if (dark) Color(0xFF2A2A32) else Color(0xFFF2F2F5)
    val Ink get() = if (dark) Color(0xFFF2F2F5) else Color(0xFF14141A)
    val Sub get() = if (dark) Color(0xFF9A9AA5) else Color(0xFF8A8A93)
    val Line get() = if (dark) Color(0xFF2E2E36) else Color(0xFFF0F0F3)
    val Border get() = if (dark) Color(0xFF3A3A42) else Color(0xFFD9D9E0)
    val OrangeSoft get() = if (dark) Color(0xFF3A2A12) else Color(0xFFFFF4E0)
    val Dark get() = Color(0xFF17171C)
    val DarkCard get() = Color(0xFF232329)
    val DarkSub get() = Color(0xFF9A9AA5)
    val Orange get() = Color(0xFFF59E0B)
    val DisabledBg get() = if (dark) Color(0xFF2E2E36) else Color(0xFFE4E4E8)
}

/** 主题包装：Material3 配色跟随深色模式（下拉菜单、开关等组件用它）。 */
@Composable
fun MbTheme(content: @Composable () -> Unit) {
    val scheme = if (Mb.dark) darkColorScheme(
        primary = Mb.Purple,
        background = Mb.Page,
        surface = Mb.Card,
        onBackground = Mb.Ink,
        onSurface = Mb.Ink,
        surfaceVariant = Mb.FieldBg,
        onSurfaceVariant = Mb.Sub
    ) else lightColorScheme(
        primary = Mb.Purple,
        background = Mb.Page,
        surface = Mb.Card,
        onBackground = Mb.Ink,
        onSurface = Mb.Ink,
        surfaceVariant = Mb.FieldBg,
        onSurfaceVariant = Mb.Sub
    )
    MaterialTheme(colorScheme = scheme) { content() }
}

enum class Screen { Home, Picker, Templates, History, Settings, Progress, Done }

/** 统一元数据表单状态：首页与编辑页共用同一份，改一处两处都变。 */
class FormState {
    var title by mutableStateOf("")
    var description by mutableStateOf("")
    var keywords by mutableStateOf("风景、旅行、自然")
    var creator by mutableStateOf("")
    var rights by mutableStateOf("")
    var source by mutableStateOf("")

    fun values() = MetadataValues(
        title, description, parseKeywords(keywords), creator, rights, source
    )

    /** “模板”下拉：默认模板 = 恢复初始值。 */
    fun applyDefault() {
        title = ""; description = ""; keywords = "风景、旅行、自然"
        creator = ""; rights = ""; source = ""
    }

    /** “模板”下拉：清空表单 = 全部留空（空白 = 清除原值，v0.4.4 规则）。 */
    fun clearAll() {
        title = ""; description = ""; keywords = ""
        creator = ""; rights = ""; source = ""
    }
}

/** 输出设置状态（输出设置屏）。 */
class ExportSettings {
    var namingSuffix by mutableStateOf(true)
    var keepOriginal by mutableStateOf(true)
    var overwrite by mutableStateOf(false)
    var format by mutableStateOf(ExportFormat.ORIGINAL)

    fun options() = ExportOptions(namingSuffix, overwrite, format, keepOriginal)
}

fun parseKeywords(keywords: String): List<String> =
    keywords.split(',', '，', '、', ';', '；').map { it.trim() }
        .filter { it.isNotEmpty() }.distinct()

fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

/** 读出上次闪退的崩溃原因（异常第一行），读完就删，只显示一次。 */
fun readLastCrash(context: Context): String {
    val file = java.io.File(context.filesDir, "crash-log.txt")
    if (!file.exists()) return ""
    val first = runCatching { file.readLines().firstOrNull { it.isNotBlank() }.orEmpty() }.getOrDefault("")
    runCatching { file.delete() }
    return first
}

/** 列出文件夹第一层的图片（JPG/PNG/WebP），按文件名排序；读不到返回空表。 */
fun listImagesInFolder(context: Context, treeUri: Uri): List<Uri> {
    return runCatching {
        val dir = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
            ?: return emptyList()
        dir.listFiles()
            .filter { it.isFile && BatchProcessor.isSupportedImageName(it.name.orEmpty()) }
            .sortedBy { it.name.orEmpty() }
            .map { it.uri }
    }.getOrDefault(emptyList())
}

/** 在系统文件管理器里打开输出目录；打不开则退回目录选择器。 */
fun openOutputFolder(context: Context, treeUri: Uri) {
    runCatching {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setData(docUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }.onFailure {
        runCatching {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, treeUri)
            }
            context.startActivity(intent)
        }
    }
}

/** 手绘文件夹小图标：material-icons-core 里没有 Folder，改手绘，免得多拉一个大依赖。 */
@Composable
fun FolderGlyph(modifier: Modifier = Modifier, tint: Color = Mb.Purple) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.08f, h * 0.12f),
            size = Size(w * 0.38f, h * 0.22f),
            cornerRadius = CornerRadius(w * 0.06f)
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.08f, h * 0.28f),
            size = Size(w * 0.84f, h * 0.62f),
            cornerRadius = CornerRadius(w * 0.08f)
        )
    }
}

/** 手绘图片小图标：material-icons-core 里没有 PhotoLibrary / Image，改手绘。 */
@Composable
fun ImageGlyph(modifier: Modifier = Modifier, tint: Color = Mb.Purple) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        drawRoundRect(
            color = tint,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(w - stroke, h - stroke),
            cornerRadius = CornerRadius(w * 0.12f),
            style = Stroke(stroke)
        )
        drawCircle(color = tint, radius = w * 0.09f, center = Offset(w * 0.36f, h * 0.36f))
        val path = Path().apply {
            moveTo(w * 0.10f, h * 0.82f)
            lineTo(w * 0.38f, h * 0.52f)
            lineTo(w * 0.58f, h * 0.70f)
            lineTo(w * 0.72f, h * 0.56f)
            lineTo(w * 0.92f, h * 0.82f)
            close()
        }
        drawPath(path, tint)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 闪退留档：崩溃时把堆栈写进私有文件，再交给系统正常处理；
        // 下次启动首页会显示崩溃原因（只显示一次），方便定位。
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                java.io.File(filesDir, "crash-log.txt").writeText(e.stackTraceToString())
            }
            prevHandler?.uncaughtException(thread, e)
        }
        setContent {
            Mb.dark = isSystemInDarkTheme()
            MetaBatchScreen()
        }
    }
}

@Composable
private fun MetaBatchScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val settings = remember { ExportSettings() }

    var screen by remember { mutableStateOf(Screen.Home) }
    var pool by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    var viewing by remember { mutableStateOf<Uri?>(null) }
    var metadataMap by remember { mutableStateOf<Map<Uri, PhotoMetadata>>(emptyMap()) }
    var outputFolder by remember { mutableStateOf(OutputFolderStore.load(context)) }
    var outputFolderName by remember(outputFolder) {
        mutableStateOf(
            outputFolder?.let { uri ->
                runCatching {
                    androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)?.name
                }.getOrNull() ?: uri.lastPathSegment.orEmpty()
            }.orEmpty()
        )
    }
    var exportRunId by remember { mutableStateOf(0) }
    var exportResults by remember { mutableStateOf<List<BatchItemResult>>(emptyList()) }
    var exportStartedAt by remember { mutableStateOf(0L) }
    var exportEndedAt by remember { mutableStateOf(0L) }
    var notice by remember { mutableStateOf("") }
    var templates by remember { mutableStateOf(TemplateStore.load(context)) }
    var history by remember { mutableStateOf(HistoryStore.load(context)) }

    // Read each added photo's original metadata once, in the background.
    // Chunked: a whole folder can bring hundreds of photos. Reading 25 at
    // a time with one state update per chunk (instead of one coroutine +
    // one full recomposition per photo) keeps the UI alive.
    LaunchedEffect(pool) {
        val missing = pool.filter { it !in metadataMap }
        if (missing.isEmpty()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            missing.chunked(25).forEach { chunk ->
                val metas = chunk.map { uri ->
                    async { uri to MetadataReader.read(context, uri) }
                }.awaitAll()
                metadataMap = metadataMap + metas.toMap()
            }
        }
    }

    // 上次闪退过的话，首页先把崩溃原因亮出来（只显示一次）。
    LaunchedEffect(Unit) {
        val crash = readLastCrash(context)
        if (crash.isNotBlank()) notice = "上次闪退：$crash"
    }

    fun takeReadPermission(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    // 首页“选择图片”：换一批（沿用 v0.4.x 行为：重新选 = 换掉整批）。
    val pickImagesReplace = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            pool = uris
            selected = uris.toSet()
            uris.forEach { takeReadPermission(it) }
            notice = "已加入 ${uris.size} 张图片"
        }
    }
    // 选图页“＋”：追加到当前这批，并默认勾选新加的。
    val pickImagesAppend = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            pool = (pool + uris).distinct()
            selected = selected + uris
            uris.forEach { takeReadPermission(it) }
            notice = "已追加 ${uris.size} 张图片"
        }
    }
    // 首页“选择文件夹”：整批导入一个文件夹第一层的所有图片（系统选图器
    // 既不能选文件夹也没有全选，整批导入只能走这里）。枚举在 IO 线程做，
    // 文件夹一大会卡主线程——之前闪退的头号嫌疑。
    val pickInputFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        notice = "正在读取文件夹…"
        scope.launch(Dispatchers.IO) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val files = runCatching { listImagesInFolder(context, uri) }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) {
                if (files.isEmpty()) {
                    notice = "这个文件夹里没有图片（只看第一层，支持 JPG / PNG / WebP）"
                } else {
                    pool = files
                    selected = files.toSet()
                    notice = "已加入 ${files.size} 张图片"
                }
            }
        }
    }
    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        outputFolder = uri
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            OutputFolderStore.save(context, uri)
        }
    }

    // 状态栏/导航栏跟随主题：深色时图标变白，导航栏底色跟页面走。
    val view = LocalView.current
    SideEffect {
        val activity = view.context as? Activity ?: return@SideEffect
        val window = activity.window
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = Mb.Page.toArgb()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(
                if (Mb.dark) 0 else (WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS),
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                if (Mb.dark) 0 else (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
        }
    }

    val exportSources = pool.filter { it in selected }
    val exportSourceNames = exportSources.map { uri ->
        metadataMap[uri]?.fileName ?: uri.lastPathSegment?.substringAfterLast('/') ?: "image"
    }

    // 导出跑完：不保留原图时，删掉导成功的原图并移出当前批次，再进完成页。
    val onExportFinished: (List<BatchItemResult>, Long, Long) -> Unit =
        { results, startedAt, endedAt ->
            exportResults = results
            exportStartedAt = startedAt
            exportEndedAt = endedAt
            // 记一条历史（最新在前，最多留 50 条）。
            val record = ExportRecord(
                timeMs = endedAt,
                total = results.size,
                success = results.count { it.success },
                failed = results.count { !it.success },
                folderName = outputFolderName,
                folderUri = outputFolder?.toString().orEmpty(),
                durationMs = (endedAt - startedAt).coerceAtLeast(0)
            )
            history = HistoryStore.addRecord(history, record)
            HistoryStore.save(context, history)
            if (!settings.keepOriginal) {
                val deleted = exportSources.filterIndexed { i, _ ->
                    results.getOrNull(i)?.success == true
                }
                deleted.forEach { uri ->
                    runCatching {
                        DocumentsContract.deleteDocument(context.contentResolver, uri)
                    }
                }
                if (deleted.isNotEmpty()) {
                    val gone = deleted.toSet()
                    pool = pool - gone
                    selected = selected - gone
                    metadataMap = metadataMap - gone
                }
            }
            screen = Screen.Done
        }

    val viewingUri = viewing
    if (viewingUri != null) {
        PhotoDetailScreen(
            uris = pool,
            initialUri = viewingUri,
            metadataMap = metadataMap,
            onBack = { viewing = null },
            onFillForm = { m ->
                form.title = m.xmp.title
                form.description = m.xmp.description.ifBlank { m.description }
                form.keywords = m.xmp.keywords.joinToString("、")
                form.creator = m.xmp.creator.ifBlank { m.artist }
                form.rights = m.xmp.rights.ifBlank { m.copyright }
                form.source = m.xmp.source
                viewing = null
            }
        )
        return
    }

    // 系统返回键：子页面先回首页，首页才退出应用（之前子页面没拦，返回键直接退出了应用）。
    // 详情页有自己的 BackHandler（后注册优先，先关详情）；进度页自己拦着。
    BackHandler(enabled = screen != Screen.Home && screen != Screen.Progress) {
        screen = Screen.Home
    }

    val navigate: (Screen) -> Unit = { screen = it }

    MbTheme {
        when (screen) {
            Screen.Home -> HomeScreen(
                pool = pool, selected = selected, form = form, notice = notice,
                onPickReplace = { pickImagesReplace.launch(arrayOf("image/*")) },
                onPickAppend = { pickImagesAppend.launch(arrayOf("image/*")) },
                onPickFolder = { pickInputFolder.launch(null) },
                onViewPhoto = { viewing = it },
                onOpenPicker = { screen = Screen.Picker },
                onOpenTemplates = { screen = Screen.Templates },
                onExport = { screen = Screen.Settings },
                current = screen, onNavigate = navigate
            )
            Screen.Picker -> PickerScreen(
                pool = pool, selected = selected,
                onToggle = { uri ->
                    selected = if (uri in selected) selected - uri else selected + uri
                },
                onSelectAll = { selected = pool.toSet() },
                onClearAll = { pool = emptyList(); selected = emptySet() },
                onPickAppend = { pickImagesAppend.launch(arrayOf("image/*")) },
                onViewPhoto = { viewing = it },
                onConfirm = { screen = Screen.Home },
                onBack = { screen = Screen.Home },
                current = screen, onNavigate = navigate
            )
            Screen.Templates -> TemplatesScreen(
                templates = templates,
                onSaveTemplate = { name ->
                    val t = Template(
                        name, form.title, form.description, form.keywords,
                        form.creator, form.rights, form.source
                    )
                    templates = templates.filter { it.name != name } + t
                    TemplateStore.save(context, templates)
                    notice = "模板「$name」已保存"
                },
                onApplyTemplate = { t ->
                    form.title = t.title
                    form.description = t.description
                    form.keywords = t.keywords
                    form.creator = t.creator
                    form.rights = t.rights
                    form.source = t.source
                    notice = "已应用模板「${t.name}」"
                    screen = Screen.Home
                },
                onDeleteTemplate = { t ->
                    templates = templates - t
                    TemplateStore.save(context, templates)
                },
                onBack = { screen = Screen.Home },
                current = screen, onNavigate = navigate
            )
            Screen.History -> HistoryScreen(
                records = history,
                onOpenFolder = { uriStr ->
                    runCatching { openOutputFolder(context, Uri.parse(uriStr)) }
                },
                onClear = {
                    history = emptyList()
                    HistoryStore.save(context, history)
                },
                onBack = { screen = Screen.Home },
                current = screen, onNavigate = navigate
            )
            Screen.Settings -> OutputSettingsScreen(
                outputFolder = outputFolder, outputFolderName = outputFolderName,
                settings = settings, selectedCount = selected.size,
                onPickFolder = { pickFolder.launch(null) },
                onUnpinFolder = {
                    OutputFolderStore.clear(context)
                    outputFolder = null
                },
                onStartExport = { exportRunId++; screen = Screen.Progress },
                onBack = { screen = Screen.Home },
                current = screen, onNavigate = navigate
            )
            Screen.Progress -> ProgressScreen(
                sources = exportSources, sourceNames = exportSourceNames,
                outputFolder = outputFolder, values = form.values(),
                options = settings.options(), exportRunId = exportRunId,
                onExportFinished = onExportFinished
            )
            Screen.Done -> DoneScreen(
                results = exportResults,
                outputFolder = outputFolder,
                elapsedMs = exportEndedAt - exportStartedAt,
                onViewFiles = { outputFolder?.let { openOutputFolder(context, it) } },
                onHome = { screen = Screen.Home }
            )
        }
    }
}

/* ---------------- 共用组件 ---------------- */

/** 主按钮：紫底大圆角白字。 */
@Composable
fun MbPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(52.dp),
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Mb.Purple,
            disabledContainerColor = Mb.DisabledBg,
            contentColor = Color.White,
            disabledContentColor = Mb.Sub
        )
    ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
}

/** 描边按钮：白底细边框深字。 */
@Composable
fun MbOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(52.dp),
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Mb.Ink),
        border = androidx.compose.foundation.BorderStroke(1.dp, Mb.Border)
    ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
}

/** 浅灰填充输入框：上标小字 + 内容，无边框。 */
@Composable
fun MbField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    minLines: Int = 1,
    maxChars: Int? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 12.sp, color = Mb.Sub)
        Box(
            Modifier.fillMaxWidth().background(Mb.FieldBg, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 4.dp)
        ) {
            TextField(
                value = value,
                onValueChange = { v -> if (maxChars == null || v.length <= maxChars) onValueChange(v) },
                modifier = Modifier.fillMaxWidth(),
                minLines = minLines,
                singleLine = minLines == 1,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = Mb.Ink)
            )
            if (trailing != null) {
                Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
            }
        }
    }
}

/** 关键词 chips：横向滑动，点 × 删掉，点 ＋ 展开输入行。 */
@Composable
fun KeywordEditor(keywords: String, onChange: (String) -> Unit) {
    val list = parseKeywords(keywords)
    var adding by remember { mutableStateOf(false) }
    var newWord by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("关键词", fontSize = 12.sp, color = Mb.Sub)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (word in list) {
                Row(
                    Modifier.background(Mb.ChipBg, RoundedCornerShape(999.dp))
                        .padding(start = 12.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(word, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Mb.PurpleDark)
                    Text(
                        "  ×", fontSize = 13.sp, color = Mb.PurpleDark,
                        modifier = Modifier.clickable {
                            onChange(list.filter { it != word }.joinToString("、"))
                        }
                    )
                }
            }
            Row(
                Modifier.border(1.dp, Mb.Border, RoundedCornerShape(999.dp))
                    .clickable { adding = true }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) { Text("+ 添加", fontSize = 13.sp, color = Mb.Sub) }
        }
        if (adding) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = newWord,
                    onValueChange = { newWord = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入一个关键词", fontSize = 14.sp, color = Mb.Sub) },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Mb.FieldBg,
                        unfocusedContainerColor = Mb.FieldBg,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(14.dp)
                )
                TextButton(onClick = {
                    val w = newWord.trim()
                    if (w.isNotEmpty() && w !in list) {
                        onChange((list + w).joinToString("、"))
                    }
                    newWord = ""
                    adding = false
                }) { Text("添加", color = Mb.Purple, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/** 圆角缩略图；checked 不为空时右上角带勾选圆圈（点圆圈切换勾选）。 */
@Composable
fun Thumb(
    uri: Uri,
    modifier: Modifier = Modifier,
    checked: Boolean? = null,
    onClick: () -> Unit,
    onCheckClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) { ImageLoader.decodeSampled(context, uri, 256) }
    }
    Box(
        modifier.aspectRatio(1f).clip(RoundedCornerShape(14.dp))
            .background(Mb.FieldBg).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text("…", color = Mb.Sub)
        }
        if (checked != null) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp)
                    .clip(CircleShape)
                    .background(if (checked) Mb.Purple else Color.White)
                    .border(1.5.dp, if (checked) Mb.Purple else Mb.Border, CircleShape)
                    .clickable { onCheckClick?.invoke() },
                contentAlignment = Alignment.Center
            ) {
                if (checked) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(15.dp))
            }
        }
    }
}

/** 模板图标：带横线的文档（手绘，core 库里没有合适的）。 */
@Composable
fun TemplateGlyph(modifier: Modifier = Modifier, tint: Color = Mb.Purple) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        drawRoundRect(
            color = tint,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(w - stroke, h - stroke),
            cornerRadius = CornerRadius(w * 0.12f),
            style = Stroke(stroke)
        )
        val x0 = w * 0.30f
        val x1 = w * 0.70f
        for (y in listOf(0.36f, 0.52f, 0.68f)) {
            drawLine(
                color = tint,
                start = Offset(x0, h * y),
                end = Offset(x1, h * y),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}

/** 历史图标：时钟（手绘）。 */
@Composable
fun HistoryGlyph(modifier: Modifier = Modifier, tint: Color = Mb.Purple) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        drawCircle(
            color = tint,
            radius = w * 0.5f - stroke / 2,
            style = Stroke(stroke)
        )
        val c = Offset(w / 2, h / 2)
        val r = w * 0.26f
        drawLine(tint, c, Offset(c.x, c.y - r), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(tint, c, Offset(c.x + r * 0.72f, c.y + r * 0.55f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

/** “模板”下拉菜单：默认模板 / 清空表单 / 管理模板。 */
@Composable
fun TemplateMenu(form: FormState, onManageTemplates: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text("模板 ▾", color = Mb.Purple, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("默认模板") },
                onClick = { form.applyDefault(); expanded = false }
            )
            DropdownMenuItem(
                text = { Text("清空表单") },
                onClick = { form.clearAll(); expanded = false }
            )
            DropdownMenuItem(
                text = { Text("管理模板…") },
                onClick = { onManageTemplates(); expanded = false }
            )
        }
    }
}

/** 底部四栏导航：首页 / 模板 / 历史 / 设置。 */
@Composable
fun BottomNav(current: Screen, onNavigate: (Screen) -> Unit) {
    NavigationBar(containerColor = Mb.Card) {
        val items = listOf(
            Screen.Home to "首页",
            Screen.Templates to "模板",
            Screen.History to "历史",
            Screen.Settings to "设置"
        )
        for ((screen, label) in items) {
            NavigationBarItem(
                selected = current == screen,
                onClick = { if (current != screen) onNavigate(screen) },
                icon = {
                    val tint = if (current == screen) Mb.Purple else Mb.Sub
                    when (screen) {
                        Screen.Home -> Icon(Icons.Filled.Home, label, tint = tint)
                        Screen.Templates -> TemplateGlyph(Modifier.size(24.dp), tint)
                        Screen.History -> HistoryGlyph(Modifier.size(24.dp), tint)
                        Screen.Settings -> Icon(Icons.Filled.Settings, label, tint = tint)
                        else -> {}
                    }
                },
                label = { Text(label, fontSize = 11.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Mb.Purple,
                    selectedTextColor = Mb.Purple,
                    unselectedIconColor = Mb.Sub,
                    unselectedTextColor = Mb.Sub,
                    indicatorColor = Mb.ChipBg
                )
            )
        }
    }
}

/** 元数据表单（首页与编辑页共用）：标题、描述、关键词、作者、版权、来源。 */
@Composable
fun MetadataForm(form: FormState, showCount: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        MbField("标题", form.title, { form.title = it })
        MbField(
            "描述", form.description, { form.description = it },
            minLines = 2, maxChars = 500,
            trailing = if (showCount) {
                { Text("${form.description.length}/500", fontSize = 11.sp, color = Mb.Sub) }
            } else null
        )
        KeywordEditor(form.keywords) { form.keywords = it }
        MbField("作者", form.creator, { form.creator = it })
        MbField("版权", form.rights, { form.rights = it })
        MbField("来源", form.source, { form.source = it })
    }
}

/* ---------------- 屏 1：首页 / 主编辑 ---------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    pool: List<Uri>,
    selected: Set<Uri>,
    form: FormState,
    notice: String,
    onPickReplace: () -> Unit,
    onPickAppend: () -> Unit,
    onPickFolder: () -> Unit,
    onViewPhoto: (Uri) -> Unit,
    onOpenPicker: () -> Unit,
    onOpenTemplates: () -> Unit,
    onExport: () -> Unit,
    current: Screen,
    onNavigate: (Screen) -> Unit
) {
    Scaffold(
        containerColor = Mb.Page,
        bottomBar = { BottomNav(current, onNavigate) }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize()
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            // 品牌栏
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Mb.Purple),
                    contentAlignment = Alignment.Center
                ) { Text("M", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold) }
                Column(Modifier.padding(start = 12.dp)) {
                    Text("MetaBatch", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Mb.Ink)
                    Text("图片元数据批量编辑器", fontSize = 12.sp, color = Mb.Sub)
                }
            }

            // 添加图片大卡片
            Column(
                Modifier.fillMaxWidth().background(Mb.PurpleSoft, RoundedCornerShape(20.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FolderGlyph(Modifier.size(36.dp), Mb.Purple)
                Text("添加图片", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Mb.Ink)
                Text("先看原信息，再决定改什么", fontSize = 12.sp, color = Mb.Sub)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MbPrimaryButton("选择图片", onClick = onPickReplace, modifier = Modifier.weight(1f))
                    MbOutlineButton("选择文件夹", onClick = onPickFolder, modifier = Modifier.weight(1f))
                }
            }
            if (notice.isNotBlank()) {
                Text(
                    notice, fontSize = 12.sp, color = Mb.Sub,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }

            // 已选缩略图
            if (pool.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().background(Mb.Card, RoundedCornerShape(20.dp)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenPicker() },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("已选 ${selected.size} 张", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Mb.Ink)
                        Text("  / 共 ${pool.size} 张", fontSize = 12.sp, color = Mb.Sub)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Mb.Sub)
                    }
                    val shown = pool.filter { it in selected }.take(4)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (uri in shown) {
                            Thumb(uri, Modifier.weight(1f), onClick = { onViewPhoto(uri) })
                        }
                        repeat((4 - shown.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
                        Box(
                            Modifier.weight(1f).aspectRatio(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.5.dp, Mb.Border, RoundedCornerShape(14.dp))
                                .clickable { onPickAppend() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Add, null, tint = Mb.Purple, modifier = Modifier.size(28.dp))
                        }
                    }
                    if (selected.isEmpty()) {
                        Text("还没勾选图片，去选图页勾选要导出的", fontSize = 12.sp, color = Mb.Sub)
                    }
                }
            }

            // 元数据编辑
            Column(
                Modifier.fillMaxWidth().background(Mb.Card, RoundedCornerShape(20.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("元数据编辑", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Mb.Ink)
                    Spacer(Modifier.weight(1f))
                    TemplateMenu(form, onOpenTemplates)
                }
                MetadataForm(form, showCount = false)
                Text(
                    "不填的字段就是空白，会清除该项原值。中文原样写入，不会变问号。",
                    fontSize = 12.sp, color = Mb.Sub
                )
            }

            // 批量导出
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                MbPrimaryButton(
                    "批量导出",
                    onClick = onExport,
                    enabled = selected.isNotEmpty()
                )
                Text(
                    if (selected.isNotEmpty()) "将导出 ${selected.size} 张图片" else "先选图片",
                    fontSize = 12.sp, color = Mb.Sub,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

/* ---------------- 详情页（六屏之外，bo 定必须保留） ---------------- */

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PhotoDetailScreen(
    uris: List<Uri>,
    initialUri: Uri,
    metadataMap: Map<Uri, PhotoMetadata>,
    onBack: () -> Unit,
    onFillForm: (PhotoMetadata) -> Unit
) {
    // 左右滑动切换图片：前后一页预加载（解码在 IO 线程），滑动不卡顿。
    val pagerState = rememberPagerState(
        initialPage = uris.indexOf(initialUri).coerceAtLeast(0)
    ) { uris.size }
    BackHandler { onBack() }
    MbTheme {
        Scaffold(
            containerColor = Mb.Page,
            topBar = {
                TopAppBar(
                    title = {
                        val currentMeta = metadataMap[uris.getOrNull(pagerState.currentPage)]
                        Column {
                            Text(
                                currentMeta?.fileName ?: "查看图片",
                                fontSize = 17.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            if (uris.size > 1) {
                                Text(
                                    "${pagerState.currentPage + 1} / ${uris.size}",
                                    fontSize = 12.sp, color = Mb.Sub
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Filled.ArrowBack, "返回", tint = Mb.Ink)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Mb.Card)
                )
            }
        ) { padding ->
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                modifier = Modifier.padding(padding).fillMaxSize()
            ) { page ->
                val uri = uris[page]
                DetailPage(uri = uri, metadata = metadataMap[uri], onFillForm = onFillForm)
            }
        }
    }
}

/** 详情页的一页：一张图的原图 + 原信息 + 填入按钮。各自记自己的解码结果。 */
@Composable
private fun DetailPage(
    uri: Uri,
    metadata: PhotoMetadata?,
    onFillForm: (PhotoMetadata) -> Unit
) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) { ImageLoader.decodeSampled(context, uri, 2048) }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bmp.asImageBitmap(), null, Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth
            )
        } else {
            Box(
                Modifier.fillMaxWidth().height(240.dp).background(Mb.FieldBg),
                contentAlignment = Alignment.Center
            ) { Text("图片加载中…", color = Mb.Sub) }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (metadata == null) {
                Text("原信息读取中…", color = Mb.Sub)
            } else {
                Column(
                    Modifier.fillMaxWidth().background(Mb.Card, RoundedCornerShape(20.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("原信息", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Mb.Ink)
                    MetaRow("文件名", metadata.fileName)
                    MetaRow("类型", metadata.mimeType)
                    MetaRow("大小", if (metadata.fileSize >= 0) formatSize(metadata.fileSize) else "")
                    MetaRow("尺寸", if (metadata.width > 0) "${metadata.width} × ${metadata.height}" else "")
                    MetaRow("拍摄时间", metadata.dateTime)
                    MetaRow("相机", listOf(metadata.make, metadata.model).filter { it.isNotBlank() }.joinToString(" "))
                    MetaRow("软件", metadata.software)
                    MetaRow("方向", metadata.orientation)
                    MetaRow("位置", metadata.gps)
                    MetaRow("标题", metadata.xmp.title)
                    MetaRow("描述", metadata.xmp.description.ifBlank { metadata.description })
                    MetaRow("关键词", metadata.xmp.keywords.joinToString("、"))
                    MetaRow("作者", metadata.xmp.creator.ifBlank { metadata.artist })
                    MetaRow("版权", metadata.xmp.rights.ifBlank { metadata.copyright })
                    MetaRow("来源", metadata.xmp.source)
                }
                MbPrimaryButton("把原值填入编辑表单", onClick = { onFillForm(metadata) })
                Text(
                    "填入后回到首页，可以在原值基础上改，再批量导出。原图不会被改动。",
                    fontSize = 12.sp, color = Mb.Sub
                )
            }
        }
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                label, Modifier.width(64.dp), fontSize = 14.sp,
                fontWeight = FontWeight.Bold, color = Mb.Sub
            )
            Text(
                if (value.isBlank()) "无" else value, Modifier.weight(1f),
                fontSize = 14.sp, color = Mb.Ink
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Mb.Line))
    }
}
