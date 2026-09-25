package com.aliothmoon.maafw;

import android.view.Surface;
import com.aliothmoon.maafw.ITouchEventCallback;

/**
 * 特权进程的服务面（docs/privileged-runtime.md §6）
 *
 * transaction id 必须显式写死且只增不改：app 升级后旧特权进程可能仍存活，
 * 双方按 transaction code 通信，改动方法顺序会错位
 * destroy() 的 16777114 是 Shizuku user service 的保留 id
 */
interface RemoteService {

    oneway void destroy() = 16777114;

    // ── 生命周期 ──
    void exit() = 1;

    String version() = 2;

    int pid() = 3;

    /** app 侧上报自己的 pid，特权进程据此做 /proc 看门狗，app 消失即自杀 */
    oneway void heartbeat(int appPid) = 4;

    /**
     * piRoot/logDir 是 PI 时代的遗留形参（PI 解包根 / MaaFramework 日志目录），
     * MaaFramework 与 PI 机制均已剔除，实现侧只记日志不使用；保留只为不动 AIDL 事务号
     */
    boolean setup(String piRoot, String logDir, boolean isDebug) = 5;

    // ── 显示 ──

    /** 返回 display id，失败返回 -1 */
    int startVirtualDisplay() = 12;

    void stopVirtualDisplay() = 13;

    oneway void setDisplayPower(boolean on) = 17;

    // ── 预览 ──
    void setMonitorSurface(in Surface surface) = 20;

    oneway void setTouchCallback(ITouchEventCallback callback) = 21;

    // ── 预览上的手动操作 ──
    oneway void touchDown(int x, int y) = 30;

    oneway void touchMove(int x, int y) = 31;

    oneway void touchUp(int x, int y) = 32;

    // ── 代授权限 ──

    /**
     * 用特权身份给 app 自己授权，省掉用户逐个点系统页
     *
     * permissions 与返回值都是 PrivilegedGrant 的位掩码，返回实际授到的那些
     * 用位掩码而不是 Parcelable：Parcelable 的线格式跟着 Kotlin 类布局走，
     * app 升级但旧特权进程仍存活时会解不出来（同本文件开头的 transaction id 约定）
     */
    int grantPermissions(String packageName, int uid, int permissions) = 41;
}
