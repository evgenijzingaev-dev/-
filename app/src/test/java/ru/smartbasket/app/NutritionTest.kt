package ru.smartbasket.app
import org.junit.Test
import org.junit.Assert.*
class NutritionTest{
    @Test fun labelledCard(){
        val n=parseNutrition("Цена 93 99 ₽\nПищевая ценность на 100 г\n20.0\nбелки\n11.0\nжиры\n49.0\nуглеводы\n420.0\nккал\nСостав")!!
        assertEquals(420.0,n.value.kcal,0.001)
        assertEquals(168.0,nutritionLine("батончик",40.0,"г",n).total!!.kcal,0.001)
    }
    @Test fun missingAndInvalid(){
        assertNull(parseNutrition("белки 10 жиры 20 углеводы 30 ккал 200"))
        assertNull(parseNutrition("Пищевая ценность на 100 г\n120 белки\n5 жиры\n4 углеводы\n200 ккал"))
        assertNull(nutritionLine("неизвестный продукт",1.0,"шт",null).total)
    }
    @Test fun portionsAndEstimates(){
        val n=recipeNutrition(Recipe("Тест",2,listOf(Ingredient("Рис",200,"г")),""),emptyList()){null}
        assertEquals(350.0,n.total.kcal,0.001);assertEquals(1,n.estimated)
        assertTrue(nutritionLine("Яйца",2.0,"шт",null).estimated)
    }
    @Test fun volumeBasis(){
        val n=parseNutrition("Пищевая ценность на 100 мл\n3 белки\n2 жиры\n4 углеводы\n46 ккал")!!
        assertEquals(92.0,nutritionLine("напиток",200.0,"мл",n).total!!.kcal,0.001)
    }
}
