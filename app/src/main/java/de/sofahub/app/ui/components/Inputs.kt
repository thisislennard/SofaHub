package de.sofahub.app.ui.components

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import de.sofahub.app.ui.theme.Icon
import de.sofahub.app.ui.theme.Sh
import de.sofahub.app.ui.theme.ShIcon

/**
 * Texteingabe für die Fernbedienung: Fokus mit Rahmen, OK öffnet die Systemtastatur, ▲/▼ verlassen das Feld.
 * Eingaben laufen über die Fire-TV-Tastatur; Passwörter werden maskiert angezeigt.
 */
@Composable
fun ShTextField(
    key: String,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    mono: Boolean = true,
    singleLine: Boolean = true,
    height: Int = 80,
    keyboardType: KeyboardType = KeyboardType.Text,
    maxLength: Int = 2048,
) {
    val registry = LocalFocusRegistry.current
    val requester = remember(registry, key) { registry.requester(key) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    // Nur lesbar, bis OK gedrückt wird: Fire OS öffnete die Tastatur sonst schon beim bloßen Fokussieren.
    var editing by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Txt(label, Sh.style(22, FontWeight.Medium, Sh.text2))
        BasicTextField(
            value = value,
            onValueChange = { onValueChange(it.take(maxLength)) },
            singleLine = singleLine,
            readOnly = !editing,
            textStyle = Sh.style(26, FontWeight.Medium, Sh.text, mono = mono, lineHeight = 1.3f),
            cursorBrush = SolidColor(Sh.tealText),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else keyboardType,
                imeAction = if (singleLine) ImeAction.Next else ImeAction.Default,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onNext = { keyboard?.hide(); editing = false; focusManager.moveFocus(FocusDirection.Down) }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(requester)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) registry.last = key else editing = false
                }
                .onPreviewKeyEvent { ev ->
                    val code = ev.nativeKeyEvent.keyCode
                    when {
                        (code == AndroidKeyEvent.KEYCODE_DPAD_CENTER || code == AndroidKeyEvent.KEYCODE_ENTER) && !editing -> {
                            if (ev.type == KeyEventType.KeyUp) {
                                editing = true
                                keyboard?.show()
                            }
                            true
                        }
                        ev.type == KeyEventType.KeyDown && code == AndroidKeyEvent.KEYCODE_DPAD_DOWN && singleLine -> {
                            focusManager.moveFocus(FocusDirection.Down); true
                        }
                        ev.type == KeyEventType.KeyDown && code == AndroidKeyEvent.KEYCODE_DPAD_UP && singleLine -> {
                            focusManager.moveFocus(FocusDirection.Up); true
                        }
                        // Nicht im Tippmodus: links/rechts verschieben den Fokus statt des (unsichtbaren) Cursors.
                        // Findet sich kein Ziel, geht die Taste an die umgebende Ansicht weiter.
                        ev.type == KeyEventType.KeyDown && code == AndroidKeyEvent.KEYCODE_DPAD_LEFT && !editing ->
                            focusManager.moveFocus(FocusDirection.Left)
                        ev.type == KeyEventType.KeyDown && code == AndroidKeyEvent.KEYCODE_DPAD_RIGHT && !editing ->
                            focusManager.moveFocus(FocusDirection.Right)
                        else -> false
                    }
                },
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(22.dp)).background(Color(0x40000000))
                        .border(if (focused) 4.dp else 1.dp, if (focused) Sh.focusRing else Sh.veil(0.141f), RoundedCornerShape(22.dp))
                        .padding(horizontal = 26.dp, vertical = if (singleLine) 0.dp else 18.dp),
                    contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) Txt(placeholder, Sh.style(26, color = Sh.muted, mono = mono))
                    inner()
                }
            },
        )
    }
}

/** Kontrollkästchen-Zeile (fernbedienbar). */
@Composable
fun CheckRow(key: String, checked: Boolean, label: String, modifier: Modifier = Modifier, sub: String? = null, onToggle: () -> Unit) {
    Tile(
        key = key,
        modifier = modifier.fillMaxWidth().height(if (sub == null) 76.dp else 92.dp),
        radius = 22,
        style = TileStyle.Plain,
        background = if (checked) androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0x242DD4BF), Color(0x242DD4BF))) else null,
        borderColor = if (checked) Color(0x732DD4BF) else Sh.veil(0.102f),
        focusScale = 1.03f,
        padding = androidx.compose.foundation.layout.PaddingValues(start = 18.dp, end = 24.dp),
        onClick = onToggle,
    ) {
        Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (checked) Sh.tealFill else Color.Transparent)
                    .border(2.dp, if (checked) Sh.tealFill else Sh.veil(0.349f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) { if (checked) Icon(ShIcon.Check, Color.White, 22) }
            Column {
                Txt(label, Sh.style(24, FontWeight.SemiBold))
                sub?.let { Txt(it, Sh.style(19, FontWeight.Medium, Sh.hint)) }
            }
        }
    }
}

/** QR-Code wie im Entwurf: dunkle Module auf heller Fläche. */
@Composable
fun QrCode(content: String, size: Int = 300, padding: Int = 22, radius: Int = 24) {
    val matrix = remember(content) {
        // UTF-8-Bytes direkt (ohne ECI-Kennung) – so lesen Handykameras auch Umlaute, z. B. im WLAN-Namen, richtig.
        // ZXing schreibt ISO-8859-1-Zeichen 1:1 als Bytes; deshalb die UTF-8-Bytes als solche Zeichen übergeben.
        val raw = String(content.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
        QRCodeWriter().encode(raw, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0))
    }
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(radius.dp)).background(Sh.text).padding(padding.dp)) {
        // Module einmal als Pfad aufbauen statt bei jedem Zeichnen hunderte Rechtecke (z. B. beim Fokus-Zoom der Kachel).
        Spacer(
            Modifier.size((size - 2 * padding).dp).drawWithCache {
                val cell = this.size.width / matrix.width
                val path = Path()
                for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                    if (matrix.get(x, y)) path.addRect(Rect(x * cell, y * cell, (x + 1) * cell + 0.5f, (y + 1) * cell + 0.5f))
                }
                onDrawBehind { drawPath(path, Sh.ink) }
            },
        )
    }
}
