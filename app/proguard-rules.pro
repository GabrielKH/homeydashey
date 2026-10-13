# kotlinx.serialization: keep generated serializers for the Homey models.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class app.homeydash.homey.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.homeydash.homey.**$$serializer { *; }

# socket.io-client and OkHttp
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keep class io.socket.** { *; }

# TensorFlow Lite (assistant face recognition) is called through JNI.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# Vosk (wake name and voice recognition) is called through JNA, which reflects on these classes.
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
