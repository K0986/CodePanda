# ADB protocol classes use reflection-free code; keep model classes.
-keepclassmembers class com.codepanda.otg.** { *; }

# Keep Compose runtime happy
-keep class androidx.compose.runtime.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler { *; }
