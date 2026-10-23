package com.moekoe.xposed.kugoulite.compat

import java.lang.reflect.Executable

/**
 * 兼容旧版 Xposed 的 MethodHookParam（与框架无关的纯数据对象）。
 *
 * 语义与原版保持一致：
 *  - 在 beforeHookedMethod 里调用 setResult() 会设置 returnEarly，跳过原方法直接返回；
 *  - 在 afterHookedMethod 里调用 setResult() 会覆盖返回值；
 *  - setResult() 会清掉 throwable，原方法抛出的异常会被吞掉。
 */
class MethodHookParam(
    @JvmField val thisObject: Any?,
    @JvmField val method: Executable?,
    @JvmField var args: Array<Any?>
) {

    private var result: Any? = null
    private var throwable: Throwable? = null
    private var returnEarly = false

    fun getResult(): Any? = result

    fun setResult(value: Any?) {
        setResult(value, true)
    }

    fun setResult(value: Any?, early: Boolean) {
        result = value
        returnEarly = early
        throwable = null
    }

    fun hasThrowable(): Boolean = throwable != null

    fun getThrowable(): Throwable? = throwable

    fun setThrowable(t: Throwable?) {
        throwable = t
        result = null
        returnEarly = false
    }

    /** 框架回填原方法返回值（不是 hook 主动设置的，不影响 returnEarly） */
    fun setFrameworkResult(value: Any?) {
        result = value
        throwable = null
    }

    /** 框架回填原方法抛出的异常 */
    fun setFrameworkThrowable(t: Throwable?) {
        throwable = t
        result = null
    }

    fun isReturnEarly(): Boolean = returnEarly
}
