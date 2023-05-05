package com.nightlynexus.huelockscreen

import android.app.PendingIntent
import android.content.Intent
import android.os.Build.VERSION.SDK_INT
import android.service.controls.Control
import android.service.controls.ControlsProviderService
import android.service.controls.DeviceTypes
import android.service.controls.actions.BooleanAction
import android.service.controls.actions.ControlAction
import android.service.controls.actions.FloatAction
import android.service.controls.templates.ControlButton
import android.service.controls.templates.ControlTemplate
import android.service.controls.templates.RangeTemplate
import android.service.controls.templates.ToggleRangeTemplate
import androidx.annotation.FloatRange
import androidx.annotation.StringRes
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Flow
import java.util.function.Consumer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

private val client = OkHttpClient()

class HueControlsProviderService : ControlsProviderService() {
  private val pendingIntentRequestCode = 0
  private val lightId = "LIGHT_ID"
  private val brightnessRangeId = "BRIGHTNESS_RANGE_ID"
  @StringRes private val lightTitleRes = R.string.light_title
  private val lightType = DeviceTypes.TYPE_LIGHT
  @StringRes private val lightButtonActionDescription = R.string.light_button_action_description
  private val lightMinValue = 0f
  private val lightMaxValue = 100f
  private val lightStepValue = 1f
  private val lightFormat = "%.0f%%"
  private val lightPublisher = LightPublisher()

  override fun createPublisherForAllAvailable(): Flow.Publisher<Control> {
    return Flow.Publisher<Control> { subscriber ->
      subscriber.onNext(
        createStatelessControl(
          lightId,
          getString(lightTitleRes),
          lightType
        )
      )
      subscriber.onComplete()
    }
  }

