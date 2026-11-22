package app.metabatch

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/* ---------------- 屏 2：选择图片 / 图片管理 ---------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerScreen(
    pool: List<Uri>,
    selected: Set<Uri>,
    onToggle: (Uri) -> Unit,
    onSelectAll: () -> Unit,
    onClearAll: () -> Unit,
    onPickAppend: () -> Unit,
    onViewPhoto: (Uri) -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    current: Screen,
    onNavigate: (Screen) -> Unit
) {
    Scaffold(
        containerColor = Mb.Page,
        topBar = {
            TopAppBar(
                title = { Text("选择图片 / 视频", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "返回", tint = Mb.Ink)
                    }
                },
                actions = {
                    TextButton(onClick = onSelectAll) {
                        Text("全选", color = Mb.Purple, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = onClearAll) {
                        Text("清空", color = Mb.Sub, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Mb.Card)
            )
        },
        bottomBar = {
            Column(
                Modifier.background(Mb.Card).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("已选择 ${selected.size} 张", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Mb.Ink)
                    Row(
                        Modifier.weight(1f).padding(start = 12.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (uri in pool.filter { it in selected }) {
                            Thumb(uri, Modifier.size(40.dp), onClick = { onViewPhoto(uri) })
                        }
                    }
                }
                MbPrimaryButton("确认选择", onClick = onConfirm)
            }
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)
        ) {
            // 分类 Tab：目前只有“全部”（没有真实分类，不编造）。
            Column(Modifier.padding(vertical = 12.dp)) {
                Text(
                    "全部 ${pool.size} 张",
                    fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Mb.Purple
                )
                Box(
                    Modifier.padding(top = 6.dp).width(64.dp).height(3.dp)
                        .background(Mb.Purple, RoundedCornerShape(2.dp))
                )
            }
            if (pool.isEmpty()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
                ) {
                    Text("还没有文件", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Mb.Ink)
                    Text("从相册里选一批，先看原信息再改", fontSize = 13.sp, color = Mb.Sub)
                    MbPrimaryButton("选择图片 / 视频", onClick = onPickAppend, modifier = Modifier.padding(top = 8.dp))
                }
            } else {
                // 懒加载网格：文件夹导入可能一次几百张，只渲染看得见的。
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    items(pool, key = { it.toString() }) { uri ->
                        Thumb(
                            uri, Modifier.fillMaxWidth(),
                            checked = uri in selected,
                            onClick = { onViewPhoto(uri) },
                            onCheckClick = { onToggle(uri) }
                        )
                    }
                    item {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.5.dp, Mb.Border, RoundedCornerShape(14.dp))
                                .clickable { onPickAppend() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Add, null, tint = Mb.Purple, modifier = Modifier.size(28.dp))
                        }
                    }
                }
                Text(
                    "点文件看原信息，点右上角圆圈勾选 / 取消勾选",
                    fontSize = 12.sp, color = Mb.Sub,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
                )
            }
        }
    }
}

/* ---------------- 模板页（替“编辑”栏） ---------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatesScreen(
    templates: List<Template>,
    onSaveTemplate: (String) -> Unit,
    onApplyTemplate: (Template) -> Unit,
    onDeleteTemplate: (Template) -> Unit,
    onBack: () -> Unit,
    current: Screen,
    onNavigate: (Screen) -> Unit
) {
    var name by remember { mutableStateOf("") }
    Scaffold(
        containerColor = Mb.Page,
        topBar = {
            TopAppBar(
                title = { Text("模板", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "返回", tint = Mb.Ink)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Mb.Card)
            )
        },
        bottomBar = { BottomNav(current, onNavigate) }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize()
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(
                Modifier.fillMaxWidth().background(Mb.Card, RoundedCornerShape(20.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("保存当前表单为模板", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Mb.Ink)
                Text(
                    "把首页正在编辑的标题、描述、关键词、作者、版权、来源存下来，下次一键套用。",
                    fontSize = 13.sp, color = Mb.Sub
                )
                MbField("模板名称", name, { name = it })
                MbPrimaryButton(
                    "保存模板", onClick = { onSaveTemplate(name.trim()); name = "" },
                    enabled = name.isNotBlank()
                )
            }
            if (templates.isEmpty()) {
                Text(
                    "还没有保存的模板", color = Mb.Sub,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    textAlign = TextAlign.Center
                )
            } else {
                for (t in templates) {
                    Column(
                        Modifier.fillMaxWidth().background(Mb.Card, RoundedCornerShape(20.dp)).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                t.name, Modifier.weight(1f),
                                fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Mb.Ink
                            )
                            TextButton(onClick = { onDeleteTemplate(t) }) {
                                Text("删除", color = Mb.Sub, fontSize = 14.sp)
                            }
                        }
                        val preview = listOf(t.title, t.keywords)
                            .filter { it.isNotBlank() }.joinToString(" · ")
                        Text(
                            if (preview.isBlank()) "（空模板）" else preview,
                            fontSize = 13.sp, color = Mb.Sub,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                        MbOutlineButton("应用到表单", onClick = { onApplyTemplate(t) })
                    }
                }
            }
        }
    }
}

/* ---------------- 历史页（替“图片”栏） ---------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    records: List<ExportRecord>,
    onOpenFolder: (String) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    current: Screen,
    onNavigate: (Screen) -> Unit
) {
    Scaffold(
        containerColor = Mb.Page,
        topBar = {
            TopAppBar(
                title = { Text("历史记录", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "返回", tint = Mb.Ink)
                    }
                },
                actions = {
                    if (records.isNotEmpty()) {
                        TextButton(onClick = onClear) {
                            Text("清空", color = Mb.Sub, fontSize = 14.sp)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Mb.Card)
            )
        },
        bottomBar = { BottomNav(current, onNavigate) }
    ) { padding ->
        if (records.isEmpty()) {
            Box(
                Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { Text("还没有导出记录", color = Mb.Sub) }
        } else {
            Column(
                Modifier.padding(padding).fillMaxSize()
                    .verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                for (r in records) {
                    Column(
                        Modifier.fillMaxWidth().background(Mb.Card, RoundedCornerShape(20.dp))
                            .clickable(enabled = r.folderUri.isNotBlank()) { onOpenFolder(r.folderUri) }
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(r.timeMs)),
                            fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Mb.Ink
                        )
                        Text(
                            "成功 ${r.success} 张 · 失败 ${r.failed} 张",
                            fontSize = 14.sp, color = Mb.Ink
                        )
                        Text(
                            "${r.folderName} · 用时 ${formatDuration(r.durationMs)}",
                            fontSize = 13.sp, color = Mb.Sub
                        )
                    }
                }
            }
        }
    }
}

/** 毫秒 → “8 秒” / “1 分 20 秒”。 */
fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s < 60) "$s 秒" else "${s / 60} 分 ${s % 60} 秒"
}

