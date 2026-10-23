# ============ 本模块全部类原样保留 ============
# Xposed 入口类由框架按类名反射实例化（MainHook/LegacyEntry 来自 META-INF，
# 不在 AndroidManifest 里，R8 的 manifest keep 规则覆盖不到），必须显式保留；
# 其余类同样整体保留，避免 R8 类合并/成员内联在注入酷狗进程后产生
# 无法在真机验证的行为差异（模块以正确性优先，体积由 stdlib 裁剪保障）。
-keep class com.moekoe.xposed.kugoulite.** { *; }

# ============ Kotlin 元数据 ============
-keepattributes RuntimeVisibleAnnotations
-keep class kotlin.Metadata { *; }

# ============ 反射调用的目标（酷狗自身的类，按名查找，不涉及本模块类）============
-dontwarn okhttp3.**
-dontwarn org.json.**
