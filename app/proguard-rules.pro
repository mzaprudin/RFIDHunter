# Whisper binds its external JNI methods by the generated class/method names.
# Keep the private bridge stable when R8 obfuscates the rest of the app.
-keep class ru.rfidhunter.app.voice.WhisperNative { *; }

# Honeywell's SDK is loaded from an AAR and wires callbacks through its
# service/manager APIs. Keep the vendor package stable for SDK reflection and
# listener dispatch; the AAR remains the source of the public hardware API.
-keep class com.honeywell.rfidservice.** { *; }

# Vosk loads model/decoder implementation classes reflectively from its
# Android native wrapper.
-keep class org.vosk.** { *; }
