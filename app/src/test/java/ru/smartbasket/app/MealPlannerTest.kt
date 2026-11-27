package ru.smartbasket.app
import org.junit.Test
import org.junit.Assert.*
class MealPlannerTest {
    @Test fun breakfast(){val p=buildPlan(parseRequest("Завтраки на 3 дня на 1500 ₽",1,3,300000));assertEquals(3,p.meals.size);assertEquals(150000L,p.budget);assertTrue(p.ingredients().isNotEmpty());assertTrue(p.meals.all{it.kind=="Завтрак"})}
    @Test fun week(){val p=buildPlan(parseRequest("Меню на неделю",1,3,300000));assertEquals(21,p.meals.size)}
    @Test fun explicit(){val p=buildPlan(parseRequest("манная каша и тосты",1,2,300000));assertEquals(2,p.meals.size);assertEquals(40L,p.ingredients().single{it.name=="Масло сливочное"}.amount)}
    @Test fun budgetThousands(){assertEquals(500000L,parseRequest("Меню на неделю за 5 тыс",1,3,0).budget)}
    @Test fun exclusion() {
        for (text in listOf("Меню без молока", "БЕЗ молока, пожалуйста", "Меню: не едим молоко")) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                parseRequest(text, 1, 3, 300000)
            }
            assertTrue(error.message.orEmpty().contains("Меню не составлено"))
        }
        assertTrue(buildPlan(parseRequest("Меню с безалкогольным напитком", 1, 3, 300000)).meals.isNotEmpty())
    }
    @Test fun importPlain(){val p=importIngredients("• Молоко — 500 мл\n2. Яйца 6 шт\nМука 1,5 кг\nСоль по вкусу");assertEquals(3,p.ingredients.size);assertEquals(1500L,p.ingredients[2].amount);assertEquals(listOf("Соль по вкусу"),p.ignored)}
    @Test fun importOld(){assertEquals(Ingredient("Хлеб",120,"г"),importIngredients("Хлеб;120;г").ingredients.single())}
    @Test fun noInventedAmount(){assertTrue(importIngredients("Молоко\nСыр").ingredients.isEmpty())}
}
