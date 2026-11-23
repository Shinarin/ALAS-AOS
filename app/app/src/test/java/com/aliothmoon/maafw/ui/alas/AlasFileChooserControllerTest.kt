package com.aliothmoon.maafw.ui.alas

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlasFileChooserControllerTest {

    @Test
    fun `打开选择器并回传选中文件`() {
        val controller = AlasFileChooserController<String>()
        var launches = 0
        var received: Array<String>? = null

        val shown = controller.show({ received = it }) { launches++ }
        controller.complete(arrayOf("config.json"))
        controller.complete(arrayOf("ignored.json"))

        assertTrue(shown)
        assertEquals(1, launches)
        assertArrayEquals(arrayOf("config.json"), received)
    }

    @Test
    fun `新的选择请求会取消旧回调`() {
        val controller = AlasFileChooserController<String>()
        var firstReceived = arrayOf("unset")
        var secondReceived: Array<String>? = null

        controller.show({ firstReceived = it ?: emptyArray() }) {}
        controller.show({ secondReceived = it }) {}

        assertArrayEquals(emptyArray(), firstReceived)
        controller.complete(arrayOf("new.json"))
        assertArrayEquals(arrayOf("new.json"), secondReceived)
    }

    @Test
    fun `系统选择器无法打开时清理回调`() {
        val controller = AlasFileChooserController<String>()
        var received: Array<String>? = arrayOf("unset")

        val shown = controller.show({ received = it }) { throw IllegalStateException("no activity") }
        controller.complete(arrayOf("ignored.json"))

        assertFalse(shown)
        assertNull(received)
    }

    @Test
    fun `取消选择只通知一次`() {
        val controller = AlasFileChooserController<String>()
        var calls = 0
        var received: Array<String>? = arrayOf("unset")
        controller.show({
            calls++
            received = it
        }) {}

        controller.cancel()
        controller.cancel()
        controller.complete(arrayOf("ignored.json"))

        assertEquals(1, calls)
        assertNull(received)
    }
}
