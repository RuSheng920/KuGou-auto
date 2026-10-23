package com.moekoe.xposed.kugoulite.compat

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 旧版 de.robv.android.xposed.XposedHelpers 的最小兼容实现（Kotlin 版）。
 * libxposed API 102 只提供 hook / invoker 原语，不提供这些反射糖，因此这里自己补上。
 */
object XposedHelpers {

    // ------------------------------------------------------------------ 类

    @Throws(ClassNotFoundException::class)
    fun findClass(className: String, classLoader: ClassLoader?): Class<*> {
        return if (classLoader != null) {
            Class.forName(className, false, classLoader)
        } else {
            Class.forName(className)
        }
    }

    fun findClassIfExists(className: String, classLoader: ClassLoader?): Class<*>? {
        return try {
            findClass(className, classLoader)
        } catch (_: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------ 方法查找

    private fun matches(declared: Array<out Class<*>>, wanted: Array<out Class<*>>): Boolean {
        if (declared.size != wanted.size) return false
        for (i in declared.indices) {
            val d = declared[i]
            val w = wanted[i]
            if (d == w) continue
            if (d.isPrimitive || w.isPrimitive) return false
            if (!d.isAssignableFrom(w)) return false
        }
        return true
    }

    /** 沿继承链查找第一个可用的实现（跳过抽象方法） */
    fun findMethodExact(clazz: Class<*>, methodName: String, vararg parameterTypes: Class<*>): Method {
        var fallback: Method? = null
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            for (method in c.declaredMethods) {
                if (method.name != methodName
                    || !matches(method.parameterTypes, parameterTypes)
                ) {
                    continue
                }
                if (!Modifier.isAbstract(method.modifiers)) {
                    method.isAccessible = true
                    return method
                }
                if (fallback == null) {
                    fallback = method
                }
            }
            c = c.superclass
        }
        fallback?.let {
            it.isAccessible = true
            return it
        }
        throw NoSuchMethodError(
            clazz.name + "." + methodName + "(" + parameterTypes.contentToString() + ")"
        )
    }

    /** 按运行时参数类型找最匹配的方法（供 callMethod 使用） */
    private fun findMethodBestMatch(clazz: Class<*>, methodName: String, vararg args: Any?): Method {
        var compat: Method? = null
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            for (method in c.declaredMethods) {
                if (method.name != methodName || method.parameterTypes.size != args.size) {
                    continue
                }
                val ps = method.parameterTypes
                var ok = true
                for (i in ps.indices) {
                    if (args[i] == null) {
                        if (ps[i].isPrimitive) {
                            ok = false
                            break
                        }
                        continue
                    }
                    if (ps[i].isPrimitive) {
                        if (!box(ps[i]).isInstance(args[i])) {
                            ok = false
                            break
                        }
                    } else if (!ps[i].isInstance(args[i])) {
                        ok = false
                        break
                    }
                }
                if (!ok) continue

                if (compat == null) {
                    compat = method
                }
                var exact = true
                for (i in ps.indices) {
                    val t = if (args[i] == null) null else args[i]!!.javaClass
                    val p = if (ps[i].isPrimitive) box(ps[i]) else ps[i]
                    if (t == null || p != t) {
                        exact = false
                        break
                    }
                }
                if (exact) {
                    method.isAccessible = true
                    return method
                }
            }
            c = c.superclass
        }
        compat?.let {
            it.isAccessible = true
            return it
        }
        throw NoSuchMethodError(clazz.name + "." + methodName)
    }

    private fun box(primitive: Class<*>): Class<*> = when (primitive) {
        Int::class.javaPrimitiveType -> Int::class.java
        Boolean::class.javaPrimitiveType -> Boolean::class.java
        Long::class.javaPrimitiveType -> Long::class.java
        Double::class.javaPrimitiveType -> Double::class.java
        Float::class.javaPrimitiveType -> Float::class.java
        Char::class.javaPrimitiveType -> Char::class.java
        else -> primitive
    }

    // ---------------------------------------------------------------- 调用

    fun callMethod(obj: Any, methodName: String, vararg args: Any?): Any? {
        return try {
            val method = findMethodBestMatch(obj.javaClass, methodName, *args)
            method.invoke(obj, *args)
        } catch (e: RuntimeException) {
            throw e
        } catch (t: Throwable) {
            throw RuntimeException(t)
        }
    }

