package art.graphite.vpn.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlin.math.*
import kotlin.random.Random

// One small repeating bitmap replaces thousands of drawLine calls on every exposed frame.
fun Modifier.paper(ink: Color, background: Color): Modifier = drawWithCache {
    val grain=ImageBitmap(192,192)
    val canvas=Canvas(grain)
    val random=Random(721)
    val paint=Paint().apply { strokeWidth=.65f;strokeCap=StrokeCap.Round }
    repeat(240) {
        val p=Offset(random.nextFloat()*192f,random.nextFloat()*192f)
        val length=random.nextFloat()*7f+1f
        paint.color=ink.copy(alpha=random.nextFloat()*.055f+.015f)
        canvas.drawLine(p,p+Offset(length,-length*.7f),paint)
    }
    val grainBrush=ShaderBrush(ImageShader(grain,TileMode.Repeated,TileMode.Repeated))
    onDrawBehind { drawRect(background);drawRect(grainBrush) }
}
fun Modifier.sketchPanel(ink: Color, fill: Color): Modifier = clip(RoundedCornerShape(16.dp)).drawWithCache {
    val w=size.width; val h=size.height; val r=16.dp.toPx()
    val path=Path().apply {
        moveTo(r,1f); cubicTo(w*.35f,-.5f,w*.68f,2f,w-r,1f)
        quadraticTo(w-1f,0f,w-1f,r)
        cubicTo(w-2f,h*.4f,w+1f,h*.7f,w-1f,h-r)
        quadraticTo(w,h-1f,w-r,h-1f)
        cubicTo(w*.65f,h-2f,w*.3f,h+1f,r,h-1f)
        quadraticTo(1f,h,1f,h-r); lineTo(1f,r); quadraticTo(0f,1f,r,1f); close()
    }
    onDrawBehind {
        drawRect(fill)
        for(i in -20..(w/7).toInt()) {
            val x=i*7f
            drawLine(ink.copy(alpha=.035f),Offset(x,h),Offset(x+36f,h-38f),.8f)
            drawLine(ink.copy(alpha=.02f),Offset(x,0f),Offset(x+20f,22f),.7f)
        }
        drawPath(path, ink.copy(alpha=.6f),style=Stroke(.8.dp.toPx()))
        translate(1.2f,1.4f) { drawPath(path,ink.copy(alpha=.17f),style=Stroke(.5.dp.toPx())) }
    }
}
@Composable fun SketchCard(modifier: Modifier=Modifier, onClick:(()->Unit)?=null, content:@Composable ColumnScope.()->Unit) {
    val colors=MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().sketchPanel(colors.onSurface,colors.surface.copy(alpha=.93f))
        .then(if(onClick!=null) Modifier.clickable(role=Role.Button,onClick=onClick) else Modifier)
        .padding(horizontal=14.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(6.dp),content=content)
}
@Composable fun SketchRow(icon:String,title:String,subtitle:String,onClick:(()->Unit)?=null) {
    SketchCard(onClick=onClick) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            SketchIcon(icon,Modifier.size(28.dp))
            Column(Modifier.weight(1f)) {
                Text(title,style=MaterialTheme.typography.titleMedium)
                Text(subtitle,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(onClick!=null) SketchIcon("arrow",Modifier.size(18.dp))
        }
    }
}
@Composable fun Mountains(modifier:Modifier=Modifier) {
    val ink=MaterialTheme.colorScheme.onSurface
    Canvas(modifier.fillMaxWidth().height(70.dp)) {
        val ridge=listOf(Offset(-.03f,.82f),Offset(.15f,.17f),Offset(.29f,.57f),Offset(.37f,.42f),Offset(.52f,.81f),Offset(.77f,.38f),Offset(.9f,.55f),Offset(1.04f,.43f))
        fun scaled(p:Offset)=Offset(p.x*size.width,p.y*size.height)
        val path=Path().apply { ridge.forEachIndexed { i,p-> if(i==0)moveTo(scaled(p).x,scaled(p).y) else lineTo(scaled(p).x,scaled(p).y) } }
        drawPath(path,ink.copy(alpha=.65f),style=Stroke(1.dp.toPx(),cap=StrokeCap.Round,join=StrokeJoin.Round))
        translate(0f,3f) {drawPath(path,ink.copy(alpha=.22f),style=Stroke(.6.dp.toPx()))}
        for(i in 0 until ridge.lastIndex) {
            val a=scaled(ridge[i]);val b=scaled(ridge[i+1])
            for(j in 0..24) {
                val t=j/24f; val p=a+(b-a)*t
                val d=if(i%2==0) -22f else 20f
                drawLine(ink.copy(alpha=.12f+(.03f*(j%3))),p,p+Offset(d, size.height-p.y),.65.dp.toPx())
            }
        }
        val snow=Path().apply {moveTo(size.width*.15f,size.height*.17f);lineTo(size.width*.17f,size.height*.48f);lineTo(size.width*.22f,size.height*.56f);lineTo(size.width*.19f,size.height*.53f);lineTo(size.width*.27f,size.height*.72f)}
        drawPath(snow,ink.copy(alpha=.55f),style=Stroke(.8.dp.toPx()))
    }
}
@Composable fun PowerDisc(modifier:Modifier=Modifier) {
    val ink=MaterialTheme.colorScheme.onSurface
    val fill=MaterialTheme.colorScheme.surface
    Canvas(modifier) {
        val radius=size.minDimension*.5f
        for(i in 0..6) {
            val r=radius*(.68f+i*.048f)
            val p=Path()
            for(j in 0..180) {
                val theta=j/180f*2*PI.toFloat()
                val wobble=(sin(theta*7+i)*.7f+sin(theta*13+i*2)*.3f)*density
                val v=center+Offset(cos(theta)*(r+wobble),sin(theta)*(r+wobble))
                if(j==0)p.moveTo(v.x,v.y) else p.lineTo(v.x,v.y)
            }
            p.close()
            if(i==0)drawPath(p,fill)
            drawPath(p,ink.copy(alpha=when(i){0->.9f;1->.8f;2->.45f;else->.13f}),style=Stroke(if(i==0)1.8.dp.toPx() else .8.dp.toPx()))
        }
        val inner=Path().apply {addOval(Rect(center-Offset(radius*.67f,radius*.67f),center+Offset(radius*.67f,radius*.67f)))}
        clipPath(inner) {
            for(i in -100..150) {
                val x=i*5f*density
                drawLine(ink.copy(alpha=.07f),Offset(x,0f),Offset(x-size.height,size.height),.7f*density)
                drawLine(ink.copy(alpha=.035f),Offset(x,0f),Offset(x+size.height,size.height),.55f*density)
            }
        }
    }
}
