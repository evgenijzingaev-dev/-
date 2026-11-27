
package ru.smartbasket.app
import org.junit.Test
import org.junit.Assert.*
class TransferPlanTest {
    private fun milk()=BasketItem("Молоко",1500,"мл",pack=900,price=9900,storeName="Молоко 900 мл",storeUrl="https://5ka.ru/product/milk--123")
    @Test fun wholePackages(){val r=buildTransfer(listOf(milk()),30000,1000);assertNull(r.error);assertEquals(2L,r.lines.single().quantity);assertEquals(20800L,r.total)}
    @Test fun overBudget(){assertNotNull(buildTransfer(listOf(milk()),10000,1000).error)}
    @Test fun missingPrice(){assertNotNull(buildTransfer(listOf(milk().copy(price=null)),30000,0).error)}
    @Test fun untrusted(){assertFalse(validTransferUrl("https://5ka.ru.evil.example/product/milk"));assertFalse(validTransferUrl("https://5ka.ru@evil.example/product/milk"));assertFalse(validTransferUrl("http://5ka.ru/product/milk"));assertNotNull(buildTransfer(listOf(milk().copy(storeUrl="https://5ka.ru/catalog/")),30000,0).error)}
    @Test fun duplicate(){assertNotNull(buildTransfer(listOf(milk(),milk().copy(name="Другое молоко")),100000,0).error)}
    @Test fun alreadyHome(){assertNotNull(buildTransfer(listOf(milk().copy(atHome=1500)),30000,0).error)}
}
