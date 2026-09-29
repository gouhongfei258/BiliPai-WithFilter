package com.android.purebilibili.data.repository

import com.android.purebilibili.data.model.response.ReplyItem

/**
 * 评论 IP 属地白名单过滤。
 *
 * 语义：仅当 [enabled] 为真且 [regions] 非空时生效；属地未知（缺失或解析不出地区）的评论一律保留。
 * [regions] 中的地区必须已经过 [canonicalizeCommentRegion] 规范化，用 [buildCommentLocationFilter] 构造可保证这一点。
 */
internal data class CommentLocationFilter(
    val enabled: Boolean,
    val regions: Set<String>
) {
    val isActive: Boolean get() = enabled && regions.isNotEmpty()

    companion object {
        val INACTIVE = CommentLocationFilter(enabled = false, regions = emptySet())
    }
}

internal data class CommentRegionFilterResult(
    val items: List<ReplyItem>,
    /** 被移除的根评论数量；嵌套楼中楼不计入，避免与根评论重复计数。 */
    val hiddenCount: Int
)

private val COMMENT_REGION_PREFIXES = listOf(
    "IP属地：", "IP属地:", "IP属地", "IP 属地：", "IP 属地:", "IP 属地"
)

private val COMMENT_WHITELIST_SEPARATORS = charArrayOf(
    '\n', '\r', ',', '，', '、', ';', '；', '|', ' ', '\t'
)

/** 从原始属地字符串中提取地区，形如 `IP属地：北京` -> `北京`；解析不出时返回 null。 */
internal fun extractCommentRegion(location: String?): String? {
    if (location.isNullOrBlank()) return null
    var region = location.trim()
    val prefix = COMMENT_REGION_PREFIXES.firstOrNull { region.startsWith(it) }
    if (prefix != null) {
        region = region.removePrefix(prefix).trim()
    }
    return region.takeIf { it.isNotEmpty() }
}

/**
 * 地区别名归一化：B 站对港澳台返回「中国香港 / 中国澳门 / 中国台湾」，
 * 允许用户只写「香港 / 澳门 / 台湾」，境内省份与海外地区名原样返回。
 */
internal fun canonicalizeCommentRegion(region: String): String {
    val trimmed = region.trim()
    if (trimmed.isEmpty()) return trimmed
    return trimmed.removePrefix("中国").trim().ifEmpty { trimmed }
}

/** 单条白名单输入归一化：先剥属地前缀，再做别名归一化；解析不出时返回 null。 */
internal fun normalizeCommentRegionEntry(entry: String): String? {
    val region = extractCommentRegion(entry) ?: return null
    return canonicalizeCommentRegion(region).takeIf { it.isNotEmpty() }
}

/** 解析白名单文本（换行 / 逗号 / 顿号 / 分号 / 空格分隔），去空、去重并保持顺序。 */
internal fun parseCommentIpWhitelist(raw: String): List<String> =
    raw.split(*COMMENT_WHITELIST_SEPARATORS)
        .mapNotNull(::normalizeCommentRegionEntry)
        .distinct()

internal fun buildCommentLocationFilter(enabled: Boolean, rawWhitelist: String): CommentLocationFilter =
    CommentLocationFilter(
        enabled = enabled,
        regions = parseCommentIpWhitelist(rawWhitelist).toSet()
    )

internal fun shouldHideCommentByRegion(location: String?, filter: CommentLocationFilter): Boolean {
    if (!filter.isActive) return false
    val region = extractCommentRegion(location) ?: return false
    return canonicalizeCommentRegion(region) !in filter.regions
}

/**
 * 过滤根评论列表，并同步剥离每条根评论内嵌的楼中楼预览，
 * 否则被过滤用户仍会从折叠预览中露出。
 */
internal fun filterCommentsByRegion(
    items: List<ReplyItem>,
    filter: CommentLocationFilter
): CommentRegionFilterResult {
    if (!filter.isActive || items.isEmpty()) {
        return CommentRegionFilterResult(items = items, hiddenCount = 0)
    }
    var hiddenCount = 0
    val visible = ArrayList<ReplyItem>(items.size)
    items.forEach { item ->
        if (shouldHideCommentByRegion(item.replyControl?.location, filter)) {
            hiddenCount++
            return@forEach
        }
        val nested = item.replies
        if (nested.isNullOrEmpty()) {
            visible += item
            return@forEach
        }
        val visibleNested = nested.filterNot { shouldHideCommentByRegion(it.replyControl?.location, filter) }
        visible += if (visibleNested.size == nested.size) item else item.copy(replies = visibleNested)
    }
    return CommentRegionFilterResult(items = visible, hiddenCount = hiddenCount)
}

/** 快捷勾选：在地区上切换选中状态，并以换行形式写回白名单文本。 */
internal fun toggleCommentWhitelistRegion(raw: String, region: String): String {
    val canonical = normalizeCommentRegionEntry(region) ?: return raw
    val current = parseCommentIpWhitelist(raw)
    val next = if (canonical in current) current - canonical else current + canonical
    return next.joinToString(separator = "\n")
}

/** 设置页快捷勾选常用地区：省级行政区 + 港澳台 + 常见海外。 */
internal fun commentIpRegionPresets(): List<String> = listOf(
    "北京", "上海", "天津", "重庆",
    "河北", "山西", "辽宁", "吉林", "黑龙江",
    "江苏", "浙江", "安徽", "福建", "江西", "山东",
    "河南", "湖北", "湖南", "广东", "海南",
    "四川", "贵州", "云南", "陕西", "甘肃", "青海",
    "内蒙古", "广西", "西藏", "宁夏", "新疆",
    "中国台湾", "中国香港", "中国澳门",
    "日本", "美国", "韩国", "新加坡", "英国", "德国", "法国", "加拿大", "澳大利亚"
)
