package com.example

import kotlin.test.Test
import kotlin.test.assertEquals

class AppTest {
    @Test
    fun add_works() {
        assertEquals(5, App.add(2, 3))
    }

    @Test
    fun greet_works() {
        assertEquals("Hello, Ada", App.greet("Ada"))
    }
}
