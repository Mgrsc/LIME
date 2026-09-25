package org.bitfennec.lime.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UserDictTsvTest {

    @Test
    fun testParseStandardUserDictLine() {
        val line = "留青\tliu qing\t32"
        val entry = UserDataManager.parseUserDictLine(line)
        assertNotNull(entry)
        assertEquals("留青", entry!!.phrase)
        assertEquals("liu qing", entry.code)
        assertEquals(32, entry.commits)
    }

    @Test
    fun testParseLineWithExtraColumnsTolerated() {
        val line = "人工智能\tren gong zhi neng\t105\textra\tcols"
        val entry = UserDataManager.parseUserDictLine(line)
        assertNotNull(entry)
        assertEquals("人工智能", entry!!.phrase)
        assertEquals("ren gong zhi neng", entry.code)
        assertEquals(105, entry.commits)
    }

    @Test
    fun testParseDeletedEntryIgnored() {
        val tombstoneLine = "旧词\tjiu ci\t-1"
        val entry = UserDataManager.parseUserDictLine(tombstoneLine)
        assertNull("Negative commits indicate deleted entry and must be filtered out", entry)
    }

    @Test
    fun testParseZeroCommitsIgnored() {
        val zeroCommitLine = "你好\tni hao\t0"
        val entry = UserDataManager.parseUserDictLine(zeroCommitLine)
        assertNull("Zero commits indicate internal sentence elements and must be filtered out", entry)
    }

    @Test
    fun testParseCommentsAndBlankLinesIgnored() {
        assertNull(UserDataManager.parseUserDictLine(""))
        assertNull(UserDataManager.parseUserDictLine("   "))
        assertNull(UserDataManager.parseUserDictLine("# Rime user dict export"))
        assertNull(UserDataManager.parseUserDictLine("#@/db_name\tpinyin"))
    }

    @Test
    fun testParseMalformedLinesHandled() {
        assertNull(UserDataManager.parseUserDictLine("only_one_col"))
        assertNull(UserDataManager.parseUserDictLine("\tcode_only"))
        assertNull(UserDataManager.parseUserDictLine("phrase_only\t"))
    }

    @Test
    fun testTombstoneStringFormat() {
        val entry = UserDictEntry(phrase = "测试词", code = "ce shi ci", commits = 5)
        val tombstone = "${entry.phrase}\t${entry.code}\t-1\n"
        assertEquals("测试词\tce shi ci\t-1\n", tombstone)
    }

    @Test
    fun testListSortingByCommitsDescending() {
        val entries = listOf(
            UserDictEntry("低频", "di pin", 2),
            UserDictEntry("高频", "gao pin", 99),
            UserDictEntry("中频", "zhong pin", 15)
        )
        val sorted = entries.sortedByDescending { it.commits }
        assertEquals("高频", sorted[0].phrase)
        assertEquals(99, sorted[0].commits)
        assertEquals("中频", sorted[1].phrase)
        assertEquals(15, sorted[1].commits)
        assertEquals("低频", sorted[2].phrase)
        assertEquals(2, sorted[2].commits)
    }

    @Test
    fun testCustomFlagOnUserDictEntry() {
        val defaultEntry = UserDictEntry("你好", "ni hao", 1)
        assertEquals(false, defaultEntry.isCustom)
        val customEntry = defaultEntry.copy(isCustom = true)
        assertEquals(true, customEntry.isCustom)
    }
}
