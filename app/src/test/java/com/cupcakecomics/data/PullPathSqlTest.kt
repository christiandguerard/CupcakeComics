package com.cupcakecomics.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PullPathSqlTest {
    @Test
    fun escapeLike_escapesMetacharacters() {
        assertEquals("a\\_b", PullPathSql.escapeLike("a_b"))
        assertEquals("a\\%b", PullPathSql.escapeLike("a%b"))
        assertEquals("a\\\\b", PullPathSql.escapeLike("a\\b"))
    }

    @Test
    fun childPrefix_rootAndNested() {
        assertEquals("/%", PullPathSql.childPrefix(""))
        assertEquals("Comics/%", PullPathSql.childPrefix("Comics"))
        assertEquals("Series\\_Foo/%", PullPathSql.childPrefix("Series_Foo"))
    }

    @Test
    fun childPrefix_doesNotLeaveRawWildcards() {
        val prefix = PullPathSql.childPrefix("Issue_01")
        assertFalse(prefix.contains("Issue_01/"))
        assertTrue(prefix.startsWith("Issue\\_01/"))
    }
}
