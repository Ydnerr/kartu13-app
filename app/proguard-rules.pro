# Tidak ada aturan khusus: app ini nggak pakai library pihak ketiga.

# WebRTC JNI and reflection-backed native bindings
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# libwebrtc (v130+) manggil org.jni_zero.* lewat JNI waktu library native dimuat.
# Tanpa aturan ini R8 membuang kelasnya, lalu app force close begitu voice chat dimulai.
-keep class org.jni_zero.** { *; }
-dontwarn org.jni_zero.**
-keepattributes *Annotation*
-keepclasseswithmembernames class * { native <methods>; }
