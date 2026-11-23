package com.locapeer.ui.components

import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * A slider driven by [value], with the signature of Material3's value-based `Slider`, which is
 * deprecated in favour of the [SliderState] overload.
 *
 * `rememberSliderState` only seeds the state with an initial value, so a value changed elsewhere
 * (typed into a field, restored from settings) would no longer move the thumb. This keeps the state
 * in step with [value] on every composition, and rebuilds it when [steps] or [valueRange] change.
 */
@Composable
fun AppSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null
) {
    val state = remember(steps, valueRange) {
        SliderState(value = value, steps = steps, trackRange = valueRange)
    }
    if (state.value != value) state.value = value
    Slider(
        state = state,
        onValueChange = { newValue ->
            state.value = newValue
            onValueChange(newValue)
        },
        modifier = modifier,
        enabled = enabled,
        onValueChangeFinished = onValueChangeFinished
    )
}
