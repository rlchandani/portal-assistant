package com.portal.assistant

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import com.portal.assistant.audio.PcmPlayer
import com.portal.assistant.conversation.FunctionCall
import com.portal.assistant.conversation.ToolResult
import com.portal.assistant.conversation.backend.VoiceBackend
import com.portal.assistant.gemini.GeminiModel
import com.portal.assistant.gemini.LiveClient
import com.portal.assistant.system.AppPrefs
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Opt-in device verification using synthetic text/audio, the production Live client and real AudioTrack.
 * Never captures microphone audio, invokes household controls, or exports credentials/transcripts.
 */
class JarvisUpgradeInstrumentation : Instrumentation() {
    private var checkpoint = "start"
    private var diagnostics = JSONObject()
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val report = JSONObject()
        var successful = false
        try {
            check(GeminiModel.AVAILABLE.contains(GeminiModel.LEGACY_ID))
            check(AppPrefs.apiKey(targetContext) != null)
            val sessions = JSONArray()
            for (model in listOf(GeminiModel.ID, GeminiModel.LEGACY_ID, GeminiModel.ID)) {
                AppPrefs.setModelId(targetContext, model)
                check(AppPrefs.modelId(targetContext) == model)
                sessions.put(checkSession(model))
            }
            report.put("sessions", sessions)
            successful = true
            report.put("passed", true)
        } catch (error: Throwable) {
            // Exception text from a transport may contain credentials. Export the type only.
            report.put("passed", false).put("errorType", error.javaClass.simpleName)
                .put("checkpoint", checkpoint).put("diagnostics", diagnostics)
        } finally {
            AppPrefs.setModelId(targetContext, if (successful) GeminiModel.ID else GeminiModel.LEGACY_ID)
            report.put("selectedModel", AppPrefs.modelId(targetContext))
        }
        finish(if (successful) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("report", report.toString()) })
    }

    private fun checkSession(model: String): JSONObject {
        val ready = CountDownLatch(1)
        val error = AtomicReference<String?>()
        val player = PcmPlayer()
        val received = AtomicLong()
        val lastAudio = AtomicLong()
        val lastDrained = AtomicLong()
        val toolCalls = AtomicLong()
        val completed = AtomicReference(CountDownLatch(1))
        val firstAudio = AtomicLong()
        val outputTranscript = AtomicBoolean()
        val inputTranscript = AtomicBoolean()
        lateinit var client: LiveClient
        val declaration = JSONObject("""{
            "name":"portal.get_time","description":"Get the current time.",
            "parameters":{"type":"OBJECT","properties":{}}
        }""")
        val listener = object : VoiceBackend.Listener {
            override fun onReady() { ready.countDown() }
            override fun onInputTranscript(textDelta: String) { inputTranscript.set(true) }
            override fun onOutputTranscript(textDelta: String) { outputTranscript.set(true) }
            override fun onModelGenerating() = Unit
            override fun onInterrupted() { player.flush() }
            override fun onAudio(pcm24k: ByteArray) {
                val now = SystemClock.elapsedRealtime()
                firstAudio.compareAndSet(0, now)
                lastAudio.set(now)
                received.addAndGet(pcm24k.size.toLong())
                player.enqueue(pcm24k)
            }
            override fun onTurnComplete() { completed.get().countDown() }
            override fun onToolCall(calls: List<FunctionCall>) {
                check(calls.all { it.name == "portal.get_time" })
                toolCalls.addAndGet(calls.size.toLong())
                client.sendToolResponse(calls.map {
                    ToolResult(it.id, it.name, JSONObject()
                        .put("status", "ok").put("time", "10:42 AM").put("retryable", false))
                })
            }
            override fun onToolCancel(ids: List<String>) = Unit
            override fun onServerClosingSoon(graceMs: Long) = Unit
            override fun onError(message: String) {
                error.set("live_session_error")
                ready.countDown()
                completed.get().countDown()
            }
            override fun onClosed() = Unit
        }
        player.setOnDrained { lastDrained.set(SystemClock.elapsedRealtime()) }
        client = LiveClient(
            apiKey = requireNotNull(AppPrefs.apiKey(targetContext)),
            model = AppPrefs.modelId(targetContext),
            systemPrompt = "This is a synthetic verification. Answer in one short sentence. " +
                "For a time question, call portal.get_time and use the returned time.",
            functionDeclarations = listOf(declaration),
            listener = listener,
        )
        try {
            checkpoint = "$model/setup"
            player.start()
            val connectionStart = SystemClock.elapsedRealtime()
            client.connect()
            check(ready.await(25, TimeUnit.SECONDS) && error.get() == null)
            val report = JSONObject().put("model", model)
                .put("setupMs", SystemClock.elapsedRealtime() - connectionStart)
            val turns = JSONArray()
            for (prompt in listOf(
                "Say only: device check complete.",
                "What time is it? Use the time tool and answer briefly.",
            )) {
                completed.set(CountDownLatch(1))
                firstAudio.set(0)
                outputTranscript.set(false)
                val receivedBefore = received.get()
                val toolsBefore = toolCalls.get()
                val started = SystemClock.elapsedRealtime()
                client.sendText(prompt)
                check(completed.get().await(45, TimeUnit.SECONDS) && error.get() == null)
                check(received.get() > receivedBefore && outputTranscript.get())
                val playbackDeadline = SystemClock.elapsedRealtime() + 15_000
                while (player.playedBytes() < received.get() || lastDrained.get() < lastAudio.get()) {
                    check(SystemClock.elapsedRealtime() < playbackDeadline)
                    Thread.sleep(20)
                }
                turns.put(JSONObject().put("firstAudioMs", firstAudio.get() - started)
                    .put("audioBytes", received.get() - receivedBefore)
                    .put("toolCalls", toolCalls.get() - toolsBefore)
                    .put("playbackDrained", true))
            }
            check(toolCalls.get() > 0)
            checkpoint = "$model/voice_asset"
            completed.set(CountDownLatch(1))
            firstAudio.set(0)
            outputTranscript.set(false)
            inputTranscript.set(false)
            val voiceReceivedBefore = received.get()
            val voice = context.assets.open("jarvis-synthetic-command.pcm").use { it.readBytes() }
            checkpoint = "$model/voice_send"
            // Same 16 kHz mono PCM format as MicCapture, paced at real time; no microphone is opened.
            for (offset in voice.indices step 3200) {
                client.sendAudio(voice.copyOfRange(offset, minOf(offset + 3200, voice.size)))
                Thread.sleep(100)
            }
            val voiceSent = SystemClock.elapsedRealtime()
            // A real listening microphone keeps streaming until a reply starts. A fixed one-second
            // tail followed by abrupt EOF can leave newer models waiting for the rest of the utterance.
            val voiceDeadline = SystemClock.elapsedRealtime() + 45_000
            while (firstAudio.get() == 0L && completed.get().count > 0 && error.get() == null) {
                check(SystemClock.elapsedRealtime() < voiceDeadline)
                client.sendAudio(ByteArray(3200))
                Thread.sleep(100)
            }
            checkpoint = "$model/voice_completion"
            val voiceComplete = completed.get().await(45, TimeUnit.SECONDS)
            diagnostics = JSONObject().put("completed", voiceComplete)
                .put("inputTranscribed", inputTranscript.get())
                .put("outputTranscribed", outputTranscript.get())
                .put("audioBytes", received.get() - voiceReceivedBefore)
                .put("connectionError", error.get() != null)
            check(voiceComplete && error.get() == null)
            check(inputTranscript.get() && outputTranscript.get() && received.get() > voiceReceivedBefore)
            checkpoint = "$model/voice_playback"
            val voicePlaybackDeadline = SystemClock.elapsedRealtime() + 15_000
            while (player.playedBytes() < received.get() || lastDrained.get() < lastAudio.get()) {
                check(SystemClock.elapsedRealtime() < voicePlaybackDeadline)
                Thread.sleep(20)
            }
            report.put("voiceInput", JSONObject()
                .put("inputTranscribed", true)
                .put("firstAudioAfterInputMs", firstAudio.get() - voiceSent)
                .put("audioBytes", received.get() - voiceReceivedBefore)
                .put("playbackDrained", true))
            return report.put("turns", turns)
        } finally {
            client.close()
            player.stop()
        }
    }
}