/* ---------------- 屏 4：输出设置 ---------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutputSettingsScreen(
    outputFolder: Uri?,
    outputFolderName: String,
    settings: ExportSettings,
    selectedCount: Int,
    onPickFolder: () -> Unit,
    onUnpinFolder: () -> Unit,
    onStartExport: () -> Unit,
    onBack: () -> Unit,
    current: Screen,
    onNavigate: (Screen) -> Unit
) {
    val canExport = selectedCount > 0 && outputFolder != null
    Scaffold(
        containerColor = Mb.Page,
        topBar = {
            TopAppBar(
                title = { Text("输出设置", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "返回", tint = Mb.Ink)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Mb.Card)
            )
        },
        bottomBar = {
            Column(
                Modifier.background(Mb.Card).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!canExport) {
                    Text(
                        if (selectedCount == 0) "先选文件" else "先选输出目录",
                        fontSize = 12.sp, color = Mb.Sub, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                MbPrimaryButton("开始导出", onClick = onStartExport, enabled = canExport)
            }
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize()
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 输出目录
            Column(
                Modifier.fillMaxWidth().background(Mb.Card, RoundedCornerShape(20.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().clickable { onPickFolder() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Mb.PurpleSoft),
                        contentAlignment = Alignment.Center
                    ) { FolderGlyph(Modifier.size(22.dp), Mb.Purple) }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("输出目录", fontSize = 12.sp, color = Mb.Sub)
                        Text(
                            if (outputFolder != null) outputFolderName else "未选择输出目录",
                            fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Mb.Ink,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Mb.Sub)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (outputFolder != null) {
                        OutlinedButton(
                            onClick = onUnpinFolder,
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Mb.PurpleDark),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Mb.Purple)
                        ) { Text("已固定 ✓", fontWeight = FontWeight.Bold) }
                    } else {
                        OutlinedButton(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text("固定目录", fontWeight = FontWeight.Bold) }
                    }
                    Button(
                        onClick = onPickFolder,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Mb.Purple)
                    ) { Text("更换目录", fontWeight = FontWeight.Bold, color = Color.White) }
                }
                Text("选一次就固定，下次打开直接用。", fontSize = 12.sp, color = Mb.Sub)
            }

            // 命名方式
            Column(
                Modifier.fillMaxWidth().background(Mb.Card, RoundedCornerShape(20.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("命名方式", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Mb.Ink)
                RadioRow(
                    label = "原文件名 + _metabatch",
                    sub = "photo_metabatch.jpg",
                    selected = settings.namingSuffix,
                    onClick = { settings.namingSuffix = true }
                )
                RadioRow(
                    label = "原文件名",
                    sub = "photo.jpg",
                    selected = !settings.namingSuffix,
                    onClick = { settings.namingSuffix = false }
                )
            }

            // 开关
            Column(
                Modifier.fillMaxWidth().background(Mb.Card, RoundedCornerShape(20.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SwitchRow(
                    label = "保留原图",
                    sub = "导出后原图保留，不删除",
                    checked = settings.keepOriginal,
                    onChange = { settings.keepOriginal = it }
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(Mb.Line))
                SwitchRow(
                    label = "覆盖同名文件",
                    sub = "开：同名直接覆盖；关：自动改名",
                    checked = settings.overwrite,
                    onChange = { settings.overwrite = it }
                )
            }

            // 导出格式
            Column(
                Modifier.fillMaxWidth().background(Mb.Card, RoundedCornerShape(20.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("导出格式", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Mb.Ink)
                Text("视频始终保持原格式，不转码。", fontSize = 12.sp, color = Mb.Sub)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val formats = listOf(
                        ExportFormat.ORIGINAL to "原格式",
                        ExportFormat.JPEG to "JPEG",
                        ExportFormat.PNG to "PNG",
                        ExportFormat.WEBP to "WebP"
                    )
                    for ((value, label) in formats) {
                        val isSelected = settings.format == value
                        Box(
                            Modifier.clip(RoundedCornerShape(999.dp))
                                .background(if (isSelected) Mb.Purple else Mb.FieldBg)
                                .clickable { settings.format = value }
                                .padding(horizontal = 14.dp, vertical = 9.dp)
                        ) {
                            Text(
                                label, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                color = if (isSelected) Color.White else Mb.Ink
                            )
                        }
                    }
                }
                Text(
                    "原格式不转码，画质无损；选其他格式会重新编码（JPEG / WebP 质量 95）。",
                    fontSize = 12.sp, color = Mb.Sub
                )
            }
        }
    }
}

@Composable
private fun RadioRow(label: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(20.dp).clip(CircleShape)
                .border(2.dp, if (selected) Mb.Purple else Mb.Border, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(Mb.Purple))
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text(label, fontSize = 15.sp, color = Mb.Ink)
            Text(sub, fontSize = 12.sp, color = Mb.Sub)
        }
    }
}

@Composable
private fun SwitchRow(label: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp, color = Mb.Ink)
            Text(sub, fontSize = 12.sp, color = Mb.Sub)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Mb.Purple,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Mb.DisabledBg
            )
        )
    }
}

/* ---------------- 屏 5：导出进度 ---------------- */

