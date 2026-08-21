package org.lsposed.manager.ui.compose

import android.animation.TimeInterpolator
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

internal object PagerSpringInterpolator : TimeInterpolator {
    private const val STIFFNESS = 322.2
    private const val DAMPING_COEFFICIENT = 32.31
    private const val SIMULATION_DURATION_SECONDS = 0.5
    private val angularFrequency = sqrt(STIFFNESS)
    private val dampingRatio = DAMPING_COEFFICIENT / (2.0 * angularFrequency)
    private val dampedFrequency = angularFrequency * sqrt(1.0 - dampingRatio * dampingRatio)

    override fun getInterpolation(input: Float): Float {
        if (input <= 0f) return 0f
        if (input >= 1f) return 1f

        val seconds = input.toDouble() * SIMULATION_DURATION_SECONDS
        val decay = exp(-dampingRatio * angularFrequency * seconds)
        val oscillation = cos(dampedFrequency * seconds) +
            dampingRatio * angularFrequency / dampedFrequency *
            sin(dampedFrequency * seconds)
        return (1.0 - decay * oscillation).toFloat()
    }
}
