# Ferrostar's core is Rust called through JNA (UniFFI). R8 must keep the generated bindings and
# JNA itself, or release builds fail at runtime with "UnsatisfiedLinkError"/"NoSuchMethodError".
-keep class uniffi.ferrostar.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn java.awt.**