    fun callStaticMethod(clazz: Class<*>, methodName: String, vararg args: Any?): Any? {
        return try {
            val method = findMethodBestMatch(clazz, methodName, *args)
            method.invoke(null, *args)
        } catch (e: RuntimeException) {
            throw e
        } catch (t: Throwable) {
            throw RuntimeException(t)
        }
    }

    fun newInstance(clazz: Class<*>, vararg args: Any?): Any {
        for (ctor in clazz.declaredConstructors) {
            if (ctor.parameterTypes.size != args.size) continue
            ctor.isAccessible = true
            return ctor.newInstance(*args)
        }
        throw NoSuchMethodError("ctor " + clazz.name)
    }

    // ------------------------------------------------------------------ 字段

    fun getObjectField(obj: Any, fieldName: String): Any? {
        return try {
            findField(obj.javaClass, fieldName).get(obj)
        } catch (e: RuntimeException) {
            throw e
        } catch (t: Throwable) {
            throw RuntimeException(t)
        }
    }

    fun setObjectField(obj: Any, fieldName: String, value: Any?) {
        try {
            findField(obj.javaClass, fieldName).set(obj, value)
        } catch (e: RuntimeException) {
            throw e
        } catch (t: Throwable) {
            throw RuntimeException(t)
        }
    }

    fun getBooleanField(obj: Any, fieldName: String): Boolean {
        return try {
            findField(obj.javaClass, fieldName).getBoolean(obj)
        } catch (e: RuntimeException) {
            throw e
        } catch (t: Throwable) {
            throw RuntimeException(t)
        }
    }

    fun setBooleanField(obj: Any, fieldName: String, value: Boolean) {
        try {
            findField(obj.javaClass, fieldName).setBoolean(obj, value)
        } catch (e: RuntimeException) {
            throw e
        } catch (t: Throwable) {
            throw RuntimeException(t)
        }
    }

    fun findField(clazz: Class<*>, fieldName: String): Field {
        var c: Class<*>? = clazz
        while (c != null) {
            try {
                val field = c.getDeclaredField(fieldName)
                field.isAccessible = true
                return field
            } catch (_: NoSuchFieldException) {
            }
            c = c.superclass
        }
        throw NoSuchFieldError(clazz.name + "." + fieldName)
    }

    // --------------------------------------------------------- findAndHook

    fun findAndHookMethod(
        clazz: Class<*>,
        methodName: String,
        vararg parameterTypesAndCallback: Any?
    ) {
        val parts = parameterTypesAndCallback
        if (parts.isEmpty() || parts[parts.size - 1] !is XC_MethodHook) {
            throw IllegalArgumentException("最后一个参数必须是 XC_MethodHook")
        }
        val callback = parts[parts.size - 1] as XC_MethodHook
        @Suppress("UNCHECKED_CAST")
        val parameterTypes = java.util.Arrays.copyOfRange(parts, 0, parts.size - 1) as Array<Class<*>>
        XposedBridge.hookMethod(
            findMethodExact(clazz, methodName, *parameterTypes),
            callback
        )
    }

    fun findAndHookMethod(
        className: String,
        classLoader: ClassLoader?,
        methodName: String,
        vararg parameterTypesAndCallback: Any?
    ) {
        try {
            findAndHookMethod(findClass(className, classLoader), methodName, *parameterTypesAndCallback)
        } catch (e: ClassNotFoundException) {
            throw NoClassDefFoundError(e.message)
        }
    }

    fun findAndHookConstructor(clazz: Class<*>, vararg parameterTypesAndCallback: Any?) {
        val parts = parameterTypesAndCallback
        if (parts.isEmpty() || parts[parts.size - 1] !is XC_MethodHook) {
            throw IllegalArgumentException("最后一个参数必须是 XC_MethodHook")
        }
        val callback = parts[parts.size - 1] as XC_MethodHook
        @Suppress("UNCHECKED_CAST")
        val parameterTypes = java.util.Arrays.copyOfRange(parts, 0, parts.size - 1) as Array<Class<*>>
        try {
            val ctor = clazz.getDeclaredConstructor(*parameterTypes)
            ctor.isAccessible = true
            XposedBridge.hookMethod(ctor, callback)
        } catch (e: NoSuchMethodException) {
            throw NoSuchMethodError(e.message)
        }
    }
}
