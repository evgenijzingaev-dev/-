package ru.smartbasket.app
import org.junit.Test
import org.junit.Assert.*

class LocalCatalogTest {
    @Test fun priceAndDiscount(){
        assertEquals(19999L,displayCatalogPrice("Молоко 500 мл Старая цена 279 99 ₽Цена 199 99 ₽"))
        assertEquals(10999L,displayCatalogPrice("Молоко 900 мл Цена 109,99 ₽"))
        assertNull(displayCatalogPrice("Молоко нет в наличии"))
        assertNull(displayCatalogPrice("Молоко Цена 99 99 ₽ Цена 89 99 ₽"))
    }
    @Test fun bestWholePack(){
        val a=CatalogEntry("https://5ka.ru/product/milk-a","Молоко 900 мл Цена 99 99 ₽")
        val b=CatalogEntry("https://5ka.ru/product/milk-b","Молоко 1 л Цена 119 99 ₽")
        val bad=CatalogEntry("https://5ka.ru/product/milk-c","от 2 шт Молоко 1 л Цена 50 00 ₽")
        val item=BasketItem("Молоко",1000,"мл")
        assertEquals(b,findCatalogMatch(listOf(a,b,bad),item)?.entry)
    }
    @Test fun noUntrustedOrWrongUnits(){
        val item=BasketItem("Молоко",1000,"мл")
        assertNull(findCatalogMatch(listOf(CatalogEntry("https://evil.example/product/123","Молоко 1 л Цена 99 99 ₽"),CatalogEntry("https://5ka.ru/product/123","Молоко 1 кг Цена 99 99 ₽")),item))
    }
}
