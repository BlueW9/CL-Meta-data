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

data class Item(val uri: Uri, val name: String, val status: String = "Pending")

private fun displayName(ctx: Context, uri: Uri): String =
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: "file"

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
            if (MetadataCleaner.isSupported(n) && items.none { it.uri == u })
                items.add(Item(u, n))
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
        Text("The original files are cleaned in place. No copy is created.",
            color = Blue, fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp))

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.uri.toString() }) { item ->
                Column(Modifier.fillMaxWidth().background(CardBg, RoundedCornerShape(12.dp)).padding(12.dp)) {
                    Text(item.name, color = Blue, fontWeight = FontWeight.Medium)
                    Text(item.status, color = Blue, fontSize = 12.sp)
                }
            }
        }

        Button(
            onClick = {
                busy = true
                scope.launch {
                    for (i in items.indices) {
                        val item = items[i]
                        items[i] = item.copy(status = "Cleaning...")
                        val result = withContext(Dispatchers.IO) {
                            try { MetadataCleaner.clean(ctx, item.uri, item.name); "Done - original cleaned" }
                            catch (t: Throwable) { "Error: " + (t.message ?: t.javaClass.simpleName) }
                        }
                        items[i] = item.copy(status = result)
                    }
                    busy = false
                }
            },
            enabled = !busy && items.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Bg)
        ) { Text(if (busy) "Working..." else "Clean metadata", fontWeight = FontWeight.Bold) }
    }
}
