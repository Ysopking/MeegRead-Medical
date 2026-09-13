package de.meegread.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import de.meegread.app.mapping.SensorLayouts
import de.meegread.app.model.MeegRecording
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

enum class BrainViewMode { TOPO_2D, SPHERE_3D }

@Composable
fun BrainMapView(recording: MeegRecording, sampleIndex: Int, mode: BrainViewMode, rotationDegrees: Float = 0f, modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.onSurface.copy(alpha=0.45f); val surface=MaterialTheme.colorScheme.surfaceVariant
    val positions = SensorLayouts.positions(recording)
    val values = recording.channels.mapValues { (_, samples) -> samples.getOrNull(sampleIndex.coerceIn(0,max(0,samples.lastIndex)))?.takeIf{it.isFinite()} ?: 0.0 }
    val maxAbs = values.values.maxOfOrNull { kotlin.math.abs(it) }?.takeIf { it > 1e-30 } ?: 1.0
    val rotation = rotationDegrees / 180f * PI
    Box(modifier.fillMaxWidth().height(360.dp).background(surface,RoundedCornerShape(14.dp))) {
        Canvas(Modifier.matchParentSize()) {
            val radius=minOf(size.width,size.height)*0.39f; val center=Offset(size.width/2f,size.height/2f)
            drawCircle(Color.Black.copy(alpha=0.18f),radius,center); drawCircle(outline,radius,center,style=Stroke(3f))
            if(mode==BrainViewMode.TOPO_2D){ drawLine(outline,Offset(center.x-radius*0.12f,center.y-radius),Offset(center.x,center.y-radius*1.10f),3f); drawLine(outline,Offset(center.x,center.y-radius*1.10f),Offset(center.x+radius*0.12f,center.y-radius),3f) }
            else { drawOval(outline.copy(alpha=0.25f),Offset(center.x-radius,center.y-radius*0.25f),Size(radius*2,radius*0.5f),style=Stroke(2f)) }
            positions.forEach { (name,p) ->
                val raw=values[name] ?: 0.0; val normalized=(raw/maxAbs).coerceIn(-1.0,1.0); val projected=if(mode==BrainViewMode.SPHERE_3D){ val x=p.x*cos(rotation)+p.z*sin(rotation); val z=-p.x*sin(rotation)+p.z*cos(rotation); Triple(x,p.y,z) } else Triple(p.x,p.y,p.z)
                if(mode==BrainViewMode.SPHERE_3D && projected.third < -0.25) return@forEach
                val px=center.x+projected.first.toFloat()*radius; val py=center.y-projected.second.toFloat()*radius; val color=heatColor(normalized)
                drawCircle(color.copy(alpha=0.28f),radius*0.12f,Offset(px,py)); drawCircle(color,radius*0.045f,Offset(px,py))
            }
        }
    }
}
private fun heatColor(value: Double): Color { val x=value.coerceIn(-1.0,1.0); return if(x>=0) Color(1f,(1.0-x*0.72).toFloat(),(1.0-x).toFloat(),1f) else { val m=-x; Color((1.0-m).toFloat(),(1.0-m*0.55).toFloat(),1f,1f) } }
