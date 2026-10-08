# Gemini 3.8 Live upgrade

This fork adds `gemini-3.8-live` to Jarvis and makes it the default when no model
has been selected. Settings → Model
retains `gemini-2.5-flash-native-audio-latest` for manual fallback. Existing API keys,
location, music preferences and external-tool selections continue using `AppPrefs`.

Gemini 3.8 defaults to asynchronous function calls. The existing Jarvis engine waits
for tool results before completing a turn, so this client explicitly sends
`behavior: BLOCKING` on 3.8 function declarations. It copies those declarations
instead of mutating the shared tool registry, preserving the original 2.5 requests.
Audio frames use the current `realtimeInput.audio` field instead of deprecated
`mediaChunks`. The PCM format, microphone gain, VAD settings, playback queue and
conversation reducer are unchanged. No thinking or proactivity flags are added.

## Build and test

Use JDK 21 and Android SDK 36. The existing composite build needs the
`portal-commons` repository alongside `portal-assistant`; the validated commons
revision is `8dc290af764baba4b8f6477235ccc40bcff20327`.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.portal.assistant.test/com.portal.assistant.JarvisUpgradeInstrumentation
```

The opt-in instrumentation uses the configured key and consumes Gemini API quota.
It exercises the production Live client and real speaker playback, checks two text
turns, a time-tool call and a generated speech turn on 3.8, the 2.5 fallback and a
fresh 3.8 connection, then leaves 3.8 selected after success.
On failure it selects 2.5. It exports timing and pass/fail metadata, not credentials
or conversation transcripts. The audio-input fixture is an offline generated
16 kHz mono PCM recording of “Jarvis, what is two plus two?” No microphone is
opened by this instrumentation. It continues streaming silence until a response
starts, matching the live microphone's continuous stream. A one-second silence
tail followed by abrupt EOF was insufficient for 3.8's automatic turn detection
in the initial replay; that is not a reason to add a delay to the production app.

## Validation on Portal+

The debug build passed all 346 JVM tests. The native check passed nine turns across
three sessions (3.8, 2.5, fresh 3.8): six text turns and three generated-speech turns,
with transcription, time-tool responses, turn completion and drained speaker
playback verified. The installed package is version code 6, `2.3.1-portal-live`.
The original saved API key was verified unchanged after installation.

| Native check | Gemini 3.8 | Gemini 2.5 |
| --- | --- | --- |
| Text submission to first audio | 519–911 ms | 1,711–1,924 ms |
| End of generated speech to first audio | 1,533–1,589 ms | 2,622 ms |

These are a small compatibility sample on one Portal+, not a latency guarantee.
The synthetic speech check validates the PCM transport and playback, not room
distance, microphone acoustics, or wake-word recognition. Those paths retain the
existing implementation.

## Installation signing

Android accepts an in-place update only when both APKs use the same signing key.
The original installed Jarvis APK and this local build use different debug keys.
For the first migration, preserve the original APK and protect complete internal
and external app-data backups before replacing the package. Restore archive
ownership to the new app UID, verify every restored file, and restore previously
granted permissions. Subsequent builds can use `adb install -r` with this same
local signing key. Never put an API key, signing keystore or device-data backup
in this repository.

## References

- [Gemini 3.8 Live model and migration notes](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-live)
- [Live API](https://ai.google.dev/gemini-api/docs/live-api)
- [Live WebSocket protocol](https://ai.google.dev/api/live)
- Upstream base: `4b50bfddb064577c694f865b8ae4045eac4d1469`
