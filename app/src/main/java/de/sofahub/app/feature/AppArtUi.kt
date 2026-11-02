package de.sofahub.app.feature

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import de.sofahub.app.integration.apps.AppArt
import de.sofahub.app.ui.components.Monogram
import de.sofahub.app.ui.components.Txt
import de.sofahub.app.ui.theme.Sh

@Composable
fun rememberAppArt(vm: MainViewModel, packageName: String): AppArt? {
    val art by produceState<AppArt?>(null, packageName) { value = vm.c.apps.art(packageName) }
    return art
}

/** Kachelfläche mit dem echten TV-Banner der App (randlos), wie im Fire-TV-Startbildschirm. */
object AppBannerScope {
    @Composable
    fun BoxScope.AppBannerIn(art: AppArt, label: String, badge: String? = null) {
        Image(art.banner!!.asImageBitmap(), contentDescription = label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (badge != null) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(10.dp).size(38.dp).clip(RoundedCornerShape(19.dp)).background(Color(0xB30A0A0A)),
                contentAlignment = Alignment.Center,
            ) { Txt(badge, Sh.style(20, Sh.W650, Color.White)) }
        }
    }
}

/** App-Icon in Kachelgröße; ohne Icon das Monogramm aus dem Design. */
@Composable
fun AppIcon(art: AppArt?, label: String, seed: String, size: Int = 50, radius: Int = 14) {
    val icon = art?.icon
    if (icon != null) {
        Image(
            icon.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit,
            modifier = Modifier.size(size.dp).clip(RoundedCornerShape(radius.dp)),
        )
    } else {
        Monogram(label, seed, size, (size * 0.44f).toInt(), radius)
    }
}
