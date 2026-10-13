package com.fitrace.runner

import org.junit.Assert.assertEquals
import org.junit.Test

class NicknameTest {

    @Test
    fun `same person typing their nickname differently gets the same runner id`() {
        assertEquals("speedy alex", runnerIdOf("Speedy Alex"))
        assertEquals("speedy alex", runnerIdOf("  speedy   ALEX "))
        assertEquals("小明", runnerIdOf(" 小明 "))
    }

    @Test
    fun `blank nickname yields no runner id`() {
        assertEquals("", runnerIdOf("   "))
    }
}
