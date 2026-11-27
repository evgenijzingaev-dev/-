package ru.smartbasket.app
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject

class GeneratedDishTest{
    private val raw="""{"name":"Блюдо","servings":2,"method":"Приготовить","ingredients":[{"name":"Неизвестный продукт","amount":200,"unit":"г","estimated_total":{"kcal":300,"protein":20,"fat":10,"carbs":30}}]}"""
    @Test fun modelEstimatesArePerServing(){
        val d=parseGeneratedDish(JSONObject(raw)){null}
        assertEquals(150.0,d.report.total.kcal,0.001)
        assertEquals(1,d.modelEstimates)
        assertEquals(1,d.report.estimated)
        assertEquals(d,decodeDishes(encodeDishes(listOf(d))).single())
    }
    @Test fun labelDataWins(){
        val d=parseGeneratedDish(JSONObject(raw)){ProductNutrition(Nutrition(100.0,5.0,2.0,15.0),"г")}
        assertEquals(100.0,d.report.total.kcal,0.001)
        assertEquals(0,d.modelEstimates)
        assertEquals(0,d.report.estimated)
    }
    @Test fun impossibleEstimateIsMissing(){
        val d=parseGeneratedDish(JSONObject(raw.replace("\"kcal\":300","\"kcal\":999999"))){null}
        assertEquals(1,d.report.missing)
    }
}
