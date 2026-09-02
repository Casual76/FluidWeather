package dev.pampa.fluidweather.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.pampa.fluidweather.core.model.GlassLevel
import dev.pampa.fluidweather.core.model.SamplingMode
import dev.pampa.fluidweather.strings.descriptionRes
import dev.pampa.fluidweather.strings.labelRes

/*
 * Le etichette delle modalita' di campionamento e dei livelli del vetro: le usano diagnostica,
 * onboarding e impostazioni. Le parole vivono in core-strings (fase 17); qui solo la comodita'
 * di chiamarle da un composable.
 */

@Composable
internal fun SamplingMode.label(): String = stringResource(labelRes())

@Composable
internal fun SamplingMode.description(): String = stringResource(descriptionRes())

@Composable
internal fun GlassLevel.label(): String = stringResource(labelRes())

@Composable
internal fun GlassLevel.description(): String = stringResource(descriptionRes())
