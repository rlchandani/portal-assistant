package com.portal.assistant.gemini

import com.portal.assistant.gemini.LiveClient.ServerEvent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Pure parsing + setup-JSON tests for the Live API boundary (no socket, no Android). */
class LiveClientTest {

    @Test fun audioFrameUsesCurrentRealtimeFieldAndPreservesPcm() {
        val pcm = byteArrayOf(0, 1, -128, 127, -1, 0)
        val message = JSONObject(LiveClient.buildRealtimeAudio(pcm))
        val realtime = message.getJSONObject("realtimeInput")
        assertFalse(realtime.has("mediaChunks"))
        val audio = realtime.getJSONObject("audio")
        assertEquals("audio/pcm;rate=16000", audio.getString("mimeType"))
        assertTrue(pcm.contentEquals(Base64.getDecoder().decode(audio.getString("data"))))
    }

    // ---- parseServerMessage ------------------------------------------------------------------------

    @Test fun setupCompleteIsReady() {
        assertEquals(listOf(ServerEvent.Ready), LiveClient.parseServerMessage("""{"setupComplete":{}}"""))
    }

    @Test fun garbageOrEmptyIsNoEvents() {
        assertEquals(emptyList<ServerEvent>(), LiveClient.parseServerMessage("not json"))
        assertEquals(emptyList<ServerEvent>(), LiveClient.parseServerMessage("{}"))
        assertEquals(emptyList<ServerEvent>(), LiveClient.parseServerMessage("""{"serverContent":{}}"""))
    }

    @Test fun inputTranscriptParsed() {
        val events = LiveClient.parseServerMessage(
            """{"serverContent":{"inputTranscription":{"text":"what's the weather"}}}""",
        )
        assertEquals(listOf(ServerEvent.InputTranscript("what's the weather")), events)
    }

    @Test fun emptyTranscriptIsDropped() {
        val events = LiveClient.parseServerMessage(
            """{"serverContent":{"inputTranscription":{"text":""}}}""",
        )
        assertEquals(emptyList<ServerEvent>(), events)
    }

    @Test fun outputTranscriptParsed() {
        val events = LiveClient.parseServerMessage(
            """{"serverContent":{"outputTranscription":{"text":"Let me tell you"}}}""",
        )
        assertEquals(listOf(ServerEvent.OutputTranscript("Let me tell you")), events)
    }

    @Test fun emptyOutputTranscriptIsDropped() {
        val events = LiveClient.parseServerMessage(
            """{"serverContent":{"outputTranscription":{"text":""}}}""",
        )
        assertEquals(emptyList<ServerEvent>(), events)
    }

    @Test fun modelTurnAudioThenOutputTranscriptInOrder() {
        val pcm = byteArrayOf(5, 6)
        val b64 = Base64.getEncoder().encodeToString(pcm)
        val json =
            """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"data":"$b64"}}]},""" +
                """"outputTranscription":{"text":"hello"},"turnComplete":true}}"""
        val events = LiveClient.parseServerMessage(json)
        assertEquals(4, events.size)
        assertTrue(events[0] is ServerEvent.ModelGenerating)
        assertTrue(events[1] is ServerEvent.Audio)
        assertEquals(ServerEvent.OutputTranscript("hello"), events[2])
        assertTrue(events[3] is ServerEvent.TurnComplete)
    }

