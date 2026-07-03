# MBclaw ProGuard Rules — v4.6
# 防止 R8 过度裁剪导致闪退

# ── Kotlin ──
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes Signature
-keepattributes EnclosingMethod
-dontwarn kotlin.**
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }

# ── Compose ──
-keep class androidx.compose.** { *; }
-keepclassmembers class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ── Gson (JSON serialization) ──
-keepattributes SerializedName
-keep class com.google.gson.** { *; }
-keep class com.mbclaw.root.api.** { *; }
-keep class com.mbclaw.root.model.** { *; }
-keep class com.mbclaw.root.data.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ── OkHttp ──
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# ── All MBclaw classes (app核心) ──
-keep class com.mbclaw.root.** { *; }
-keepclassmembers class com.mbclaw.root.** { *; }

# ── Android ──
-keep class * extends android.app.Activity
-keep class * extends android.app.Application
-keep class * extends android.app.Service
-keep class * extends android.content.BroadcastReceiver
-keep class * extends android.content.ContentProvider
-keep class * extends android.view.View
-keep class * extends android.appwidget.AppWidgetProvider

# ── Accessibility Service ──
-keep class com.mbclaw.root.service.MBclawAccessibilityService { *; }
-keepclassmembers class com.mbclaw.root.service.MBclawAccessibilityService { *; }

# ── JNI ──
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── Enum ──
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── Parcelable ──
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# ── Serializable ──
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ── R 资源类 ──
-keepclassmembers class **.R$* {
    public static <fields>;
}

# ── WebView ──
-keepclassmembers class * extends android.webkit.WebViewClient {
    public void *(android.webkit.WebView, java.lang.String, android.graphics.Bitmap);
    public boolean *(android.webkit.WebView, java.lang.String);
}
-keepclassmembers class * extends android.webkit.WebViewClient {
    public void *(android.webkit.WebView, java.lang.String);
}

# ── Keep source file names and line numbers for crash logs ──
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
