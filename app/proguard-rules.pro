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
