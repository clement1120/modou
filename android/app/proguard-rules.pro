# 轻阅是零反射工程：只保留「按名字被外部调用」的三类东西，其余交给 R8 裁。

# JS 桥：方法名是前端字符串里写死的，混淆了就调不到
-keepattributes JavascriptInterface,*Annotation*
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class cn.modou.app.ModouBridge { *; }

# 内嵌 NanoHTTPD：以源码形式编进来，运行时按接口/反射取 MIME 与处理器
-keep class org.nanohttpd.** { *; }
-dontwarn org.nanohttpd.**

# 编码探测：探测器按字符集类名映射，裁掉会让「无声明页面」少几种编码
-keep class com.googlecode.juniversalchardet.** { *; }
-dontwarn com.googlecode.juniversalchardet.**

# 清单里的组件（Activity/Service/JobService）由 AGP 自动 keep，无需手写
