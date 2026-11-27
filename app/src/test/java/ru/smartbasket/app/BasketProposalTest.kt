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

    @Test fun previewsSelectedPackagesAndNutritionForRecipeQuantity() {
        val url = "https://5ka.ru/product/moloko/"
        val snapshot = CatalogSnapshot("store", 1000, 1, 0, listOf(
            CatalogEntry(url, "Молоко 930 мл Цена 99 99 ₽")
        ))
        val args = JSONObject("""{"items":[{"name":"Молоко","amount":1000,"unit":"мл"},{"name":"Кумкват","amount":150,"unit":"г"}]}""")
        val items = proposeBasket(args, emptyList(), snapshot, 1001).items
        val milk = proposalItemLabel(items.first()) { selected ->
            assertEquals(url, selected)
            ProductNutrition(Nutrition(50.0, 3.0, 2.0, 5.0), "мл")
        }
        assertTrue(milk.contains("Молоко 930 мл"))
        assertTrue(milk.contains("2 уп. × 99,99 ₽ = 199,98 ₽"))
        assertTrue(milk.contains("500 ккал · Б 30,0 · Ж 20,0 · У 50,0 г"))
        val missing = proposalItemLabel(items.last()) { error("No product selected") }
        assertTrue(missing.contains("цена неизвестна"))
        assertTrue(missing.contains("КБЖУ на 150 г: нет данных"))
        assertTrue(proposalItemLabel(items.first()) { null }.contains("оценка · "))
    }

    @Test fun maximumProposalShowsEveryIngredientIncludingItsNutritionStatus() {
        val rows = org.json.JSONArray()
        for (index in 1..100) {
            rows.put(JSONObject().put("name", "Продукт $index").put("amount", index).put("unit", "г"))
        }
        val proposal = proposeBasket(JSONObject().put("items", rows), emptyList(), null, 1000)
        val preview = proposal.previewRows { null }.split("\n\n")
        assertEquals(100, preview.size)
        for (index in 1..100) {
            val row = preview[index - 1]
            assertTrue(row.startsWith("Продукт $index — $index г\n"))
            assertTrue(row.endsWith("КБЖУ на $index г: нет данных"))
        }
    }
}
