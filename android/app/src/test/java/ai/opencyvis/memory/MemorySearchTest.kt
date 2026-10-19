package ai.opencyvis.memory

import org.junit.Assert.*
import org.junit.Test

class MemorySearchTest {
    @Test fun chineseQueriesOverlapLongerTitlesAndPreserveIdentifiers() {
        val title = MemorySearch.tokens("在原子笔记保存 Mouse27 对比结果").toSet()
        assertTrue(MemorySearch.tokens("原子笔记").all { it in title })
        assertTrue("mouse27" in title)
        assertTrue(MemorySearch.query("原子笔记").contains("OR"))
    }

    @Test fun proceduralMaturityAndKeyOrderDoNotCreateDifferentMethods() {
        assertTrue(MemoryBodies.sameContent(
            """{"goal":"save","nodes":[{"node_id":"save","expected":"visible"}],"maturity":"validated"}""",
            """{"maturity":"observed","nodes":[{"expected":"visible","node_id":"save"}],"goal":"save"}"""))
        assertFalse(MemoryBodies.sameContent("""{"goal":"save"}""", """{"goal":"delete"}"""))
    }
}
