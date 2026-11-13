package za.westend.watersplit.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot

/**
 * What a frosted-glass panel needs to see: a copy of the content behind it ([live]) and a blurred version of that copy ([blurred]).
 * The content is drawn once as usual and recorded into the layers on the way, so the panel can paint the blurred copy behind itself.
 * Blurring needs Android 12 (API 31); older phones get a plain, nearly opaque panel instead.
 */
class Backdrop(val live: GraphicsLayer, val blurred: GraphicsLayer, private val list: LazyListState) {
    /** Where the recorded content starts on the screen. */
    var origin: Offset = Offset.Zero

    fun ready(): Boolean = blurred.size.width > 0 && blurred.size.height > 0

    /** Changes whenever the list scrolls, so reading it in a draw block redraws the panel. */
    fun scrollKey(): Int = list.firstVisibleItemIndex * 1_000_000 + list.firstVisibleItemScrollOffset
}

@Composable
fun rememberBackdrop(list: LazyListState): Backdrop {
    val live = rememberGraphicsLayer()
    val blurred = rememberGraphicsLayer()
    return remember(list) {
        blurred.renderEffect = BlurEffect(40f, 40f, TileMode.Clamp)
        Backdrop(live, blurred, list)
    }
}

/** Marks the content a frosted panel looks through. Put it before any padding so the recorded area is the whole screen. */
fun Modifier.frostSource(backdrop: Backdrop): Modifier = this
    .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
    .drawWithContent {
        backdrop.live.record { this@drawWithContent.drawContent() }
        backdrop.blurred.record { drawLayer(backdrop.live) }
        drawLayer(backdrop.live)
    }

/** Paints the blurred copy of the content behind the panel, lined up with where the panel sits. */
fun DrawScope.drawFrost(backdrop: Backdrop, panelOrigin: Offset) {
    translate(
        left = -(panelOrigin.x - backdrop.origin.x),
        top = -(panelOrigin.y - backdrop.origin.y),
    ) { drawLayer(backdrop.blurred) }
}