  private fun createStatelessControl(id: String, title: String, type: Int): Control {
    val intent = Intent(this, ColorConfigurationActivity::class.java)
    val action = PendingIntent.getActivity(
      this,
      pendingIntentRequestCode,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    return Control.StatelessBuilder(id, action)
      .setTitle(title)
      .setCustomColor(getColorStateList(R.color.light_color))
      .setDeviceType(type)
      .build()
  }

  private fun createStatefulControl(
    id: String,
    title: String,
    type: Int,
    template: ControlTemplate
  ): Control {
    val intent = Intent(this, ColorConfigurationActivity::class.java)
    val action = PendingIntent.getActivity(
      this,
      pendingIntentRequestCode,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val controlBuilder = Control.StatefulBuilder(id, action)
      .setTitle(title)
      .setCustomColor(getColorStateList(R.color.light_color))
      .setDeviceType(type)
      .setStatus(Control.STATUS_OK)
      .setControlTemplate(template)
    if (SDK_INT >= 33) {
      controlBuilder
        .setAuthRequired(false)
    }
    return controlBuilder.build()
  }

  private fun createLight(on: Boolean, brightnessPercentage: Float): Control {
    return createStatefulControl(
      lightId,
      getString(lightTitleRes),
      lightType,
      ToggleRangeTemplate(
        lightId,
        ControlButton(
          on,
          getText(lightButtonActionDescription)
        ),
        RangeTemplate(
          brightnessRangeId,
          lightMinValue,
          lightMaxValue,
          brightnessPercentage,
          lightStepValue,
          lightFormat
        )
      )
    )
  }

  override fun createPublisherFor(controlIds: List<String>): Flow.Publisher<Control> {
    check(controlIds.size == 1 && controlIds.first() == lightId)
    return lightPublisher
  }

  override fun performControlAction(
    controlId: String,
    action: ControlAction,
    consumer: Consumer<Int>
  ) {
    check(controlId == lightId)
    when (action) {
      is BooleanAction -> {
        if (action.newState) {
          lightPublisher.turnOn()
        } else {
          lightPublisher.turnOff()
        }
      }

      is FloatAction -> {
        lightPublisher.updateBrightness(action.newValue)
      }

      else -> {
        throw IllegalStateException("Unexpected action type: $action")
      }
    }
    consumer.accept(ControlAction.RESPONSE_OK)
  }

  private inner class LightPublisher : Flow.Publisher<Control>, Callback {
    private val subscribers = CopyOnWriteArrayList<Flow.Subscriber<in Control>>()
    private val statusLock = Any()
    private var on = false
    @FloatRange(0.0, 100.0) private var brightnessPercentage = 0f
    private var onOffCall: Call? = null
    private var brightnessCall: Call? = null

    override fun onFailure(call: Call, e: IOException) {
      if (call.isCanceled()) {
        return
      }
      // TODO
    }

    override fun onResponse(call: Call, response: Response) {
      if (!response.isSuccessful) {
        response.close()
        // TODO
        return
      }
      // TODO
      response.close()
    }

    fun turnOn() {
      val brightnessPercentage: Float
      synchronized(statusLock) {
        on = true
        brightnessPercentage = this.brightnessPercentage
      }
      val control = createLight(true, brightnessPercentage)
      for (i in subscribers.indices) {
        val subscriber = subscribers[i]
        subscriber.onNext(control)
      }

      onOffCall?.cancel()
      val call = newOnCall()
      call.enqueue(this)
      onOffCall = call
    }

    fun turnOff() {
      val brightnessPercentage: Float
      synchronized(statusLock) {
        on = false
        brightnessPercentage = this.brightnessPercentage
      }
      val control = createLight(false, brightnessPercentage)
      for (i in subscribers.indices) {
        val subscriber = subscribers[i]
        subscriber.onNext(control)
      }

      onOffCall?.cancel()
      val call = newOffCall()
      call.enqueue(this)
      onOffCall = call
    }

    fun updateBrightness(brightnessPercentage: Float) {
      val on: Boolean
      synchronized(statusLock) {
        on = this.on
        this.brightnessPercentage = brightnessPercentage
      }
      val control = createLight(on, brightnessPercentage)
      for (i in subscribers.indices) {
        val subscriber = subscribers[i]
        subscriber.onNext(control)
      }

      brightnessCall?.cancel()
      val call = newBrightnessCall(brightnessPercentage)
      call.enqueue(this)
      brightnessCall = call
    }

    override fun subscribe(subscriber: Flow.Subscriber<in Control>) {
      val subscription = LightSubscription(subscriber)
      subscriber.onSubscribe(subscription)

      // Give the new subscriber the state.
      subscription.updateStatus()

      subscribers += subscriber
    }

    private inner class LightSubscription(
      private val subscriber: Flow.Subscriber<in Control>
    ) : Flow.Subscription, Callback {
      private val statusCallLock = Any()
      private var statusCall: Call? = null

      override fun request(n: Long) {
        updateStatus()
      }

      override fun cancel() {
        subscribers -= subscriber
      }

      fun updateStatus() {
        val call: Call
        synchronized(statusCallLock) {
          statusCall?.cancel()
          call = newStatusCall()
          statusCall = call
        }
        call.enqueue(this)
      }

      override fun onFailure(call: Call, e: IOException) {
        if (call.isCanceled()) {
          return
        }
        // TODO
      }

      override fun onResponse(call: Call, response: Response) {
        if (!response.isSuccessful) {
          response.close()
          // TODO
          return
        }
        val lightsState = response.body!!.source().use { source ->
          source.lightsState()
        }
        synchronized(statusLock) {
          on = lightsState.on
          brightnessPercentage = lightsState.brightnessPercentage
        }
        val control = createLight(lightsState.on, lightsState.brightnessPercentage)
        subscriber.onNext(control)
      }
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    cancelAll()
  }

  private val statusUrl: HttpUrl
  private val actionUrl: HttpUrl

  init {
    HttpUrl.Builder()
      .scheme("http")
      .host(BuildConfig.address)
      .addEncodedPathSegment("api")
      .addEncodedPathSegment(BuildConfig.access)
      .addEncodedPathSegment("groups")
      .addEncodedPathSegment("1").run {
        statusUrl = build()
        actionUrl = addEncodedPathSegment("action").build()
      }
  }

  private fun newStatusCall(): Call {
    return client.newCall(
      Request.Builder()
        .url(statusUrl)
        .tag(this)
        .build()
    )
  }

  private fun newOnCall(): Call {
    return client.newCall(
      Request.Builder()
        .url(actionUrl)
        .put("""{"on":true}""".toRequestBody("application/json; charset=utf-8".toMediaType()))
        .tag(this)
        .build()
    )
  }

  private fun newOffCall(): Call {
    return client.newCall(
      Request.Builder()
        .url(actionUrl)
        .put("""{"on":false}""".toRequestBody("application/json; charset=utf-8".toMediaType()))
        .tag(this)
        .build()
    )
  }

  private fun newBrightnessCall(brightnessPercentage: Float): Call {
    val brightness = brightness(brightnessPercentage)
    return client.newCall(
      Request.Builder()
        .url(actionUrl)
        .put(
          """{"bri":$brightness}""".toRequestBody(
            "application/json; charset=utf-8".toMediaType()
          )
        )
        .tag(this)
        .build()
    )
  }

  private fun cancelAll() {
    val dispatcher = client.dispatcher
    val queuedCalls = dispatcher.queuedCalls()
    val runningCalls = dispatcher.runningCalls()
    for (i in queuedCalls.indices) {
      val call = queuedCalls[i]
      if (call.request().tag() === this) {
        call.cancel()
      }
    }
    for (i in runningCalls.indices) {
      val call = runningCalls[i]
      if (call.request().tag() === this) {
        call.cancel()
      }
    }
  }
}
