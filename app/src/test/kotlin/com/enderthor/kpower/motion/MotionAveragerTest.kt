package com.enderthor.kpower.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MotionAveragerTest {

    @Test
    fun nothingToTakeWithoutReadings() {
        assertNull(MotionAverager().take())
    }

    @Test
    fun meanOfEachAxisThenStartsAgain() {
        val a = MotionAverager()
        a.add(1f, 0f, 9f)
        a.add(3f, 2f, 10f)
        assertEquals(MotionMean(2.0, 1.0, 9.5, 2), a.take())
        assertNull(a.take())
        a.add(-1f, -1f, -1f)
        assertEquals(MotionMean(-1.0, -1.0, -1.0, 1), a.take())
    }
}
