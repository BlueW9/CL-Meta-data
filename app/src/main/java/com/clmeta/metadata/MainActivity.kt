package com.clmeta.metadata

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Blue = Color(0xFF42A5F5)
private val Bg = Color(0xFF0A0E17)
private val CardBg = Color(0xFF121A2B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Blue, onPrimary = Bg, secondary = Blue,
                    background = Bg, onBackground = Blue,
                    surface = CardBg, onSurface = Blue, onSurfaceVariant = Blue
                )
            ) { Screen() }
        }
    }
}

private val Green = Color(0xFF66BB6A)
private val Amber = Color(0xFFFFB74D)
private val Red = Color(0xFFEF5350)

/** tone: 0 neutral, 1 ok, 2 warning, 3 error */
data class Item(
    val uri: Uri,
    val name: String,
    val status: String = "Scanning...",
    val tone: Int = 0,
    val listTitle: String = "",
    val fields: List<MetaField> = emptyList(),
    val needsCleaning: Boolean = false
)

private fun count(n: Int) = if (n == 1) "1 item" else "$n items"

private fun displayName(ctx: Context, uri: Uri): String =
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: "file"

private fun scannedItem(base: Item, fields: List<MetaField>): Item =
    if (fields.isEmpty())
        base.copy(status = "Clean - no metadata found", tone = 1, listTitle = "", fields = emptyList(), needsCleaning = false)
    else
        base.copy(status = "Contains metadata (" + count(fields.size) + ")", tone = 2,
            listTitle = "Found:", fields = fields, needsCleaning = true)

private fun resultItem(base: Item, r: CleanReport): Item = when {
    !r.changed ->
        base.copy(status = "Already clean - file not changed", tone = 1, listTitle = "", fields = emptyList(), needsCleaning = false)
    r.after.isEmpty() ->
        base.copy(status = "Cleaned - " + count(r.before.size) + " removed, verified clean", tone = 1,
            listTitle = "Removed:", fields = r.before, needsCleaning = false)
    else ->
        base.copy(status = "Cleaned, but " + count(r.after.size) + " still present", tone = 2,
            listTitle = "Still present:", fields = r.after, needsCleaning = true)
}

private fun androidx.compose.runtime.snapshots.SnapshotStateList<Item>.update(uri: Uri, f: (Item) -> Item) {
    val i = indexOfFirst { it.uri == uri }
    if (i >= 0) this[i] = f(this[i])
}

/** Same picker as OpenMultipleDocuments, but also asks for write access so files can be cleaned in place. */
private class OpenForEdit : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
}

@Composable
fun Screen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<Item>() }
    var busy by remember { mutableStateOf(false) }

    val pickFiles = rememberLauncherForActivityResult(OpenForEdit()) { uris ->
        uris.forEach { u ->
            val n = displayName(ctx, u)
            if (MetadataCleaner.isSupported(n) && items.none { it.uri == u }) {
                items.add(Item(u, n))
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { MetadataCleaner.inspect(ctx, u, n) } }
                    items.update(u) { base ->
                        r.fold(
                            onSuccess = { scannedItem(base, it) },
                            onFailure = {
                                base.copy(status = "Error: " + (it.message ?: it.javaClass.simpleName),
                                    tone = 3, needsCleaning = false)
                            }
                        )
                    }
                }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding().padding(20.dp)
    ) {
        Text("CL Meta data", color = Blue, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Remove metadata from PDF, Word, Excel and PowerPoint files. Content stays untouched. Works 100% offline.",
            color = Blue, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))

        OutlinedButton(onClick = { pickFiles.launch(MetadataCleaner.supported) }, enabled = !busy,
            modifier = Modifier.fillMaxWidth()) { Text("Select files", color = Blue) }

        LazyColumn(Modifier.weight(1f).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.uri.toString() }) { item ->
                val statusColor = when (item.tone) { 1 -> Green; 2 -> Amber; 3 -> Red; else -> Blue }
                Column(Modifier.fillMaxWidth().background(CardBg, RoundedCornerShape(12.dp)).padding(12.dp)) {
                    Text(item.name, color = Blue, fontWeight = FontWeight.Medium)
                    Text(item.status, color = statusColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 2.dp))
                    if (item.fields.isNotEmpty()) {
                        Text(item.listTitle, color = Blue, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                        item.fields.take(40).forEach { f ->
                            Text("\u2022 " + f.label + ": " + f.value, color = Blue, fontSize = 12.sp)
                        }
                        if (item.fields.size > 40)
                            Text("+ " + (item.fields.size - 40) + " more", color = Blue, fontSize = 12.sp)
                    }
                }
            }
        }

        Button(
            onClick = {
                busy = true
                scope.launch {
                    for (target in items.toList().filter { it.needsCleaning }) {
                        val u = target.uri
                        val n = target.name
                        items.update(u) { it.copy(status = "Cleaning...", tone = 0) }
                        val r = withContext(Dispatchers.IO) { runCatching { MetadataCleaner.cleanAndReport(ctx, u, n) } }
                        items.update(u) { base ->
                            r.fold(
                                onSuccess = { resultItem(base, it) },
                                onFailure = {
                                    base.copy(status = "Error: " + (it.message ?: it.javaClass.simpleName),
                                        tone = 3, needsCleaning = true)
                                }
                            )
                        }
                    }
                    busy = false
                }
            },
            enabled = !busy && items.any { it.needsCleaning },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Bg)
        ) { Text(if (busy) "Working..." else "Clean metadata", fontWeight = FontWeight.Bold) }
    }
}
