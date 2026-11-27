package ru.smartbasket.app
import org.junit.Test
import org.junit.Assert.*

class CaloriePlannerTest {
    @Test fun parsesDailyTarget(){
        val r=parseRequest("Меню на 3 дня за 3000 ₽, 1 800 ккал в день",1,2,0)
        assertEquals(1800,r.kcalTarget);assertEquals(300000L,r.budget);assertFalse(r.kcalCeiling)
        assertTrue(parseRequest("Меню до 1800 ккал",1,2,0).kcalCeiling)
    }
    @Test fun scaledIngredientsAndNutrition(){
        val recipe=Recipe("Рис",2,listOf(Ingredient("Рис",200,"г")),"")
        val meal=Meal(1,"Обед",recipe,150)
        val plan=MealPlan(listOf(meal),2,10000)
        assertEquals(300L,plan.ingredients().single().amount)
        assertEquals(525.0,mealNutrition(meal,emptyList()){null}.total.kcal,0.001)
    }
    @Test fun targetNotReachedIsDisclosed(){
        val plan=MealPlan(listOf(Meal(1,"Обед",recipes[3])),1,10000)
        assertTrue(calorieGoalSummary(plan,emptyList(),2000,false){null}.contains("не достигнута"))
    }
    @Test fun defaultPortionsRemainUnchanged(){
        val p=buildPlan(parseRequest("манная каша и тосты",1,2,300000))
        assertEquals(40L,p.ingredients().single{it.name=="Масло сливочное"}.amount)
    }
}
