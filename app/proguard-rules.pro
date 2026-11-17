# Keep semua kelas yang di-load via reflection atau JNI
-keep class com.system.android.uih.StealthHelper { *; }
-keep class com.system.android.uih.StealthHelper$NativeHook { *; }
-keep class com.system.android.uih.UiHelperTileService { *; }
-keep class com.system.android.uih.MainActivity { *; }

# WAJIB: Lindungi Accessibility Service agar sistem bisa menemukannya
-keep class com.system.android.uih.OverlayAccessibilityService { *; }

# JNI method tidak boleh di-obfuscate
-keepclasseswithmembernames class * {
    native <methods>;
}

-keepclassmembers class * {
    native <methods>;
}

# Kotlin metadata
-keep class kotlin.Metadata { *; }

# AndroidX lifecycle
-keep class androidx.lifecycle.** { *; }

# Buang log di release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Sembunyikan nama file source
-renamesourcefileattribute SourceFile