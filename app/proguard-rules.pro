# OHMusic ProGuard Rules
-keepclassmembers class com.whale.music.data.model.** { *; }
-keep class com.whale.music.data.db.AppDatabase { *; }

# LX 音源沙箱的 JS 桥：@JavascriptInterface 方法按名字被 WebView 反射调用，
# R8 改名/删除会直接把音源脚本打挂
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
