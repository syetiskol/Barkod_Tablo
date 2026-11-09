package com.example.barkod

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
    }
}

@Composable
fun App() {
    var headers by remember { mutableStateOf<List<String>?>(null) }
    val h = headers
    if (h == null) SetupScreen { headers = it } else SheetScreen(h) { headers = null }
}

/* ---------------- 1) Kurulum ekranı ---------------- */

@Composable
fun SetupScreen(onDone: (List<String>) -> Unit) {
    var count by remember { mutableStateOf("3") }
    val titles = remember { mutableStateListOf("Barkod", "", "") }
    val n = (count.toIntOrNull() ?: 1).coerceIn(1, 20)
    while (titles.size < n) titles.add("")

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = {
                onDone(List(n) { i -> titles[i].ifBlank { "Kolon ${i + 1}" } })
            }) { Text("Devam") }
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Yeni Tablo", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                value = count,
                onValueChange = { count = it.filter(Char::isDigit).take(2) },
                label = { Text("Kolon sayısı") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
            for (i in 0 until n) {
                OutlinedTextField(
                    value = titles[i],
                    onValueChange = { titles[i] = it },
                    label = { Text("${i + 1}. kolon başlığı") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(80.dp))
        }
    }
}

/* ---------------- 2) Tablo + tarayıcı ekranı ---------------- */

@Composable
fun SheetScreen(headers: List<String>, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val rows = remember { mutableStateListOf<List<String>>(List(headers.size) { "" }) }
    var target by remember { mutableIntStateOf(0) }
    var scanning by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val tone = remember { ToneGenerator(AudioManager.STREAM_MUSIC, 100) }
    DisposableEffect(Unit) { onDispose { tone.release() } }
    val vibrator = remember { ctx.getSystemService(Vibrator::class.java) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // (metin, hata mı)
    var messageId by remember { mutableIntStateOf(0) }

    fun addCode(raw: String) {
        val code = raw.trim()
        if (code.isEmpty()) return
        // Aynı barkod bu kolonda zaten varsa ekleme, uyar
        if (rows.any { it[target].trim() == code }) {
            tone.startTone(ToneGenerator.TONE_SUP_ERROR, 300)
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 120), -1))
            message = "Bu barkod zaten eklendi: $code" to true
            messageId++
            return
        }
        val idx = rows.indexOfFirst { it[target].isBlank() }
        if (idx >= 0) {
            rows[idx] = rows[idx].toMutableList().also { it[target] = code }
        } else {
            rows.add(List(headers.size) { if (it == target) code else "" })
        }
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
        vibrator?.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
        message = "Eklendi: $code" to false
        messageId++
    }

    LaunchedEffect(messageId) {
        if (message != null) {
            delay(2000)
            message = null
        }
    }

    LaunchedEffect(rows.size) { if (rows.isNotEmpty()) listState.animateScrollToItem(rows.size - 1) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) scanning = true else Toast.makeText(ctx, "Kamera izni gerekli", Toast.LENGTH_SHORT).show()
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // Hedef kolon seçimi
        Text("Barkodların ekleneceği kolon:", Modifier.padding(start = 12.dp, top = 8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            headers.forEachIndexed { i, h ->
                FilterChip(selected = target == i, onClick = { target = i }, label = { Text(h) })
            }
        }

        // Tablo
        Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            Column {
                Row {
                    headers.forEachIndexed { i, h ->
                        Box(
                            Modifier.width(150.dp).border(0.5.dp, Color.Gray)
                                .background(if (i == target) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                                .padding(8.dp)
                        ) { Text(h, fontWeight = FontWeight.Bold, maxLines = 1) }
                    }
                }
                LazyColumn(state = listState) {
                    items(rows.size) { r ->
                        Row {
                            headers.indices.forEach { c ->
                                BasicTextField(
                                    value = rows[r][c],
                                    onValueChange = { v -> rows[r] = rows[r].toMutableList().also { it[c] = v } },
                                    singleLine = true,
                                    modifier = Modifier.width(150.dp).border(0.5.dp, Color.Gray).padding(8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Durum mesajı
        message?.let { (text, isErr) ->
            Text(
                text,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth()
                    .background(if (isErr) Color(0xFFC62828) else Color(0xFF2E7D32))
                    .padding(10.dp)
            )
        }

        // Kamera
        if (scanning) {
            ScannerView(onCode = ::addCode, modifier = Modifier.fillMaxWidth().height(220.dp))
        }

        // Alt butonlar
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("Yeni") }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { showSave = true }) { Text("Kaydet / Paylaş") }
            Button(onClick = {
                if (scanning) scanning = false
                else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanning = true
                else permLauncher.launch(Manifest.permission.CAMERA)
            }) { Text(if (scanning) "Durdur" else "Barkod Okut") }
        }
    }

    if (showSave) SaveDialog(headers, rows) { showSave = false }
}

/* ---------------- 3) Kamera + ML Kit ---------------- */

@Composable
fun ScannerView(onCode: (String) -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember { BarcodeScanning.getClient() }
    var last by remember { mutableStateOf("" to 0L) }
    val latestOnCode by rememberUpdatedState(onCode)

    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            scanner.close()
        }
    }

    AndroidView(modifier = modifier, factory = { c ->
        val previewView = PreviewView(c)
        val future = ProcessCameraProvider.getInstance(c)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { proxy ->
                val media = proxy.image
                if (media == null) {
                    proxy.close()
                    return@setAnalyzer
                }
                val img = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                scanner.process(img)
                    .addOnSuccessListener { list ->
                        val v = list.firstOrNull()?.rawValue
                        val now = System.currentTimeMillis()
                        if (v != null) {
                            // Kamera aynı barkodun üzerindeyken tekrar tekrar tetiklenmesin
                            val same = v == last.first && now - last.second < 1500
                            last = v to now
                            if (!same) latestOnCode(v)
                        }
                    }
                    .addOnCompleteListener { proxy.close() }
            }
            provider.unbindAll()
            provider.bindToLifecycle(ctx as LifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(c))
        previewView
    })
}

/* ---------------- 4) Kaydet / Paylaş ---------------- */

@Composable
fun SaveDialog(headers: List<String>, rows: List<List<String>>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("barkodlar") }
    var xlsx by remember { mutableStateOf(true) }

    fun payload(): Triple<String, String, ByteArray> {
        val data = rows.filter { r -> r.any { it.isNotBlank() } }
        val ext = if (xlsx) "xlsx" else "csv"
        val mime = if (xlsx) "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" else "text/csv"
        val bytes = if (xlsx) buildXlsx(headers, data) else buildCsv(headers, data)
        return Triple("${name.trim().ifBlank { "barkodlar" }}.$ext", mime, bytes)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dosyayı kaydet") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Dosya adı") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = xlsx, onClick = { xlsx = true }); Text("XLSX (Excel)")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !xlsx, onClick = { xlsx = false }); Text("CSV")
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    val (file, mime, bytes) = payload()
                    saveToDownloads(ctx, file, mime, bytes)
                    onDismiss()
                }) { Text("Kaydet") }
                TextButton(onClick = {
                    val (file, mime, bytes) = payload()
                    shareFile(ctx, file, mime, bytes)
                    onDismiss()
                }) { Text("Paylaş") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("İptal") } }
    )
}

