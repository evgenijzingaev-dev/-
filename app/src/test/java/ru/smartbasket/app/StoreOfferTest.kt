package ru.smartbasket.app
import org.junit.Test
import org.junit.Assert.*
class StoreOfferTest {
    @Test fun normal(){val x=parseStoreOffer("Молоко 930 мл Цена 99 99 ₽","мл")!!;assertEquals(930L,x.pack);assertEquals(9999L,x.price);assertEquals(19998L,x.total(1000))}
    @Test fun sale(){val x=parseStoreOffer("Мука 1 кг Старая цена 129 99 ₽Цена 69 99 ₽","г")!!;assertEquals(1000L,x.pack);assertEquals(6999L,x.price)}
    @Test fun conditional(){assertTrue(parseStoreOffer("от 2 шт Молоко 900 мл Цена 74 99 ₽","мл")!!.conditional)}
    @Test fun perWeight(){assertNull(parseStoreOffer("Мясо 1 кг Цена 299 99 ₽ за 1 кг","г"))}
    @Test fun mismatch(){assertNull(parseStoreOffer("Яйца 10 шт Цена 99 99 ₽","г"))}
    @Test fun name(){assertTrue(offerMatches("Помидоры","Томаты красные 450 г"));assertFalse(offerMatches("Молоко","Сливки 200 мл"));assertFalse(offerMatches("Масло сливочное","Масло растительное 900 мл"))}
    @Test fun ambiguous(){assertNull(parseStoreOffer("Мука 1 кг Цена 99 99 ₽ Цена 89 99 ₽","г"))}
}
