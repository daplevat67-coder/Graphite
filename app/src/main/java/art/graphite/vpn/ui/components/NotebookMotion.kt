package art.graphite.vpn.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** A flat receiving page under a curved, peeling sheet; animation invalidates drawing only. */
@Composable fun NotebookPager(
    target:String, enabled:Boolean, modifier:Modifier=Modifier, direction:Int=1,
    durationMillis:Int=NotebookMotionDefaults.PAGE_MILLIS, swipeEnabled:Boolean=true, onSwipe:(Int)->Unit={},
    style:PageTurnStyle=PageTurnStyle.CURL,
    content:@Composable (String)->Unit
) {
    val color=MaterialTheme.colorScheme.background
    val ink=MaterialTheme.colorScheme.onSurface
    var current by remember { mutableStateOf(target) }
    var outgoing by remember { mutableStateOf<String?>(null) }
    var turnDirection by remember { mutableIntStateOf(1) }
    var turnStyle by remember { mutableStateOf(style) }
    val progress=remember { Animatable(1f) }
    val focus=androidx.compose.ui.platform.LocalFocusManager.current
    val latestSwipe by rememberUpdatedState(onSwipe)
    LaunchedEffect(target,enabled,style) {
        if (current!=target) {
            focus.clearFocus(force=true)
            outgoing=if(enabled)current else null
            current=target
            turnDirection=if(direction>=0)1 else -1
            turnStyle=style
            progress.snapTo(0f)
        }
        if(outgoing!=null) {
            if(enabled) progress.animateTo(1f,tween(durationMillis.coerceIn(300,NotebookMotionDefaults.MAX_PAGE_MILLIS),easing=CubicBezierEasing(.3f,0f,.2f,1f)))
            else progress.snapTo(1f)
            outgoing=null
        }
    }
    val turning=outgoing!=null
    val gesture=if(swipeEnabled && !turning)Modifier.pointerInput(target) {
        var distance=0f
        detectHorizontalDragGestures(onDragStart={distance=0f},onHorizontalDrag={change,delta->
            distance+=delta;change.consume()
        },onDragEnd={if(abs(distance)>72.dp.toPx()) latestSwipe(if(distance<0)1 else -1)},onDragCancel={distance=0f})
    } else Modifier
    Box(modifier.clipToBounds().then(gesture).then(if(turning)Modifier.pointerInput(Unit) {
        awaitPointerEventScope { while(true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
    } else Modifier)) {
        // Stable keys retain each page's scroll/Compose state while its order changes.
        val pages=outgoing?.let { listOf(current,it) } ?: listOf(current)
        pages.forEach { page -> key(page) {
            val leaving=page==outgoing
            Box(Modifier.fillMaxSize()
                .then(if(leaving)Modifier.clearAndSetSemantics {} else Modifier)
                .drawWithContent {
                    if(!leaving) { drawContent(); return@drawWithContent }
                    val p=progress.value.coerceIn(0f,1f)
                    val wave=kotlin.math.sin(p*kotlin.math.PI).toFloat()
                    val w=size.width;val h=size.height
                    if(turnStyle==PageTurnStyle.ROLL) {
                        // The exposed side is the blank paper reverse, wrapped around a vertical roll.
                        // App controls stay flat on the unrolled portion; no mirrored controls or bitmap screen.
                        val edge=w*(1f-p)
                        val radius=w*.095f*wave
                        val cap=radius*.27f
                        fun x(v:Float)=if(turnDirection>0)v else w-v
                        val face=Path().apply {
                            moveTo(x(0f),0f);lineTo(x(edge-radius),0f)
                            lineTo(x(edge-radius),h);lineTo(x(0f),h);close()
                        }
                        clipPath(face) {this@drawWithContent.drawContent()}
                        if(radius>.1f) {
                            val shadow=Path().apply {
                                moveTo(x(edge+radius),0f);lineTo(x(edge+radius*1.65f),0f)
                                lineTo(x(edge+radius*1.65f),h);lineTo(x(edge+radius),h);close()
                            }
                            drawPath(shadow,Brush.linearGradient(
                                listOf(Color.Black.copy(alpha=.3f*wave),Color.Transparent),
                                Offset(x(edge+radius),0f),Offset(x(edge+radius*1.65f),0f)))
                            val roll=Path().apply {
                                moveTo(x(edge-radius),cap)
                                cubicTo(x(edge-radius),-cap*.4f,x(edge+radius),-cap*.4f,x(edge+radius),cap)
                                lineTo(x(edge+radius),h-cap)
                                cubicTo(x(edge+radius),h+cap*.4f,x(edge-radius),h+cap*.4f,x(edge-radius),h-cap)
                                close()
                            }
                            drawPath(roll,Brush.linearGradient(
                                listOf(Color.Black.copy(alpha=.3f).compositeOver(color),ink.copy(alpha=.22f).compositeOver(color),color,Color.Black.copy(alpha=.42f).compositeOver(color)),
                                Offset(x(edge-radius),0f),Offset(x(edge+radius),0f)))
                            clipPath(roll) {
                                repeat(14) {i->
                                    val y=(i+.4f)*h/14f
                                    drawLine(ink.copy(alpha=.055f*wave),Offset(x(edge-radius),y+cap),Offset(x(edge+radius),y-cap),.6.dp.toPx())
                                }
                            }
                            drawPath(roll,ink.copy(alpha=.3f*wave),style=androidx.compose.ui.graphics.drawscope.Stroke(.7.dp.toPx()))
                            val rim=Path().apply {
                                moveTo(x(edge-radius),cap)
                                cubicTo(x(edge-radius),cap*2f,x(edge+radius),cap*2f,x(edge+radius),cap)
                            }
                            drawPath(rim,ink.copy(alpha=.3f*wave),style=androidx.compose.ui.graphics.drawscope.Stroke(.7.dp.toPx()))
                        }
                        return@drawWithContent
                    }
                    val bend=w*.12f*wave
                    val edge=w*(1f-p)
                    val top=edge+bend*.35f
                    val bottom=edge-bend*.25f
                    val middle=edge-bend*.8f
                    fun x(v:Float)=if(turnDirection>0)v else w-v
                    val sheet=Path().apply {
                        moveTo(x(0f),0f);lineTo(x(top),0f)
                        quadraticTo(x(middle),h*.5f,x(bottom),h)
                        lineTo(x(0f),h);close()
                    }
                    val band=w*.105f*wave
                    // A contact shadow lies on the receiving page, outside the lifted edge.
                    val shadow=Path().apply {
                        moveTo(x(top),0f);quadraticTo(x(middle),h*.5f,x(bottom),h)
                        lineTo(x(bottom+band*.65f),h)
                        quadraticTo(x(middle+band*1.25f),h*.5f,x(top+band*.65f),0f);close()
                    }
                    drawPath(shadow,Color.Black.copy(alpha=.24f*wave))
                    clipPath(sheet) { this@drawWithContent.drawContent() }
                    // The reverse of the sheet is blank paper, never mirrored app controls.
                    val reverse=Path().apply {
                        moveTo(x(top),0f);quadraticTo(x(middle),h*.5f,x(bottom),h)
                        lineTo(x(bottom-band*.65f),h)
                        quadraticTo(x(middle-band*1.65f),h*.5f,x(top-band*.65f),0f);close()
                    }
                    val gradientStart=x(edge-band*1.7f)
                    val gradientEnd=x(edge+band*.4f)
                    if(band>.1f) drawPath(reverse,Brush.linearGradient(
                        listOf(color,ink.copy(alpha=.18f).compositeOver(color),color),
                        Offset(gradientStart,0f),Offset(gradientEnd,0f)))
                    val crease=Path().apply {moveTo(x(top),0f);quadraticTo(x(middle),h*.5f,x(bottom),h)}
                    drawPath(crease,ink.copy(alpha=.35f*wave),style=androidx.compose.ui.graphics.drawscope.Stroke(.8.dp.toPx()))
                }) {
                // Retained render node: the fold clips a cached page instead of redrawing its UI each frame.
                Box(Modifier.fillMaxSize().graphicsLayer().paper(ink,color)) { content(page) }
            }
        } }
    }
}

private object TornEdge : Shape {
    override fun createOutline(size:androidx.compose.ui.geometry.Size,layoutDirection:LayoutDirection,density:Density):Outline {
        val tooth=with(density){7.dp.toPx()}
        val path=Path().apply {
            moveTo(0f,tooth)
            var x=0f;var i=0
            while(x<size.width) {x=(x+tooth).coerceAtMost(size.width);lineTo(x,if(i++%2==0)0f else tooth)}
            lineTo(size.width,size.height);lineTo(0f,size.height);close()
        }
        return Outline.Generic(path)
    }
}

/** A successful deletion tears, tilts and carries the actual profile sheet out of the book. */
@Composable fun TearableSheet(deleting:Boolean,enabled:Boolean,modifier:Modifier=Modifier,onGone:()->Unit,content:@Composable ()->Unit) {
    val progress=remember {Animatable(0f)}
    var delivered by remember {mutableStateOf(false)}
    val callback by rememberUpdatedState(onGone)
    LaunchedEffect(deleting,enabled) {
        if(deleting && !delivered) {
            if(enabled)progress.animateTo(1f,tween(760,easing=CubicBezierEasing(.35f,.02f,.45f,1f)))
            else progress.snapTo(1f)
            delivered=true
            callback()
        } else if(!deleting) {delivered=false;progress.snapTo(0f)}
    }
    val ink=MaterialTheme.colorScheme.onSurface
    Box(modifier.graphicsLayer {
        val p=progress.value
        val flight=((p-.26f)/.74f).coerceIn(0f,1f)
        transformOrigin=TransformOrigin(.08f,.02f)
        rotationZ=if(p<.26f)-p*6f else -1.56f-flight*17f
        translationX=size.width*1.25f*flight
        translationY=-size.height*.9f*flight
        rotationX=flight*12f
        scaleX=1f-flight*.08f;scaleY=1f-flight*.08f
        alpha=1f-flight*.95f
        shadowElevation=if(deleting)12.dp.toPx()*(1f-flight) else 0f
    }.then(if(deleting)Modifier.clip(TornEdge) else Modifier).drawWithContent {
        val p=progress.value
        drawContent()
        if(deleting && p<.8f) {
            val tooth=7.dp.toPx();val path=Path();path.moveTo(0f,tooth)
            var x=0f;var i=0
            while(x<size.width*p.coerceAtMost(.26f)/.26f) {x=(x+tooth).coerceAtMost(size.width);path.lineTo(x,if(i++%2==0)0f else tooth)}
            drawPath(path,ink.copy(alpha=.7f),style=androidx.compose.ui.graphics.drawscope.Stroke(.9.dp.toPx()))
        }
    }) {content()}
}
