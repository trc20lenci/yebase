
# Ignore Kotlin metadata parsing warnings in R8
-dontwarn kotlin.**
-dontwarn kotlinx.**
-keepclassmembers class * {
    @kotlin.Metadata *;
}
-ignorewarnings

# sherpa-onnx: классы вызываются из нативного кода (JNI)
-keep class com.k2fsa.sherpa.onnx.** { *; }
