package ru.smartbasket.app

import org.junit.Assert.*
import org.junit.Test

class PriceHistoryTest {
    @Test fun insufficientHistoryDoesNotProveDiscount(){
        assertNull(assessDiscount(8000,12000,listOf(1L to 10000L,2L to 10000L)))
        assertNull(assessDiscount(8000,12000,listOf(1L to 10000L,2L to 10000L,3L to 10000L)))
    }
    @Test fun inflatedReferenceWithoutRealSaving(){
        val result=assessDiscount(10000,15000,listOf(1L to 10000L,4L to 10000L,9L to 10000L))!!
        assertEquals(0L,result.saving)
        assertTrue(result.oldAboveTypical)
    }
    @Test fun genuineReductionRelativeToHistory(){
        val result=assessDiscount(7500,10000,listOf(1L to 10000L,4L to 9500L,9L to 10000L))!!
        assertEquals(2500L,result.saving)
        assertEquals(9500L,result.low)
        assertFalse(result.oldAboveTypical)
    }
    @Test fun repeatedSameDayDoesNotCountAsHistory(){
        assertNull(assessDiscount(8000,12000,listOf(1L to 10000L,1L to 10000L,9L to 10000L)))
    }
}
