package art.graphite.vpn.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp

/** Decorative plot of actual KiB/s observations; invalid samples remain gaps, never invented zeros. */
@Composable
fun TrafficGraph(samples: List<Double>, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    val observations = samples.takeLast(60)
    Canvas(modifier.fillMaxWidth().height(140.dp).drawWithCache {
        val maxScale = maxOf(1.0, observations.filter { it.isFinite() && it >= 0.0 }.maxOrNull() ?: 1.0)
        val trace = Path()
        val areas = mutableListOf<Path>()
        val singles = mutableListOf<Offset>()
        val points = observations.mapIndexed { index, value ->
            if (!value.isFinite() || value < 0.0) null
            else Offset(if (observations.size == 1) size.width / 2f else index.toFloat() * size.width / (observations.size - 1),
                (size.height * (1.0 - value / maxScale)).toFloat())
        }
        var segment = mutableListOf<Offset>()
        fun completeSegment() {
            if (segment.size == 1) singles += segment.first()
            else if (segment.isNotEmpty()) {
                val first = segment.first()
                trace.moveTo(first.x, first.y)
                segment.drop(1).forEach { trace.lineTo(it.x, it.y) }
                areas += Path().apply {
                    moveTo(first.x, size.height); lineTo(first.x, first.y)
                    segment.drop(1).forEach { lineTo(it.x, it.y) }
                    lineTo(segment.last().x, size.height); close()
                }
            }
            segment = mutableListOf()
        }
        points.forEach { if (it == null) completeSegment() else segment += it }
        completeSegment()
        val stroke = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val gap = 7.dp.toPx()
        onDrawBehind {
            for (i in 0..3) {
                val y = size.height * i / 3f
                drawLine(ink.copy(alpha = .14f), Offset(0f, y), Offset(size.width, y), .7.dp.toPx())
            }
            areas.forEach { area -> clipPath(area) {
                for (i in -((size.height / gap).toInt() + 1)..((size.width / gap).toInt() + 1)) {
                    val x = i * gap
                    drawLine(ink.copy(alpha = .14f), Offset(x, size.height), Offset(x + size.height, 0f), .6.dp.toPx())
                }
            } }
            drawPath(trace, ink.copy(alpha = .9f), style = stroke)
            singles.forEach { drawCircle(ink.copy(alpha = .9f), 2.dp.toPx(), it) }
        }
    }) {}
}