fun saveToDownloads(ctx: Context, name: String, mime: String, bytes: ByteArray) {
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, mime)
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
    }
    val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    if (uri == null) {
        Toast.makeText(ctx, "Kaydedilemedi", Toast.LENGTH_LONG).show()
        return
    }
    ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
    Toast.makeText(ctx, "İndirilenler klasörüne kaydedildi: $name", Toast.LENGTH_LONG).show()
}

fun shareFile(ctx: Context, name: String, mime: String, bytes: ByteArray) {
    val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
    val f = File(dir, name).also { it.writeBytes(bytes) }
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(intent, "Paylaş"))
}

/* ---------------- 5) CSV ve XLSX üretimi (ek kütüphane yok) ---------------- */

fun buildCsv(headers: List<String>, rows: List<List<String>>): ByteArray {
    // Türkçe Excel ayraç olarak ';' bekler; BOM ile Türkçe karakterler doğru açılır
    fun q(s: String) = if (s.any { it == ';' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    val sb = StringBuilder("\uFEFF")
    (listOf(headers) + rows).forEach { sb.append(it.joinToString(";") { c -> q(c) }).append("\r\n") }
    return sb.toString().toByteArray(Charsets.UTF_8)
}

private fun colName(i: Int): String {
    var n = i
    var s = ""
    do {
        s = "${'A' + n % 26}$s"
        n = n / 26 - 1
    } while (n >= 0)
    return s
}

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

// Sadece rakamlardan oluşan, en fazla 15 haneli ve başında 0 olmayan değerler sayı olarak yazılır
// (Excel 15 haneden sonrasını doğru tutamaz; başında 0 olanlar metin kalır ki sıfırlar silinmesin)
private fun isPlainNumber(s: String) =
    s.length in 1..15 && s.all { it in '0'..'9' } && (s.length == 1 || s[0] != '0')

fun buildXlsx(headers: List<String>, rows: List<List<String>>): ByteArray {
    val sheet = StringBuilder(
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<cols><col min=\"1\" max=\"${headers.size}\" width=\"22\" customWidth=\"1\"/></cols><sheetData>"
    )
    (listOf(headers) + rows).forEachIndexed { r, row ->
        sheet.append("<row r=\"${r + 1}\">")
        row.forEachIndexed { c, v0 ->
            val v = v0.trim()
            if (v.isEmpty()) return@forEachIndexed
            val ref = "${colName(c)}${r + 1}"
            if (r > 0 && isPlainNumber(v)) {
                // s="1" -> #,##0 biçimi: binlik ayracı var, ondalık yok
                sheet.append("<c r=\"$ref\" s=\"1\"><v>$v</v></c>")
            } else {
                sheet.append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(v0)}</t></is></c>")
            }
        }
        sheet.append("</row>")
    }
    sheet.append("</sheetData></worksheet>")

    val styles = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
        "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
        "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>" +
        "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
        "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
        "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
        "<xf numFmtId=\"3\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/></cellXfs></styleSheet>"

    val files = linkedMapOf(
        "[Content_Types].xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>",
        "_rels/.rels" to "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>",
        "xl/workbook.xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Sayfa1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
        "xl/_rels/workbook.xml.rels" to "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>",
        "xl/styles.xml" to styles,
        "xl/worksheets/sheet1.xml" to sheet.toString()
    )
    val bos = ByteArrayOutputStream()
    ZipOutputStream(bos).use { z ->
        files.forEach { (n, c) ->
            z.putNextEntry(ZipEntry(n))
            z.write(c.toByteArray(Charsets.UTF_8))
            z.closeEntry()
        }
    }
    return bos.toByteArray()
}