    @Test fun modelTurnWithAudioEmitsGeneratingThenAudio() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val b64 = Base64.getEncoder().encodeToString(pcm)
        val json =
            """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm","data":"$b64"}}]}}}"""
        val events = LiveClient.parseServerMessage(json)
        assertEquals(2, events.size)
        assertTrue(events[0] is ServerEvent.ModelGenerating)
        assertTrue(events[1] is ServerEvent.Audio)
        assertTrue(pcm.contentEquals((events[1] as ServerEvent.Audio).pcm24k))
    }

    @Test fun textOnlyModelTurnEmitsGeneratingOnly() {
        // A "thinking"/text part with no inlineData — model took the turn but no audio yet.
        val json = """{"serverContent":{"modelTurn":{"parts":[{"text":"thinking"}]}}}"""
        assertEquals(listOf(ServerEvent.ModelGenerating), LiveClient.parseServerMessage(json))
    }

    @Test fun turnCompleteParsed() {
        assertEquals(
            listOf(ServerEvent.TurnComplete),
            LiveClient.parseServerMessage("""{"serverContent":{"turnComplete":true}}"""),
        )
    }

    @Test fun interruptedParsed() {
        assertEquals(
            listOf(ServerEvent.Interrupted),
            LiveClient.parseServerMessage("""{"serverContent":{"interrupted":true}}"""),
        )
    }

    @Test fun goAwayParsedToMillis() {
        val events = LiveClient.parseServerMessage("""{"goAway":{"timeLeft":"9.5s"}}""")
        assertEquals(1, events.size)
        assertEquals(9500L, (events[0] as ServerEvent.GoAway).timeLeftMs)
    }

    @Test fun combinedMessagePreservesOrder() {
        // input transcript, then model takes the turn, then turnComplete — in document order.
        val json =
            """{"serverContent":{"inputTranscription":{"text":"hi"},"modelTurn":{"parts":[{"text":"x"}]},"turnComplete":true}}"""
        val events = LiveClient.parseServerMessage(json)
        assertEquals(
            listOf(ServerEvent.InputTranscript("hi"), ServerEvent.ModelGenerating, ServerEvent.TurnComplete),
            events,
        )
    }

    // ---- buildSetup --------------------------------------------------------------------------------

    @Test fun setupHasAudioGoogleSearchTranscriptionAndVad() {
        val setup = JSONObject(LiveClient.buildSetup("gemini-2.5-flash-native-audio-latest", "be nice"))
            .getJSONObject("setup")

        assertEquals("models/gemini-2.5-flash-native-audio-latest", setup.getString("model"))
        assertEquals(
            "AUDIO",
            setup.getJSONObject("generationConfig").getJSONArray("responseModalities").getString(0),
        )
        assertEquals(
            "be nice",
            setup.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"),
        )
        // googleSearch present; no functionDeclarations entry when none are passed.
        val tools = setup.getJSONArray("tools")
        assertEquals(1, tools.length())
        assertTrue(tools.getJSONObject(0).has("googleSearch"))
        assertFalse(tools.getJSONObject(0).has("functionDeclarations"))
        // Both transcriptions on: input drives the user-speech signal, output drives the chat text.
        assertTrue(setup.has("inputAudioTranscription"))
        assertTrue(setup.has("outputAudioTranscription"))
        // VAD block.
        val vad = setup.getJSONObject("realtimeInputConfig").getJSONObject("automaticActivityDetection")
        assertEquals(LiveClient.VAD_PREFIX_PADDING_MS, vad.getInt("prefixPaddingMs"))
        assertEquals(LiveClient.VAD_SILENCE_DURATION_MS, vad.getInt("silenceDurationMs"))
    }

    @Test fun setupKeepsExistingModelsPrefix() {
        val setup = JSONObject(LiveClient.buildSetup("models/foo", "x")).getJSONObject("setup")
        assertEquals("models/foo", setup.getString("model"))
    }

    // ---- toolCall / toolCallCancellation -----------------------------------------------------------

    @Test fun toolCallOnlyPayload() {
        // toolCall with no serverContent → ToolCall event, not empty list
        val events = LiveClient.parseServerMessage(
            """{"toolCall":{"functionCalls":[{"id":"c1","name":"portal.get_time","args":{}}]}}""",
        )
        assertEquals(1, events.size)
        val tc = events[0] as ServerEvent.ToolCall
        assertEquals(1, tc.calls.size)
        assertEquals("c1", tc.calls[0].id)
        assertEquals("portal.get_time", tc.calls[0].name)
    }

    @Test fun toolCallArgsAsString() {
        // args delivered as a JSON-encoded string → defensively unpacked to JSONObject
        val events = LiveClient.parseServerMessage(
            """{"toolCall":{"functionCalls":[{"id":"c1","name":"portal.get_weather","args":"{\"unit\":\"c\"}"}]}}""",
        )
        val tc = events[0] as ServerEvent.ToolCall
        assertEquals("c", tc.calls[0].args.optString("unit"))
    }

    @Test fun toolCallCancellationParsed() {
        val events = LiveClient.parseServerMessage(
            """{"toolCallCancellation":{"ids":["c1","c2"]}}""",
        )
        assertEquals(1, events.size)
        assertEquals(listOf("c1", "c2"), (events[0] as ServerEvent.ToolCancel).ids)
    }

    @Test fun toolCallAndServerContentSameMessage() {
        // Same message can carry both toolCall and serverContent — merged in document order.
        val events = LiveClient.parseServerMessage(
            """{"toolCall":{"functionCalls":[{"id":"c1","name":"n","args":{}}]},""" +
                """"serverContent":{"turnComplete":true}}""",
        )
        assertEquals(2, events.size)
        assertTrue(events[0] is ServerEvent.ToolCall)
        assertEquals(ServerEvent.TurnComplete, events[1])
    }

    // ---- buildSetup ---------------------------------------------------------------------------------

    @Test fun emptyDeclarationsProducesSingleEntryTools() {
        // Passing an explicit empty list must not emit a functionDeclarations entry.
        for (model in listOf("m", "gemini-3.8-live", "models/gemini-3.8-live")) {
            val setup = JSONObject(LiveClient.buildSetup(model, "p", emptyList())).getJSONObject("setup")
            val tools = setup.getJSONArray("tools")
            assertEquals(1, tools.length())
            assertTrue(tools.getJSONObject(0).has("googleSearch"))
            assertFalse(tools.getJSONObject(0).has("functionDeclarations"))
        }
    }

    @Test fun declarationsAddSecondToolsEntry() {
        val decl = JSONObject("""{"name":"portal._spike_noop","description":"test"}""")
        val setup = JSONObject(LiveClient.buildSetup("m", "p", listOf(decl))).getJSONObject("setup")
        val tools = setup.getJSONArray("tools")
        assertEquals(2, tools.length())
        assertTrue(tools.getJSONObject(0).has("googleSearch"))
        val declArray = tools.getJSONObject(1).getJSONArray("functionDeclarations")
        assertEquals(1, declArray.length())
        assertEquals("portal._spike_noop", declArray.getJSONObject(0).getString("name"))
    }

    @Test fun setup38MakesEveryDeclarationBlocking() {
        val declarations = listOf(
            JSONObject("""{"name":"portal.get_time","description":"Get the time"}"""),
            JSONObject("""{"name":"portal.set_timer","description":"Set a timer","behavior":"NON_BLOCKING"}"""),
            JSONObject("""{"name":"portal.get_volume","description":"Get volume","behavior":"BLOCKING"}"""),
        )
        for (model in listOf("gemini-3.8-live", "models/gemini-3.8-live")) {
            val setup = JSONObject(LiveClient.buildSetup(model, "p", declarations)).getJSONObject("setup")
            assertEquals("models/gemini-3.8-live", setup.getString("model"))
            val serialized = setup.getJSONArray("tools").getJSONObject(1).getJSONArray("functionDeclarations")
            assertEquals(3, serialized.length())
            for ((index, name) in listOf("portal.get_time", "portal.set_timer", "portal.get_volume").withIndex()) {
                assertEquals(name, serialized.getJSONObject(index).getString("name"))
                assertEquals(model, "BLOCKING", serialized.getJSONObject(index).optString("behavior"))
            }
        }
    }

    @Test fun setup38DoesNotMutateDeclarationsReusedBy25Fallback() {
        val plain = JSONObject("""{"name":"portal.get_time","description":"Get the time"}""")
        val async = JSONObject("""{"name":"portal.get_volume","description":"Get volume","behavior":"NON_BLOCKING"}""")
        val declarations = listOf(plain, async)
        val originals = declarations.map { it.toString() }

        for (model in listOf("gemini-3.8-live", "models/gemini-3.8-live")) {
            LiveClient.buildSetup(model, "p", declarations)
            assertFalse(plain.has("behavior"))
            assertEquals("NON_BLOCKING", async.getString("behavior"))
            assertEquals(originals, declarations.map { it.toString() })
        }

        val fallback = JSONObject(LiveClient.buildSetup("gemini-2.5-flash-native-audio-latest", "p", declarations))
            .getJSONObject("setup").getJSONArray("tools").getJSONObject(1).getJSONArray("functionDeclarations")
        assertFalse(fallback.getJSONObject(0).has("behavior"))
        assertEquals("NON_BLOCKING", fallback.getJSONObject(1).getString("behavior"))
    }

    @Test fun setup38PreservesDeclarationSchemas() {
        val declaration = JSONObject(
            """{
                "name":"portal.set_timer",
                "description":"Set a timer",
                "parameters":{
                    "type":"OBJECT",
                    "properties":{"seconds":{"type":"INTEGER","description":"Duration in seconds"}},
                    "required":["seconds"]
                },
                "response":{"type":"OBJECT","properties":{"id":{"type":"STRING"}}}
            }""",
        )
        val setup = JSONObject(LiveClient.buildSetup("gemini-3.8-live", "p", listOf(declaration)))
            .getJSONObject("setup")
        val serialized = setup.getJSONArray("tools").getJSONObject(1)
            .getJSONArray("functionDeclarations").getJSONObject(0)
        assertEquals("BLOCKING", serialized.optString("behavior"))
        serialized.remove("behavior")
        assertEquals(jsonValue(declaration), jsonValue(serialized))
    }

    @Test fun otherModelsKeepDeclarationBehaviorUnchanged() {
        val declarations = listOf(
            JSONObject("""{"name":"portal.get_time","description":"Get the time"}"""),
            JSONObject("""{"name":"portal.get_volume","description":"Get volume","behavior":"NON_BLOCKING"}"""),
        )
        for (model in listOf(
            "gemini-2.5-flash-native-audio-latest",
            "models/gemini-2.5-flash-native-audio-latest",
            "arbitrary-model",
            "models/foo",
            "gemini-3.8-live-preview",
            "models/gemini-3.8-live-preview",
        )) {
            val setup = JSONObject(LiveClient.buildSetup(model, "p", declarations)).getJSONObject("setup")
            val serialized = setup.getJSONArray("tools").getJSONObject(1).getJSONArray("functionDeclarations")
            assertEquals(2, serialized.length())
            assertFalse(model, serialized.getJSONObject(0).has("behavior"))
            assertEquals(model, "NON_BLOCKING", serialized.getJSONObject(1).getString("behavior"))
            declarations.forEachIndexed { index, declaration ->
                assertEquals(model, jsonValue(declaration), jsonValue(serialized.getJSONObject(index)))
            }
        }
    }

    @Test fun setup38KeepsAudioTranscriptionSearchAndVad() {
        val declaration = JSONObject("""{"name":"portal.get_time","description":"Get the time"}""")
        val setup = JSONObject(LiveClient.buildSetup("gemini-3.8-live", "be nice", listOf(declaration)))
            .getJSONObject("setup")
        val expected = JSONObject(
            """{
                "model":"models/gemini-3.8-live",
                "generationConfig":{"responseModalities":["AUDIO"]},
                "systemInstruction":{"parts":[{"text":"be nice"}]},
                "tools":[
                    {"googleSearch":{}},
                    {"functionDeclarations":[
                        {"name":"portal.get_time","description":"Get the time","behavior":"BLOCKING"}
                    ]}
                ],
                "inputAudioTranscription":{},
                "outputAudioTranscription":{},
                "realtimeInputConfig":{"automaticActivityDetection":{
                    "startOfSpeechSensitivity":"START_SENSITIVITY_HIGH",
                    "endOfSpeechSensitivity":"END_SENSITIVITY_LOW",
                    "prefixPaddingMs":300,
                    "silenceDurationMs":600
                }}
            }""",
        )
        // Exact schema comparison also guards against unsupported thinking/proactivity flags.
        assertEquals("Unexpected Gemini 3.8 setup", jsonValue(expected), jsonValue(setup))
    }

    // ---- buildClientText ---------------------------------------------------------------------------

    @Test fun clientTextIsACompleteUserTurn() {
        val cc = JSONObject(LiveClient.buildClientText("what's the weather?")).getJSONObject("clientContent")
        assertTrue(cc.getBoolean("turnComplete")) // finished turn → model answers without a VAD wait
        val turn = cc.getJSONArray("turns").getJSONObject(0)
        assertEquals("user", turn.getString("role"))
        assertEquals(
            "what's the weather?",
            turn.getJSONArray("parts").getJSONObject(0).getString("text"),
        )
    }

    // ---- parseDurationMs ---------------------------------------------------------------------------

    @Test fun durationParsing() {
        assertEquals(9500L, LiveClient.parseDurationMs("9.5s"))
        assertEquals(800L, LiveClient.parseDurationMs("0.8s"))
        assertEquals(10000L, LiveClient.parseDurationMs("10s"))
        assertEquals(0L, LiveClient.parseDurationMs(""))
        assertEquals(0L, LiveClient.parseDurationMs("garbage"))
    }

    /** Compare JSON by content without depending on object key order. */
    private fun jsonValue(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().associateWith { jsonValue(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { jsonValue(value.get(it)) }
        else -> value
    }
}
