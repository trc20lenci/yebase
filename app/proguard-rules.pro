
# Ignore Kotlin metadata parsing warnings in R8
-dontwarn kotlin.**
-dontwarn kotlinx.**
-keepclassmembers class * {
    @kotlin.Metadata *;
}
-ignorewarnings



# Vosk и JNA вызываются через JNI/рефлексию
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-dontwarn java.awt.**

# sherpa-onnx (TTS): классы вызываются из нативного кода
-keep class com.k2fsa.sherpa.onnx.** { *; }
