# Release shrinking rules. Test a minified RELEASE build end to end
# (download, install, play, ads and purchase) before shipping.



# JSON models read/written with Gson (favorites, offline feed cache).
-keep class com.evolutionofgames.zgamespsx.model.** { *; }
-keep class com.evolutionofgames.zgamespsx.feed.** { *; }
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**

# Archive libraries reference optional classes.
-dontwarn com.github.junrar.**
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn net.lingala.zip4j.**


# JNI bridge to the native libretro frontend.
-keep class com.evolutionofgames.zgamespsx.emulator.NativeCore { *; }
-keep class com.evolutionofgames.zgamespsx.emulator.EmulatorActivity { *; }
