package de.meegread.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.meegread.app.model.SpectralPoint
import kotlin.math.max

@Composable
fun SignalChart(values: List<Double>, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val step = max(1, values.size / 1200)
    val data = values.filterIndexed { index, _ -> index % step == 0 }.map { if (it.isFinite()) it else 0.0 }
    val minimum = data.minOrNull() ?: 0.0
    val maximum = data.maxOrNull() ?: 0.0
    val range = (maximum - minimum).takeIf { it != 0.0 } ?: 1.0
    Box(modifier.fillMaxWidth().height(220.dp).background(Color.Black.copy(alpha = 0.32f), RoundedCornerShape(10.dp))) {
        Canvas(Modifier.matchParentSize().padding(10.dp)) {
            repeat(5) { index -> val y = size.height * index / 4f; drawLine(gridColor, Offset(0f,y), Offset(size.width,y), 1f) }
            if (data.size > 1) {
                val dx = size.width / (data.size - 1)
                for (index in 0 until data.lastIndex) {
                    val y1 = size.height - ((data[index] - minimum) / range * size.height).toFloat()
                    val y2 = size.height - ((data[index + 1] - minimum) / range * size.height).toFloat()
                    drawLine(lineColor, Offset(dx*index,y1), Offset(dx*(index+1),y2), 2f)
                }
            }
        }
    }
}

@Composable
fun PsdChart(points: List<SpectralPoint>, maxHz: Double = 80.0, modifier: Modifier = Modifier) {
    val data = points.filter { it.frequencyHz <= maxHz }; val powers = data.map { it.powerDensity }
    LineFrequencyChart(data.map { it.frequencyHz }, powers, maxHz, powers.maxOrNull()?.takeIf { it > 0.0 } ?: 1.0, MaterialTheme.colorScheme.secondary, modifier)
}

@Composable
fun CoherenceChart(frequencies: List<Double>, coherence: List<Double>, maxHz: Double = 80.0, modifier: Modifier = Modifier) {
    val pairs = frequencies.zip(coherence).filter { it.first <= maxHz }
    LineFrequencyChart(pairs.map { it.first }, pairs.map { it.second }, maxHz, 1.0, MaterialTheme.colorScheme.primary, modifier)
}

@Composable
private fun LineFrequencyChart(x: List<Double>, y: List<Double>, maxX: Double, maxY: Double, lineColor: Color, modifier: Modifier) {
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    Box(modifier.fillMaxWidth().height(180.dp).background(Color.Black.copy(alpha = 0.28f), RoundedCornerShape(10.dp))) {
        Canvas(Modifier.matchParentSize().padding(10.dp)) {
            repeat(5) { index -> val gx = size.width * index / 4f; val gy = size.height * index / 4f; drawLine(gridColor,Offset(gx,0f),Offset(gx,size.height),1f); drawLine(gridColor,Offset(0f,gy),Offset(size.width,gy),1f) }
            if (x.size > 1 && y.size == x.size) for (index in 0 until x.lastIndex) {
                val x1 = (x[index]/maxX*size.width).toFloat(); val x2 = (x[index+1]/maxX*size.width).toFloat(); val y1 = size.height - (y[index]/maxY*size.height).toFloat(); val y2 = size.height - (y[index+1]/maxY*size.height).toFloat(); drawLine(lineColor,Offset(x1,y1),Offset(x2,y2),2f)
            }
        }
    }
}
