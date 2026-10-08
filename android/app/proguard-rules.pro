# LAN-Link keeps minification off for a predictable debug build.
# If you enable it later, keep WebSocket + ZXing + CameraX rules:
-keep class com.google.zxing.** { *; }
-keep class okhttp3.** { *; }
-keepclassmembers class com.lanlink.app.** { *; }
