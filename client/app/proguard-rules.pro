# Release R8 规则。
#
# WebView 页面通过注入名调用 Bridge 的方法，方法名不能被混淆。
-keepclassmembers class com.ywpc.tlkey.ui.CaptchaDialog$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}

# 保留行号便于线上崩溃定位；混淆映射见 app/build/outputs/mapping/release/mapping.txt。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
