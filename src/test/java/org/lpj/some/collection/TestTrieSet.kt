package org.lpj.some.collection

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TestTrieSet {
    @Test
    fun testMethod() {
        val trieSet = TrieSet<Any>()
        assertFalse(trieSet.remove("a"))

        assertTrue(trieSet.add("a"))
        assertTrue(trieSet.add("b"))

        assertFalse(trieSet.add("a"))
        assertFalse(trieSet.add("b"))

        assertTrue(trieSet.contains("a"))
        assertTrue(trieSet.contains("b"))

        assertTrue(trieSet.remove("b"))

        assertFalse(trieSet.contains("b"))
    }

    @Test
    fun testCollision() {
        class Interesting {
            override fun hashCode() = 1

            override fun equals(other: Any?) = super.equals(other)
        }

        val trieSet = TrieSet<Any>()
        for (i in 0..1000 - 1) {
            assertTrue(trieSet.add(Interesting()))
        }
        assertEquals(1000, trieSet.size)
    }
}