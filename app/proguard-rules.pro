# When not preverifying in a case-insensitive filing system
-dontusemixedcaseclassnames

-dontwarn org.apache.**
-dontwarn javax.annotation.**

# Preserve stack trace line numbers and exception signatures
-keepattributes LineNumberTable,SourceFile,Signature,InnerClasses,Exceptions,*Annotation*

# ==========================================
# LIME native JNI core and C++ bridges
# ==========================================
-keep class org.bitfennec.lime.core.** { *; }
-keepclassmembers class * {
    native <methods>;
}

# ==========================================
# Room database and persistent entities
# ==========================================
-keep class * extends androidx.room.RoomDatabase
-keep class org.bitfennec.lime.database.entity.** { *; }
-keep @androidx.room.Entity class * { *; }
-keep class * extends androidx.room.RoomOpenHelper
-keep class * extends androidx.room.RoomDatabase$Callback

# ==========================================
# Kotlinx serialization and DTOs
# ==========================================
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}
-keepclassmembers class **$serializer {
    public static final ** INSTANCE;
}
-keep class org.bitfennec.lime.entity.** { *; }
-keep class org.bitfennec.lime.keyboard.model.** { *; }
-keep class org.bitfennec.lime.keyboard.handwriting.** { *; }

# ==========================================
# AI inference engines (ONNX Runtime / Sherpa-ONNX)
# ==========================================
-keep class ai.onnxruntime.** { *; }
-keep class com.microsoft.onnxruntime.** { *; }
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** {
    native <methods>;
}

# ==========================================
# Android Jetpack and service entrypoints
# ==========================================
-keep class * extends android.app.Activity
-keep class * extends android.inputmethodservice.InputMethodService
-keep class * extends androidx.preference.PreferenceFragmentCompat
-keep class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
    public static final ** CREATOR;
}
