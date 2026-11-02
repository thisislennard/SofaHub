package de.sofahub.app.feature.dashboard

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.sofahub.app.core.model.HaSnapshot
import de.sofahub.app.feature.MainViewModel
import de.sofahub.app.feature.Route
import de.sofahub.app.integration.homeassistant.HaResult
import de.sofahub.app.ui.components.FocusOnEnter
import de.sofahub.app.ui.components.Footer
import de.sofahub.app.ui.components.PageHeader
import de.sofahub.app.ui.components.Spinner
import de.sofahub.app.ui.components.Tile
import de.sofahub.app.ui.components.Txt
import de.sofahub.app.ui.components.glass
import de.sofahub.app.ui.components.produceWhileVisible
import de.sofahub.app.ui.theme.Sh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Holt fortlaufend Standbilder der Kamera; das letzte gute Bild bleibt stehen, wenn ein Abruf scheitert. Nur solange
 * Sofa Hub sichtbar ist – während eines Films lädt der Stick keine Kamerabilder.
 */
@Composable
private fun rememberCameraFrame(vm: MainViewModel, cameraId: String, intervalMs: Long, maxWidth: Int): ImageBitmap? {
    val frame by produceWhileVisible<ImageBitmap?>(null, cameraId) {
        while (true) {
            val r = vm.c.ha.cameraImage(cameraId)
            if (r is HaResult.Ok) withContext(Dispatchers.Default) { decode(r.value, maxWidth) }?.let { value = it }
            delay(if (r is HaResult.Ok) intervalMs else intervalMs * 3)
        }
    }
    return frame
}

private fun decode(bytes: ByteArray, maxWidth: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
    // RGB_565: Kamerabilder brauchen keine Transparenz – halber Speicher pro Bild (jede Sekunde ein neues).
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
    }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}.getOrNull()

@Composable
private fun CameraView(vm: MainViewModel, cameraId: String, intervalMs: Long, maxWidth: Int, modifier: Modifier, scale: ContentScale) {
    val frame = rememberCameraFrame(vm, cameraId, intervalMs, maxWidth)
    Box(modifier.background(Color(0xFF0E0C0B)), contentAlignment = Alignment.Center) {
        if (frame != null) Image(frame, contentDescription = "Druckerkamera", contentScale = scale, modifier = Modifier.fillMaxSize())
        else Spinner(32)
    }
}

/** Kamerafenster auf der Startseite (statt der TV-Steckdose), solange ein Drucker druckt. OK öffnet das Vollbild. */
@Composable
fun RowScope.PrinterTile(vm: MainViewModel, jobs: List<PrintJob>) {
    val job = jobs.first()
    Tile(
        key = "printer",
        modifier = Modifier.weight(1f).fillMaxHeight(),
        onClick = { vm.navigate(Route.Printer) },
    ) {
        CameraView(vm, job.cameraId, 2_000, 480, Modifier.fillMaxSize(), ContentScale.Crop)
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Txt(job.printer + if (jobs.size > 1) " · +${jobs.size - 1}" else "", Sh.style(20, FontWeight.SemiBold, Color.White))
            Txt(
                listOfNotNull(job.progress?.let { "$it %" } ?: Printers.statusLabel(job), job.remainingMinutes?.let { Printers.remainingLabel(it) }).joinToString(" · "),
                Sh.style(18, color = Color(0xE6FFFFFF)),
            )
        }
    }
}

@Composable
fun PrinterScreen(vm: MainViewModel, ha: HaSnapshot) {
    val jobs = remember(ha.entities) { Printers.active(ha) }
    FocusOnEnter("back")
    Column(Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 54.dp)) {
        PageHeader("3D-Drucker", onBack = { vm.back() })
        Spacer(Modifier.height(30.dp))
        if (jobs.isEmpty()) {
            Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)) {
                de.sofahub.app.ui.components.EmptyArt(de.sofahub.app.ui.components.Illustration.Printer, 180)
                Txt("Gerade läuft kein Druck", Sh.style(34, Sh.W650))
                Txt("Sobald ein Drucker druckt, erscheint hier und auf der Startseite seine Kamera.", Sh.style(23, color = Sh.text2))
            }
        } else {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                jobs.take(2).forEach { job ->
                    Column(Modifier.weight(1f).fillMaxHeight().glass(32).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        // Zwei Drucker nebeneinander: je ~800 px breit – halbe Auflösung reicht.
                        CameraView(vm, job.cameraId, 1_000, if (jobs.size > 1) 960 else 1280, Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(22.dp)), ContentScale.Fit)
                        JobInfo(job)
                    }
                }
            }
        }
        Spacer(Modifier.height(26.dp))
        Footer(listOf("↩" to "Zurück"))
    }
}

@Composable
private fun JobInfo(job: PrintJob) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Txt(job.printer, Sh.style(30, Sh.W650))
            Txt(listOfNotNull(Printers.statusLabel(job), job.task, job.layer).joinToString(" · "), Sh.style(22, color = Sh.text2))
        }
        Column(horizontalAlignment = Alignment.End) {
            job.progress?.let { Txt("$it %", Sh.style(34, Sh.W650)) }
            job.remainingMinutes?.let { Txt(Printers.remainingLabel(it), Sh.style(22, color = Sh.text2)) }
        }
    }
    job.progress?.let { p ->
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(99.dp)).background(Sh.veil(0.122f))) {
            Box(Modifier.fillMaxWidth(p / 100f).fillMaxHeight().clip(RoundedCornerShape(99.dp)).background(if (job.paused) Sh.warnText else Sh.teal))
        }
    }
}
