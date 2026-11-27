package ru.smartbasket.app

import org.junit.Test
import org.junit.Assert.assertEquals

class CatalogStallPolicyTest {
    @Test fun stagedRecovery(){
        assertEquals(0,CatalogStallPolicy.action(4999,0,false))
        assertEquals(1,CatalogStallPolicy.action(5000,0,false))
        assertEquals(0,CatalogStallPolicy.action(9999,1,false))
        assertEquals(2,CatalogStallPolicy.action(10000,1,false))
        assertEquals(0,CatalogStallPolicy.action(19999,2,false))
        assertEquals(3,CatalogStallPolicy.action(20000,2,false))
    }
    @Test fun completedCategoryDoesNotRestart(){
        assertEquals(0,CatalogStallPolicy.action(25000,0,true))
    }
    @Test fun newProgressRestartsTimer(){
        assertEquals(0,CatalogStallPolicy.action(0,0,false))
        assertEquals(1,CatalogStallPolicy.action(5000,0,false))
    }
}
