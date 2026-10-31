# Hermi build rules (no minification in debug; safe defaults)
-keep class com.hermi.assistant.** { *; }
-dontwarn okhttp3.**
-dontwarn org.conscrypt.**
