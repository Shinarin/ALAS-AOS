package com.aliothmoon.maafw.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.privileged.SHIZUKU_M_RELEASES_URL
import com.aliothmoon.maafw.privileged.ShizukuReadiness
import com.aliothmoon.maafw.privileged.ShizukuReadinessStage

/**
 * Shizuku 就绪引导弹窗
 *
 * 纯展示：按 [ShizukuReadiness.stage] 渲染对应的标题/正文/按钮，动作一律上抛
 * [ShizukuReadiness.needsGuidance] 为 false 时不渲染
 */
@Composable
fun ShizukuReadinessDialog(
    readiness: ShizukuReadiness,
    onRecheck: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenApp: () -> Unit,
    onRequestAuth: () -> Unit,
    onUninstall: () -> Unit,
    onDismiss: () -> Unit,
    onSwitchToRoot: () -> Unit,
    isRequesting: Boolean = false,
) {
    if (!readiness.needsGuidance) return

    val switchToRootText = stringResource(R.string.dialog_shizuku_switch_to_root)
        .takeIf { readiness.canSwitchToRoot }
    val skipText = stringResource(R.string.dialog_shizuku_skip_check)

    when (readiness.stage) {
        ShizukuReadinessStage.NotInstalled -> MaaPromptDialog(
            title = stringResource(R.string.dialog_shizuku_not_installed_title),
            message = stringResource(R.string.dialog_shizuku_not_installed_message),
            icon = Icons.Outlined.WarningAmber,
            confirmText = stringResource(R.string.dialog_shizuku_go_download),
            onConfirm = { onOpenUrl(SHIZUKU_M_RELEASES_URL) },
            secondaryText = stringResource(R.string.dialog_shizuku_install_confirm),
            onSecondaryClick = onRecheck,
            neutralText = switchToRootText,
            onNeutralClick = onSwitchToRoot,
            dismissText = skipText,
            onDismissRequest = onDismiss,
        )

        ShizukuReadinessStage.NotRunning -> MaaPromptDialog(
            title = stringResource(R.string.dialog_shizuku_not_running_title),
            message = stringResource(R.string.dialog_shizuku_not_running_message),
            icon = Icons.Outlined.Build,
            confirmText = stringResource(R.string.dialog_shizuku_open_app),
            onConfirm = onOpenApp,
            neutralText = switchToRootText,
            onNeutralClick = onSwitchToRoot,
            dismissText = skipText,
            onDismissRequest = onDismiss,
        )

        // 官方版与 shizuku-m 同包名不同签名，覆盖安装必失败；只能先卸再装
        ShizukuReadinessStage.OfficialConflict -> MaaPromptDialog(
            title = stringResource(R.string.dialog_shizuku_official_conflict_title),
            message = stringResource(R.string.dialog_shizuku_official_conflict_message),
            icon = Icons.Outlined.WarningAmber,
            confirmText = stringResource(R.string.dialog_shizuku_uninstall_confirm),
            onConfirm = onUninstall,
            neutralText = switchToRootText,
            onNeutralClick = onSwitchToRoot,
            dismissText = skipText,
            onDismissRequest = onDismiss,
        )

        // Sui 以 Root 身份跑，兼容性未知；只告知一次，不给出口
        ShizukuReadinessStage.SuiAvailable -> MaaPromptDialog(
            title = stringResource(R.string.dialog_sui_detected_title),
            message = stringResource(R.string.dialog_sui_detected_message),
            icon = Icons.Outlined.Info,
            confirmText = stringResource(R.string.common_got_it),
            onConfirm = onDismiss,
            onDismissRequest = onDismiss,
        )

        ShizukuReadinessStage.NeedAuth -> MaaPromptDialog(
            title = stringResource(R.string.dialog_shizuku_need_auth_title),
            message = stringResource(R.string.dialog_shizuku_need_auth_message),
            icon = Icons.Outlined.Build,
            confirmText = stringResource(
                if (isRequesting) R.string.permission_requesting else R.string.permission_request,
            ),
            onConfirm = onRequestAuth,
            // 授权被永久拒绝后系统不再弹框，留一条去 shizuku-m 手动开授权的路
            secondaryText = stringResource(R.string.dialog_shizuku_open_app),
            onSecondaryClick = onOpenApp,
            neutralText = switchToRootText,
            onNeutralClick = onSwitchToRoot,
            dismissText = skipText,
            onDismissRequest = onDismiss,
        )

        ShizukuReadinessStage.Ready -> Unit
    }
}
