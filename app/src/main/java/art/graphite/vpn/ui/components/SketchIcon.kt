package art.graphite.vpn.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp

/** One quiet, hand-traced icon family. Coordinates use a 24-unit notebook grid. */
@Composable
fun SketchIcon(
    kind: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    Canvas(modifier.size(24.dp)) {
        val factor = size.minDimension / 24f
        val origin = Offset((size.width - 24 * factor) / 2, (size.height - 24 * factor) / 2)
        fun DrawScope.trace(color: Color, shift: Float) {
            fun point(x: Float, y: Float) = Offset(origin.x + (x + shift) * factor, origin.y + (y - shift / 2) * factor)
            val width = 1.55f * factor
            val stroke = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun line(x: Float, y: Float, xx: Float, yy: Float) = drawLine(color, point(x,y), point(xx,yy), width, StrokeCap.Round)
            fun path(vararg values: Float, closed: Boolean = false) {
                val p = Path()
                p.moveTo(point(values[0],values[1]).x, point(values[0],values[1]).y)
                var i=2
                while(i < values.size) { val v=point(values[i],values[i+1]); p.lineTo(v.x,v.y); i+=2 }
                if(closed) p.close()
                drawPath(p,color,style=stroke)
            }
            fun oval(x: Float,y: Float,w: Float,h: Float) = drawOval(color,point(x,y),Size(w*factor,h*factor),style=stroke)
            fun box(x: Float,y: Float,w: Float,h: Float) = drawRoundRect(color,point(x,y),Size(w*factor,h*factor),androidx.compose.ui.geometry.CornerRadius(1.4f*factor),style=stroke)
            when(kind) {
                "home" -> { path(3f,10f,12f,3f,21f,10f); path(5f,9f,5f,21f,19f,21f,19f,9f); path(10f,21f,10f,14f,14f,14f,14f,21f) }
                "layers" -> { path(3f,8f,12f,3f,21f,8f,12f,13f,3f,8f); path(3f,12f,12f,17f,21f,12f); path(3f,16f,12f,21f,21f,16f) }
                "pulse" -> path(2f,12f,6f,12f,9f,4f,13f,21f,16f,11f,22f,11f)
                "user" -> { oval(8f,3f,8f,8f); val p=Path(); val a=point(4f,21f); p.moveTo(a.x,a.y); val b=point(4f,10f); val c=point(20f,10f); val d=point(20f,21f); p.cubicTo(b.x,b.y,c.x,c.y,d.x,d.y); drawPath(p,color,style=stroke) }
                "menu" -> { line(4f,6f,20f,6f); line(4f,12f,20f,12f); line(4f,18f,20f,18f) }
                "power" -> { line(12f,2f,12f,11f); drawArc(color,-48f,276f,false,point(3f,4f),Size(18f*factor,18f*factor),style=stroke) }
                "shield" -> { path(12f,2f,20f,6f,19f,15f,16f,19f,12f,22f,8f,19f,5f,15f,4f,6f,12f,2f); path(8f,12f,11f,15f,16f,9f) }
                "server", "dns" -> { for(y in listOf(3f,10f,17f)) { box(3f,y,18f,5f); line(6f,y+2.5f,7f,y+2.5f); line(11f,y+2.5f,18f,y+2.5f) } }
                "link" -> { drawArc(color,105f,285f,false,point(2f,9f),Size(12f*factor,10f*factor),style=stroke); drawArc(color,-75f,285f,false,point(10f,4f),Size(12f*factor,10f*factor),style=stroke); line(8f,15f,16f,9f) }
                "arrow" -> path(9f,5f,16f,12f,9f,19f)
                "back" -> { path(11f,5f,4f,12f,11f,19f); line(4f,12f,21f,12f) }
                "settings" -> { oval(8f,8f,8f,8f); path(9f,3f,15f,3f,16f,6f,20f,7f,22f,12f,20f,17f,16f,18f,15f,21f,9f,21f,8f,18f,4f,17f,2f,12f,4f,7f,8f,6f,9f,3f) }
                "moon" -> { val p=Path(); val a=point(16f,3f); p.moveTo(a.x,a.y); val b=point(-2f,2f); val c=point(1f,24f); val d=point(20f,20f); p.cubicTo(b.x,b.y,c.x,c.y,d.x,d.y); val e=point(9f,21f); val f=point(7f,9f); val g=point(16f,3f); p.cubicTo(e.x,e.y,f.x,f.y,g.x,g.y); drawPath(p,color,style=stroke) }
                "sun" -> { oval(7f,7f,10f,10f); for(i in 0..7) { val angle=i*Math.PI/4; line((12+8*kotlin.math.cos(angle)).toFloat(),(12+8*kotlin.math.sin(angle)).toFloat(),(12+10.5*kotlin.math.cos(angle)).toFloat(),(12+10.5*kotlin.math.sin(angle)).toFloat()) } }
                "info" -> { oval(2f,2f,20f,20f); line(12f,10f,12f,17f); line(12f,6f,12f,6.5f) }
                "route" -> { oval(2f,16f,5f,5f); oval(17f,3f,5f,5f); path(7f,18f,17f,18f,17f,12f,7f,12f,7f,6f,17f,6f) }
                "flask" -> { line(8f,2f,16f,2f); path(9f,2f,9f,9f,3f,20f,4f,22f,20f,22f,21f,20f,15f,9f,15f,2f); line(7f,15f,17f,15f); line(10f,18f,11f,18f) }
                "bolt" -> path(14f,2f,4f,14f,11f,14f,10f,22f,20f,9f,13f,9f,14f,2f)
                "download" -> { line(12f,2f,12f,21f); path(5f,14f,12f,21f,19f,14f) }
                "upload" -> { line(12f,3f,12f,22f); path(5f,10f,12f,3f,19f,10f) }
                else -> { oval(3f,3f,18f,18f); line(8f,12f,16f,12f) }
            }
        }
        trace(tint.copy(alpha=tint.alpha*.17f), .55f)
        trace(tint, 0f)
    }
}
