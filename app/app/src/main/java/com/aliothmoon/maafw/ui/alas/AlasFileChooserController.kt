package com.aliothmoon.maafw.ui.alas

internal class AlasFileChooserController<T> {
    private var callback: ((Array<T>?) -> Unit)? = null

    fun show(callback: (Array<T>?) -> Unit, launch: () -> Unit): Boolean {
        cancel()
        this.callback = callback
        return try {
            launch()
            true
        } catch (_: Exception) {
            cancel()
            false
        }
    }

    fun complete(value: Array<T>?) {
        val current = callback
        callback = null
        current?.invoke(value)
    }

    fun cancel() = complete(null)
}
