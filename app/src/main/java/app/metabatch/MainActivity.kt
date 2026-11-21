package app.metabatch

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MetaBatchScreen() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MetaBatchScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var viewing by remember { mutableStateOf<Uri?>(null) }
    var metadataMap by remember { mutableStateOf<Map<Uri, PhotoMetadata>>(emptyMap()) }
    var outputFolder by remember { mutableStateOf<Uri?>(null) }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var keywords by remember { mutableStateOf("风景、旅行、自然") }
    var creator by remember { mutableStateOf("") }
    var rights by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("就绪 / Ready") }
    var busy by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf("") }

    // Read each picked photo's original metadata once, in the background.
    LaunchedEffect(selected) {
        for (uri in selected) {
            if (uri !in metadataMap) {
                scope.launch(Dispatchers.IO) {
                    val meta = MetadataReader.read(context, uri)
                    metadataMap = metadataMap + (uri to meta)
                }
            }
        }
    }

    val viewingUri = viewing
    if (viewingUri != null) {
        val meta = metadataMap[viewingUri]
        PhotoDetailScreen(
            uri = viewingUri,
            metadata = meta,
            onBack = { viewing = null },
            onFillForm = { m ->
                title = m.xmp.title
                description = m.xmp.description.ifBlank { m.description }
                keywords = m.xmp.keywords.joinToString("、")
                creator = m.xmp.creator.ifBlank { m.artist }
                rights = m.xmp.rights.ifBlank { m.copyright }
                source = m.xmp.source
                viewing = null
                status = "已把「${m.fileName}」的原值填入编辑表单 / Filled from original metadata"
            }
        )
        return
    }

    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        selected = uris
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }
    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        outputFolder = uri
        if (uri != null) runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    MaterialTheme {
        Scaffold(topBar = {
            TopAppBar(title = {
                Column {
                    Text("MetaBatch")
                    Text("图片元数据批量编辑器 · Batch Metadata Editor",
                        style = MaterialTheme.typography.labelSmall)
                }
            })
        }) { padding ->
            Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("1. 选择图片 / Select images", style = MaterialTheme.typography.titleMedium)
                        Text("已选择 ${selected.size} 张 / ${selected.size} selected")
                        Button(onClick = { pickImages.launch(arrayOf("image/*")) },
                            modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                            Text("选择多张图片 / Choose images")
                        }
                    }
                }

                if (selected.isNotEmpty()) {
                    Card {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("2. 浏览与查看原信息 / Browse & inspect", style = MaterialTheme.typography.titleMedium)
                            Text("点一张图放大查看，看完原信息再决定改什么 / Tap a photo to view it and its original metadata",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary)
                            for (row in selected.chunked(3)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    for (uri in row) {
                                        Thumbnail(uri, Modifier.weight(1f)) { viewing = uri }
                                    }
                                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }

                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("3. 统一元数据 / Shared metadata", style = MaterialTheme.typography.titleMedium)
                        Field("标题 / Title", title) { title = it }
                        Field("描述 / Description", description, minLines = 2) { description = it }
                        Field("关键词 / Keywords（逗号或顿号分隔）", keywords) { keywords = it }
                        Field("作者 / Creator", creator) { creator = it }
                        Field("版权 / Copyright", rights) { rights = it }
                        Field("来源 / Source", source) { source = it }
                    }
                }
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("4. 输出位置 / Output", style = MaterialTheme.typography.titleMedium)
                        Text(if (outputFolder != null) "已选择输出文件夹 / Folder selected"
                            else "尚未选择输出文件夹 / No output folder")
                        OutlinedButton(onClick = { pickFolder.launch(null) },
                            modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                            Text("选择输出文件夹 / Choose output folder")
                        }
                    }
                }
                Button(
                    onClick = {
                        val folder = outputFolder ?: return@Button
                        val values = MetadataValues(
                            title, description,
                            keywords.split(',', '，', '、', ';', '；').map { it.trim() }.filter { it.isNotEmpty() },
                            creator, rights, source
                        )
                        busy = true
                        resultText = ""
                        scope.launch {
                            try {
                                val results = BatchProcessor.run(context, selected, folder, values) { done, total, name ->
                                    status = "处理中 $done/$total · $name"
                                }
                                val ok = results.count { it.success }
                                val fail = results.size - ok
                                status = "完成 / Done: $ok 成功 / succeeded, $fail 失败 / failed"
                                resultText = results.joinToString("\n") {
                                    "${if (it.success) "✓" else "✗"} ${it.name}: ${it.detail}"
                                }
                            } catch (e: Exception) {
                                status = "处理失败 / Failed: ${e.message}"
                            } finally { busy = false }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selected.isNotEmpty() && outputFolder != null && !busy
                ) { Text(if (busy) "处理中 / Processing…" else "开始批量写入并另存 / Export copies") }

                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(status, style = MaterialTheme.typography.bodyMedium)
                if (resultText.isNotBlank()) {
                    Card { Text(resultText, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
                }
                Text(
                    "留空的字段保留原值，不会清空。中文标题与关键词原样写入，不会变问号。v0.4.2 支持 JPEG、PNG、WebP 的 EXIF/XMP 读取与写入。HEIC、TIFF 暂时安全跳过。原图不覆盖。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

/** Square thumbnail, decoded at ~256px on a background thread. */
@Composable
private fun Thumbnail(uri: Uri, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) { ImageLoader.decodeSampled(context, uri, 256) }
    }
    Box(
        modifier.aspectRatio(1f).clickable { onClick() }
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text("…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoDetailScreen(
    uri: Uri,
    metadata: PhotoMetadata?,
    onBack: () -> Unit,
    onFillForm: (PhotoMetadata) -> Unit
) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) { ImageLoader.decodeSampled(context, uri, 2048) }
    }
    BackHandler { onBack() }
    MaterialTheme {
        Scaffold(topBar = {
            TopAppBar(
                title = { Text(metadata?.fileName ?: "查看图片 / Photo", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("‹ 返回") }
                }
            )
        }) { padding ->
            Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
            ) {
                val bmp = bitmap
                if (bmp != null) {
                    Image(
                        bmp.asImageBitmap(), null, Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth
                    )
                } else {
                    Box(
                        Modifier.fillMaxWidth().height(240.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) { Text("图片加载中… / Loading…") }
                }
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (metadata == null) {
                        Text("原信息读取中… / Reading original metadata…")
                    } else {
                        Card {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("原信息 / Original metadata", style = MaterialTheme.typography.titleMedium)
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
                        }
                        Button(
                            onClick = { onFillForm(metadata) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("把原值填入编辑表单 / Fill edit form with these values") }
                        Text(
                            "填入后回到主界面，可以在原值基础上改，再批量写入。原图不会被改动。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.width(64.dp), style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
        Text(if (value.isBlank()) "无" else value, Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Field(
    label: String, value: String, minLines: Int = 1, onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, label = { Text(label) },
        modifier = Modifier.fillMaxWidth(), minLines = minLines, singleLine = minLines == 1
    )
}