@Composable
fun ProgressScreen(
    sources: List<Uri>,
    sourceNames: List<String>,
    outputFolder: Uri?,
    values: MetadataValues,
    options: ExportOptions,
    exportRunId: Int,
    onExportFinished: (List<BatchItemResult>, Long, Long) -> Unit
) {
    val context = LocalContext.current
    var done by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(sources.size) }
    var currentName by remember { mutableStateOf("") }
    var fileResults by remember { mutableStateOf<List<BatchItemResult>>(emptyList()) }
    var finished by remember { mutableStateOf(false) }

    BackHandler { /* 导出页全程不许返回（导出中和收尾 600ms 都拦）；取消按钮也是禁用态 */ }

    LaunchedEffect(exportRunId) {
        val folder = outputFolder
        val startedAt = System.currentTimeMillis()
        var results: List<BatchItemResult>
        if (folder == null || sources.isEmpty()) {
            results = sourceNames.map { BatchItemResult(it, false, "No output folder or no images.") }
            fileResults = results
        } else {
            try {
                results = BatchProcessor.run(
                    context, sources, folder, values, options,
                    onProgress = { d, t, name -> done = d; total = t; currentName = name },
                    onFileResult = { r -> fileResults = fileResults + r }
                )
            } catch (e: Exception) {
                results = sourceNames.map { BatchItemResult(it, false, e.message ?: "Failed") }
                fileResults = results
            }
        }
        done = results.size
        total = sources.size.coerceAtLeast(1)
        currentName = ""
        val endedAt = System.currentTimeMillis()
        finished = true
        delay(600)
        onExportFinished(results, startedAt, endedAt)
    }

    val fraction = if (total > 0) done.toFloat() / total else 0f
    Column(
        Modifier.fillMaxSize().background(Mb.Dark).padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        ProgressRing(fraction)
        Spacer(Modifier.height(16.dp))
        Text(
            if (finished) "导出完成" else "正在导出…",
            color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold
        )
        Text(
            currentName, color = Mb.DarkSub, fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(Modifier.height(20.dp))
        // 当前文件卡片
        Row(
            Modifier.fillMaxWidth().background(Mb.DarkCard, RoundedCornerShape(16.dp)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val currentUri = sources.getOrNull(fileResults.size)
            if (currentUri != null) {
                Thumb(currentUri, Modifier.size(48.dp), onClick = {})
            } else {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF2E2E36)))
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    currentName.ifBlank { "—" }, color = Color.White, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    color = Mb.Purple,
                    trackColor = Color(0xFF2E2E36)
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        // 任务列表
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            for (i in sourceNames.indices) {
                val name = sourceNames[i]
                val result = fileResults.getOrNull(i)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        name, color = Color.White, fontSize = 14.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    when {
                        result != null && result.success ->
                            Icon(Icons.Filled.Check, null, tint = Mb.Purple, modifier = Modifier.size(20.dp))
                        result != null ->
                            Text("失败", color = Mb.Orange, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        i == fileResults.size && !finished ->
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Mb.Purple, strokeWidth = 2.dp
                            )
                        else ->
                            Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF3A3A42)))
                    }
                }
            }
        }
        // 取消导出（设计稿为禁用态）
        Box(
            Modifier.fillMaxWidth().height(52.dp)
                .background(Color(0xFF2E2E36), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) { Text("取消导出", color = Mb.DarkSub, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun ProgressRing(fraction: Float) {
    Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 14.dp.toPx()
            val diameter = size.minDimension - stroke
            val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
            val arcSize = Size(diameter, diameter)
            drawArc(Color(0xFF2E2E36), 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            drawArc(
                Mb.Purple, -90f, 360f * fraction.coerceIn(0f, 1f), false,
                topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round)
            )
        }
        Text(
            "${(fraction * 100).toInt()}%",
            color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold
        )
    }
}

/* ---------------- 屏 6：导出完成 ---------------- */

@Composable
fun DoneScreen(
    results: List<BatchItemResult>,
    outputFolder: Uri?,
    elapsedMs: Long,
    onViewFiles: () -> Unit,
    onHome: () -> Unit
) {
    val ok = results.count { it.success }
    val fail = results.size - ok
    val failed = results.filter { !it.success }
    BackHandler { onHome() }
    Scaffold(containerColor = Mb.Card) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize()
                .verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))
            Box(
                Modifier.size(88.dp).clip(CircleShape).background(Mb.Purple),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(44.dp)) }
            Spacer(Modifier.height(20.dp))
            Text("导出完成！", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Mb.Ink)
            Text(
                "成功导出 $ok 个文件，用时 ${"%.1f".format(elapsedMs / 1000.0)} 秒",
                fontSize = 14.sp, color = Mb.Sub, modifier = Modifier.padding(top = 8.dp)
            )
            Spacer(Modifier.height(24.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    Modifier.weight(1f).background(Mb.PurpleSoft, RoundedCornerShape(16.dp))
                        .padding(vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("$ok", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Mb.PurpleDark)
                    Text("成功", fontSize = 13.sp, color = Mb.Sub)
                }
                Column(
                    Modifier.weight(1f).background(Mb.OrangeSoft, RoundedCornerShape(16.dp))
                        .padding(vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("$fail", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Mb.Orange)
                    Text("失败", fontSize = 13.sp, color = Mb.Sub)
                }
            }
            if (failed.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 16.dp)
                        .background(Mb.FieldBg, RoundedCornerShape(16.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("失败的文件", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Mb.Ink)
                    for (r in failed) {
                        Text("✗ ${r.name}：${r.detail}", fontSize = 13.sp, color = Mb.Orange)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            MbPrimaryButton("查看文件", onClick = onViewFiles, enabled = outputFolder != null)
            Spacer(Modifier.height(12.dp))
            MbOutlineButton("返回首页", onClick = onHome)
            Spacer(Modifier.height(16.dp))
        }
    }
}
