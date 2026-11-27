package ru.smartbasket.app

import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

class BasketProposalTest {
    @Test fun preservesHomeAndDoesNotInventPrices(){
        val result=proposeBasket(JSONObject("""{"items":[{"name":"Молоко","amount":1000,"unit":"мл"}]}"""),
            listOf(BasketItem("молоко",500,"мл",atHome=200,pack=500,price=1000)),null,1000)
        assertEquals(800L,result.items.single().need)
        assertNull(result.items.single().price)
        assertNull(result.catalogUpdated)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsDuplicates(){
        proposeBasket(JSONObject("""{"items":[{"name":"Рис","amount":100,"unit":"г"},{"name":"рис","amount":200,"unit":"г"}]}"""),emptyList(),null,1000)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsFraction(){
        proposeBasket(JSONObject("""{"items":[{"name":"Рис","amount":1.5,"unit":"г"}]}"""),emptyList(),null,1000)
    }
}
