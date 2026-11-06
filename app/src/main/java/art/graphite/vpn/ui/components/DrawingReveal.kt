package art.graphite.vpn.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Native content is revealed row by row as a pencil traces its leading edge. */
@Composable fun SketchReveal(
    refreshKey:Any?,enabled:Boolean,modifier:Modifier=Modifier,
    durationMillis:Int=NotebookMotionDefaults.REVEAL_MILLIS,
    content:@Composable ()->Unit
) {
    val progress=remember {Animatable(if(enabled)0f else 1f)}
    // A new identity is allocated for every composed request, including off→on
    // with the same key. Hide unrevealed controls in that same composition,
    // before LaunchedEffect gets its first frame.
    val request=remember(refreshKey,enabled) {Any()}
    var activeRequest by remember {mutableStateOf<Any?>(null)}
    var completedRequest by remember {mutableStateOf<Any?>(null)}
    val revealing=enabled && completedRequest!==request
    val ink=MaterialTheme.colorScheme.onSurface
    LaunchedEffect(request) {
        if(enabled) {
            progress.snapTo(0f)
            activeRequest=request
            progress.animateTo(1f,tween(durationMillis.coerceIn(100,NotebookMotionDefaults.MAX_REVEAL_MILLIS),easing=LinearEasing))
        } else {
            progress.snapTo(1f)
            activeRequest=request
        }
        // A cancelled animation cannot reach this assignment.
        completedRequest=request
    }
    Box(modifier.clipToBounds()
        .then(if(revealing)Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
            awaitPointerEventScope {while(true)awaitPointerEvent(PointerEventPass.Initial).changes.forEach{it.consume()}}
        } else Modifier)
        .drawWithContent {
            val p=when {
                !enabled->1f
                activeRequest!==request->0f
                else->progress.value
            }
            if(p>=1f){drawContent();return@drawWithContent}
            val rowHeight=28.dp.toPx()
            val rows=(size.height/rowHeight).coerceAtLeast(1f)
            val row=p*rows
            val y=(row.toInt()*rowHeight).coerceIn(0f,size.height)
            val fraction=row-row.toInt()
            val x=if(row.toInt()%2==0)size.width*fraction else size.width*(1f-fraction)
            clipRect(bottom=y) {this@drawWithContent.drawContent()}
            if(row.toInt()%2==0)clipRect(right=x,top=y,bottom=(y+rowHeight).coerceAtMost(size.height)) {this@drawWithContent.drawContent()}
            else clipRect(left=x,top=y,bottom=(y+rowHeight).coerceAtMost(size.height)) {this@drawWithContent.drawContent()}
            drawLine(ink.copy(alpha=.36f),Offset(0f,y),Offset(x,y),.65.dp.toPx())
            val pencil=Path().apply {
                moveTo(x,y);lineTo(x+5.dp.toPx(),y-12.dp.toPx())
                lineTo(x+8.dp.toPx(),y-10.dp.toPx());close()
            }
            drawPath(pencil,ink.copy(alpha=.85f))
        }) {
        Box(Modifier.graphicsLayer()) {content()}
    }
}

/** Indeterminate sketch shown only while a real operation is pending; no invented percentage. */
@Composable fun LoadingSketch(label:String,enabled:Boolean,modifier:Modifier=Modifier) {
    val phase:State<Float> = if(enabled) {
        val transition=rememberInfiniteTransition(label="pencil activity")
        transition.animateFloat(0f,1f,infiniteRepeatable(tween(1800,easing=LinearEasing)),label="pencil stroke")
    } else remember {mutableStateOf(1f)}
    val ink=MaterialTheme.colorScheme.onSurface
    Row(modifier.fillMaxWidth().semantics {contentDescription=label;liveRegion=LiveRegionMode.Polite},
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Canvas(Modifier.size(40.dp,30.dp)) {
            val value=phase.value
            val center=Offset(size.width*.46f,size.height*.53f)
            val r=size.height*.32f
            val sweep=if(enabled)220f else 300f
            drawArc(ink.copy(alpha=.5f),value*360f-90f,sweep,false,
                topLeft=Offset(center.x-r,center.y-r),size=androidx.compose.ui.geometry.Size(r*2f,r*2f),style=Stroke(1.dp.toPx()))
            val angle=(value*2*PI-PI/2).toFloat()
            val tip=center+Offset(cos(angle)*r,sin(angle)*r)
            drawLine(ink,tip,tip+Offset(8.dp.toPx(),-9.dp.toPx()),2.dp.toPx())
        }
        Text(label,style=MaterialTheme.typography.bodyMedium)
    }
}
