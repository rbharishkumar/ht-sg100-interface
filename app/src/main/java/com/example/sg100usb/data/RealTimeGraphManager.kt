package com.example.sg100usb.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

data class GraphPoint(
    val timeMillis: Long,
    val value: Float,
)

data class GraphSeries(
    val rpm: List<GraphPoint> = emptyList(),
    val pwm: List<GraphPoint> = emptyList(),
    val actuatorCurrent: List<GraphPoint> = emptyList(),
    val zoom: Float = 1f,
)

class RealTimeGraphManager(private val maxPoints: Int = 240) {
    private val _series = MutableStateFlow(GraphSeries())
    val series: StateFlow<GraphSeries> = _series.asStateFlow()
    private val rpmPoints = ArrayDeque<GraphPoint>(maxPoints)
    private val pwmPoints = ArrayDeque<GraphPoint>(maxPoints)
    private val currentPoints = ArrayDeque<GraphPoint>(maxPoints)
    private var zoom = 1f
    private var lastEmitMs = 0L

    fun add(rpm: Float, pwm: Float, actuatorCurrent: Float) {
        val now = System.currentTimeMillis()
        synchronized(this) {
            append(rpmPoints, GraphPoint(now, rpm))
            append(pwmPoints, GraphPoint(now, pwm))
            append(currentPoints, GraphPoint(now, actuatorCurrent))
            if (now - lastEmitMs >= GRAPH_EMIT_INTERVAL_MS) {
                publishLocked(now)
            }
        }
    }

    fun setZoom(zoom: Float) {
        synchronized(this) {
            this.zoom = zoom.coerceIn(1f, 6f)
            publishLocked(System.currentTimeMillis())
        }
    }

    private fun append(points: ArrayDeque<GraphPoint>, point: GraphPoint) {
        points.addLast(point)
        while (points.size > maxPoints) points.removeFirst()
    }

    private fun publishLocked(now: Long) {
        lastEmitMs = now
        _series.value = GraphSeries(
            rpm = rpmPoints.toList(),
            pwm = pwmPoints.toList(),
            actuatorCurrent = currentPoints.toList(),
            zoom = zoom,
        )
    }

    private companion object {
        const val GRAPH_EMIT_INTERVAL_MS = 100L
    }
}
