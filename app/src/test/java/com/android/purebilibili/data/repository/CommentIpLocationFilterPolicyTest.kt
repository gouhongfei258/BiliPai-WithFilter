package com.android.purebilibili.data.repository

import com.android.purebilibili.data.model.response.ReplyControl
import com.android.purebilibili.data.model.response.ReplyItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommentIpLocationFilterPolicyTest {

    private fun reply(rpid: Long, location: String?, nested: List<ReplyItem>? = null) = ReplyItem(
        rpid = rpid,
        replyControl = ReplyControl(location = location.orEmpty()),
        replies = nested
    )

    @Test
    fun `whitelist text is split on every supported separator`() {
        assertEquals(
            listOf("北京", "上海", "广东"),
            parseCommentIpWhitelist("北京\n上海，广东")
        )
        assertEquals(
            listOf("北京", "上海", "广东"),
            parseCommentIpWhitelist("北京、上海; 广东")
        )
        assertEquals(
            listOf("北京", "上海"),
            parseCommentIpWhitelist("北京,,，\n\n 上海 \n")
        )
    }

    @Test
    fun `whitelist entries are deduplicated and order preserved`() {
        // 去重保留首次出现的顺序：上海先出现，因此排在前面。
        assertEquals(listOf("上海", "北京"), parseCommentIpWhitelist("上海, 北京, 上海"))
    }

    @Test
    fun `whitelist entries strip the ip prefix and canonicalize aliases`() {
        assertEquals(listOf("北京", "香港"), parseCommentIpWhitelist("IP属地：北京\n中国香港"))
    }

    @Test
    fun `region extraction handles every prefix variant and blank input`() {
        assertEquals("北京", extractCommentRegion("IP属地：北京"))
        assertEquals("北京", extractCommentRegion("IP属地:北京"))
        assertEquals("北京", extractCommentRegion("IP属地北京"))
        assertEquals("北京", extractCommentRegion("IP 属地：北京"))
        assertEquals("北京", extractCommentRegion("  北京  "))
        assertEquals(null, extractCommentRegion(null))
        assertEquals(null, extractCommentRegion(""))
        assertEquals(null, extractCommentRegion("IP属地："))
        assertEquals(null, extractCommentRegion("   "))
    }

    @Test
    fun `china prefix is stripped only for aliases that carry it`() {
        assertEquals("香港", canonicalizeCommentRegion("中国香港"))
        assertEquals("澳门", canonicalizeCommentRegion("中国澳门"))
        assertEquals("台湾", canonicalizeCommentRegion("中国台湾"))
        assertEquals("北京", canonicalizeCommentRegion("北京"))
        assertEquals("日本", canonicalizeCommentRegion("日本"))
        assertEquals("中国", canonicalizeCommentRegion("中国"))
    }

    @Test
    fun `administrative suffixes are stripped from typed region names`() {
        assertEquals("北京", canonicalizeCommentRegion("北京市"))
        assertEquals("广东", canonicalizeCommentRegion("广东省"))
        assertEquals("重庆", canonicalizeCommentRegion("重庆市"))
        assertEquals("台湾", canonicalizeCommentRegion("台湾省"))
        assertEquals("内蒙古", canonicalizeCommentRegion("内蒙古自治区"))
        assertEquals("西藏", canonicalizeCommentRegion("西藏自治区"))
        assertEquals("新疆", canonicalizeCommentRegion("新疆维吾尔自治区"))
        assertEquals("广西", canonicalizeCommentRegion("广西壮族自治区"))
        assertEquals("宁夏", canonicalizeCommentRegion("宁夏回族自治区"))
        assertEquals("香港", canonicalizeCommentRegion("香港特别行政区"))
        assertEquals("澳门", canonicalizeCommentRegion("澳门特别行政区"))
        assertEquals("香港", canonicalizeCommentRegion("中国香港特别行政区"))
    }

    @Test
    fun `a bare suffix is never collapsed to an empty region`() {
        // 「市」/「省」单独出现时不剥离，避免归一化成空串后让该条目静默失效。
        assertEquals("市", canonicalizeCommentRegion("市"))
        assertEquals("省", canonicalizeCommentRegion("省"))
    }

    @Test
    fun `typed suffix entries match the location bilibili returns`() {
        val filter = buildCommentLocationFilter(enabled = true, rawWhitelist = "北京市、广东省")
        assertEquals(setOf("北京", "广东"), filter.regions)
        assertFalse(shouldHideCommentByRegion("IP属地：北京", filter))
        assertFalse(shouldHideCommentByRegion("IP属地：广东", filter))
        assertTrue(shouldHideCommentByRegion("IP属地：上海", filter))
    }

    @Test
    fun `filter stays inactive when disabled or the whitelist is empty`() {
        val disabled = buildCommentLocationFilter(enabled = false, rawWhitelist = "北京")
        val empty = buildCommentLocationFilter(enabled = true, rawWhitelist = "  ")
        assertFalse(shouldHideCommentByRegion("IP属地：上海", disabled))
        assertFalse(shouldHideCommentByRegion("IP属地：上海", empty))
        assertFalse(disabled.isActive)
        assertFalse(empty.isActive)
    }

    @Test
    fun `unknown location is always kept`() {
        val filter = buildCommentLocationFilter(enabled = true, rawWhitelist = "北京")
        assertFalse(shouldHideCommentByRegion(null, filter))
        assertFalse(shouldHideCommentByRegion("", filter))
        assertFalse(shouldHideCommentByRegion("IP属地：", filter))
    }

    @Test
    fun `only regions outside the whitelist are hidden`() {
        val filter = buildCommentLocationFilter(enabled = true, rawWhitelist = "北京, 香港")
        assertFalse(shouldHideCommentByRegion("IP属地：北京", filter))
        assertFalse(shouldHideCommentByRegion("IP属地：中国香港", filter))
        assertTrue(shouldHideCommentByRegion("IP属地：上海", filter))
        assertTrue(shouldHideCommentByRegion("IP属地：日本", filter))
    }

    @Test
    fun `filtering removes hidden roots and counts them`() {
        val filter = buildCommentLocationFilter(enabled = true, rawWhitelist = "北京")
        val result = filterCommentsByRegion(
            items = listOf(
                reply(1, "IP属地：北京"),
                reply(2, "IP属地：上海"),
                reply(3, "IP属地：日本"),
                reply(4, null)
            ),
            filter = filter
        )
        assertEquals(listOf(1L, 4L), result.items.map { it.rpid })
        assertEquals(2, result.hiddenCount)
    }

    @Test
    fun `filtering strips hidden nested sub reply previews`() {
        val filter = buildCommentLocationFilter(enabled = true, rawWhitelist = "北京")
        val result = filterCommentsByRegion(
            items = listOf(
                reply(
                    rpid = 1,
                    location = "IP属地：北京",
                    nested = listOf(reply(11, "IP属地：北京"), reply(12, "IP属地：上海"))
                )
            ),
            filter = filter
        )
        assertEquals(0, result.hiddenCount)
        assertEquals(listOf(11L), result.items.single().replies?.map { it.rpid })
    }

    @Test
    fun `inactive filter returns the original list untouched`() {
        val items = listOf(reply(1, "IP属地：上海"))
        val result = filterCommentsByRegion(items, CommentLocationFilter.INACTIVE)
        assertEquals(items, result.items)
        assertEquals(0, result.hiddenCount)
    }

    @Test
    fun `region toggle adds and removes entries in the raw whitelist text`() {
        assertEquals("北京", toggleCommentWhitelistRegion("", "北京"))
        assertEquals("", toggleCommentWhitelistRegion("北京", "北京"))
        assertEquals("北京\n上海", toggleCommentWhitelistRegion("北京", "上海"))
        assertEquals("", toggleCommentWhitelistRegion("中国香港", "香港"))
    }

    @Test
    fun `presets cover mainland provinces hk macau taiwan and overseas`() {
        val presets = commentIpRegionPresets()
        assertTrue("北京" in presets)
        assertTrue("新疆" in presets)
        assertTrue("中国香港" in presets)
        assertTrue("中国澳门" in presets)
        assertTrue("中国台湾" in presets)
        assertTrue("日本" in presets)
    }
}
