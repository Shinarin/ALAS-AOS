package com.aliothmoon.maafw.service

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App 前后台态 + 轮询唤醒：空闲退避轮询（HostState/AlasRunController）共用的基础设施
 *
 * 退后台不 poke——拉长到 30s 不差那一拍；回前台 poke，两条轮询立即恢复 4s 并跑一拍
 */
object AppForegroundTracker : DefaultLifecycleObserver {

    /** 初始 true：后台拉起进程（FGS）时误判为前台只是维持现状 4s，保守方向正确 */
    private val _foreground = MutableStateFlow(true)
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    /** extraBufferCapacity=1：连发多次只留一拍，轮询循环收到即重算间隔并立即探测 */
    private val _poke = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val poke: SharedFlow<Unit> = _poke.asSharedFlow()

    fun attach() = ProcessLifecycleOwner.get().lifecycle.addObserver(this)

    override fun onStart(owner: LifecycleOwner) {
        _foreground.value = true
        _poke.tryEmit(Unit)
    }

    override fun onStop(owner: LifecycleOwner) {
        _foreground.value = false
    }

    /** 环境/运行态翻转时由轮询方自调，让循环立刻按新节奏跑 */
    fun poke() {
        _poke.tryEmit(Unit)
    }
}
