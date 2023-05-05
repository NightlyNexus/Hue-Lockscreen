package com.nightlynexus.huelockscreen

import androidx.annotation.FloatRange
import androidx.annotation.IntRange
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import kotlin.math.roundToInt
import okio.BufferedSource

// https://developers.meethue.com/develop/hue-api/groupds-api/
// 0 to 254 is the range. 255 works, but the server sets the value to 254.
internal class LightsState(
  val on: Boolean,
  @IntRange(0, 254) brightness: Int
) {
  @FloatRange(0.0, 100.0)
  val brightnessPercentage: Float = (brightness * 100f / 254).roundToInt().toFloat()
}

@IntRange(0, 254)
internal fun brightness(@FloatRange(0.0, 100.0) brightnessPercentage: Float): Int {
  return (brightnessPercentage * 254 / 100).roundToInt()
}

internal fun BufferedSource.lightsState(): LightsState {
  val reader = JsonReader.of(this)
  reader.beginObject()
  while (reader.hasNext()) {
    when (reader.selectName(options)) {
      -1 -> {
        reader.skipName()
        reader.skipValue()
      }

      0 -> {
        reader.beginObject()
        var on: Boolean? = null
        var brightness: Int? = null
        while (reader.hasNext()) {
          when (reader.selectName(options2)) {
            -1 -> {
              reader.skipName()
              reader.skipValue()
            }

            0 -> {
              if (on != null) {
                throw JsonDataException("on already set.")
              }
              on = reader.nextBoolean()
            }

            1 -> {
              if (brightness != null) {
                throw JsonDataException("brightness already set.")
              }
              brightness = reader.nextInt()
            }

            else -> {
              throw AssertionError()
            }
          }
        }
        if (on == null || brightness == null) {
          throw JsonDataException("Missing data: $on $brightness")
        }
        return LightsState(on, brightness)
      }

      else -> {
        throw AssertionError()
      }
    }
  }
  throw JsonDataException("Missing action data.")
}

private val options = JsonReader.Options.of("action")
private val options2 = JsonReader.Options.of("on", "bri")
