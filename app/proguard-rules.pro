
# Ignore Kotlin metadata parsing warnings in R8
-dontwarn kotlin.**
-dontwarn kotlinx.**
-keepclassmembers class * {
    @kotlin.Metadata *;
}
-ignorewarnings

# sherpa-onnx: классы вызываются из нативного кода (JNI)
-keep class com.k2fsa.sherpa.onnx.** { *; }

# MediaPipe Tasks: JNI и protobuf
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.proto.**
-dontwarn com.google.protobuf.**
