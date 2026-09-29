package com.android.purebilibili.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.purebilibili.core.store.SettingsManager
import com.android.purebilibili.core.ui.components.AppFilterChip
import com.android.purebilibili.core.ui.components.AppOutlinedTextField
import com.android.purebilibili.core.ui.components.AppPreferenceSectionTitle
import com.android.purebilibili.core.ui.components.AppSwitchPreference
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.resolveBottomSafeAreaPadding
import com.android.purebilibili.data.repository.buildCommentLocationFilter
import com.android.purebilibili.data.repository.commentIpRegionPresets
import com.android.purebilibili.data.repository.normalizeCommentRegionEntry
import com.android.purebilibili.data.repository.toggleCommentWhitelistRegion
import com.android.purebilibili.feature.settings.ui.SettingsCardGroup
import com.android.purebilibili.feature.settings.ui.SettingsPageScaffold
import com.android.purebilibili.feature.settings.ui.settingsScrollContentPadding
import kotlinx.coroutines.launch

/**
 * 评论 IP 属地白名单设置页。
 *
 * 只负责编辑「启用开关 + 地区文本」，解析与过滤语义全部由
 * `data.repository.CommentIpLocationFilterPolicy` 统一裁决，避免设置页和评论区各自实现一套。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CommentIpWhitelistScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val whitelistEnabled by remember(context) {
        SettingsManager.getCommentIpWhitelistEnabled(context)
    }.collectAsStateWithLifecycle(initialValue = false)
    val storedWhitelist by remember(context) {
        SettingsManager.getCommentIpWhitelistRaw(context)
    }.collectAsStateWithLifecycle(initialValue = "")

    // 输入先落到本地草稿，按键即时回显，不必等待 DataStore 回读；外部改动会重新同步。
    var draft by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(storedWhitelist) {
        if (draft != null && draft == storedWhitelist) draft = null
    }
    val effectiveText = draft ?: storedWhitelist

    val filter = remember(whitelistEnabled, effectiveText) {
        buildCommentLocationFilter(whitelistEnabled, effectiveText)
    }
    val presets = remember { commentIpRegionPresets() }
    val entryVisual = rememberSettingsEntryVisual(SettingsSearchTarget.COMMENT_IP_WHITELIST)
    val bottomPadding = resolveBottomSafeAreaPadding(
        navigationBarsBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        extraBottomPadding = 24.dp,
    )

    SettingsPageScaffold(
        title = settingsDestinationCopy(SettingsSearchTarget.COMMENT_IP_WHITELIST).title,
        onBack = onBack,
        backContentDescription = "返回",
        bottomContentPadding = bottomPadding,
        scrollHost = SettingsPageScrollHost.External,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = settingsScrollContentPadding(
                extraHorizontal = 16.dp,
                extraVertical = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SettingsCardGroup {
                    AppSwitchPreference(
                        icon = entryVisual.icon,
                        title = "启用 IP 属地白名单",
                        subtitle = "开启后仅显示白名单内地区的评论；属地未知的评论会保留",
                        checked = whitelistEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                SettingsManager.setCommentIpWhitelistEnabled(context, enabled)
                            }
                        },
                        iconTint = entryVisual.iconTint,
                    )
                }
            }

            item {
                Column {
                    AppPreferenceSectionTitle("白名单地区")
                    Spacer(modifier = Modifier.height(8.dp))
                    SettingsCardGroup {
                        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                            AppOutlinedTextField(
                                value = effectiveText,
                                onValueChange = { text ->
                                    draft = text
                                    scope.launch {
                                        SettingsManager.setCommentIpWhitelistRaw(context, text)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                labelText = "每行一个地区，也可用逗号或空格分隔",
                                placeholderText = "例如：北京",
                                minLines = 4,
                                maxLines = 8,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            AppText(
                                text = resolveCommentWhitelistSummary(
                                    enabled = whitelistEnabled,
                                    regionCount = filter.regions.size,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (presets.isNotEmpty()) {
                item {
                    Column {
                        AppPreferenceSectionTitle("常用地区")
                        Spacer(modifier = Modifier.height(8.dp))
                        SettingsCardGroup {
                            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                                AppText(
                                    text = "点击快速添加或移除；港澳台会按「香港 / 澳门 / 台湾」存储",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    presets.forEach { preset ->
                                        val canonical = normalizeCommentRegionEntry(preset)
                                        AppFilterChip(
                                            selected = canonical != null && canonical in filter.regions,
                                            onClick = {
                                                val next = toggleCommentWhitelistRegion(effectiveText, preset)
                                                draft = next
                                                scope.launch {
                                                    SettingsManager.setCommentIpWhitelistRaw(context, next)
                                                }
                                            },
                                            label = {
                                                AppText(
                                                    text = preset,
                                                    style = MaterialTheme.typography.labelLarge,
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun resolveCommentWhitelistSummary(
    enabled: Boolean,
    regionCount: Int,
): String = when {
    regionCount == 0 -> "白名单为空，当前不会过滤任何评论"
    !enabled -> "已填写 $regionCount 个地区，但白名单未启用，当前不会过滤评论"
    else -> "已生效 $regionCount 个地区，仅显示这些地区的评论"
}
