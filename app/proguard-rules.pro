
# Ignore Kotlin metadata parsing warnings in R8
-dontwarn kotlin.**
-dontwarn kotlinx.**
-keepclassmembers class * {
    @kotlin.Metadata *;
}
-ignorewarnings
