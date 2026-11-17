package com.spark.companion.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.RemoteException
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.spark.companion.ui.MainActivity

/**
 * These three services are what Android requires for an app to be selectable
 * under Settings → Apps → Default apps → Digital assistant app. When the user
 * invokes the assistant (long-press home / power, or the corner swipe), Spark
 * opens and starts listening.
 */
class SparkVoiceInteractionService : VoiceInteractionService()

class SparkSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = SparkSession(this)
}

class SparkSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_LISTEN, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            // nothing more we can do from here
        }
        hide()
    }
}

/**
 * Placeholder required by the voice-interaction contract. Spark does its own
 * listening through the phone's real recognition engine (see SpeechInput).
 */
class SparkRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        try {
            listener?.error(SpeechRecognizer.ERROR_CLIENT)
        } catch (e: RemoteException) {
            // caller went away
        }
    }

    override fun onCancel(listener: Callback?) = Unit

    override fun onStopListening(listener: Callback?) = Unit
}
