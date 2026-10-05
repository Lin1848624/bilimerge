# ffmpeg-kit 通过 JNI 调用，类名与方法签名不可被混淆
-keep class com.arthenica.ffmpegkit.** { *; }
-keepclassmembers class com.arthenica.ffmpegkit.** { *; }
-dontwarn com.arthenica.ffmpegkit.**

# 保留 native 方法
-keepclasseswithmembernames class * {
    native <methods>;
}

# 数据模型用于 JSON 反射解析（org.json 手写解析，此处仅为保险）
-keep class com.dsh.bilimerge.core.model.** { *; }
