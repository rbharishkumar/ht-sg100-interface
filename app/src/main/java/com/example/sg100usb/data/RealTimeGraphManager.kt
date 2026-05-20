package com.example.sg100usb.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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

    fun add(rpm: Int, pwm: Int, actuatorCurrent: Int) {
        val now = System.currentTimeMillis()
        _series.update { current ->
            current.copy(
                rpm = (current.rpm + GraphPoint(now, rpm.toFloat())).takeLast(maxPoints),
                pwm = (current.pwm + GraphPoint(now, pwm.toFloat())).takeLast(maxPoints),
                actuatorCurrent = (current.actuatorCurrent + GraphPoint(now, actuatorCurrent.toFloat())).takeLast(maxPoints),
            )
        }
    }

    fun setZoom(zoom: Float) {
        _series.update { it.copy(zoom = zoom.coerceIn(1f, 6f)) }
    }
}
