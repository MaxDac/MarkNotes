# Project-specific ProGuard rules (minification is currently disabled).

# marknotes-sync: the UniFFI binding calls the Rust core through JNA, which uses reflection.
-keep class com.sun.jna.** { *; }
-keep class * extends com.sun.jna.** { *; }
-keep class io.github.maxdac.marknotes.sync.** { *; }
-dontwarn java.awt.**

