package ru.smartbasket.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FamilyWeekTest {
    private val members=listOf("adult","child")
    private fun plan():JSONObject=JSONObject().put("meals",JSONArray((0..20).map{i->
        JSONObject().put("day",i/3).put("slot",i%3).put("name","Блюдо "+i)
            .put("portions",JSONArray().put(JSONObject().put("member_number",1).put("grams",250))
                .put(JSONObject().put("member_number",2).put("grams",100)))
    }))
    @Test fun completeWeekUsesLocalIds(){
        val result=parseFamilyWeek(plan(),members)
        assertEquals(21,result.size)
        assertEquals(0,result.first().day)
        assertEquals(6,result.last().day)
        assertEquals(mapOf("adult" to 250,"child" to 100),result.first().portions)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsDuplicateMeal(){
        val input=plan()
        input.getJSONArray("meals").getJSONObject(1).put("slot",0)
        parseFamilyWeek(input,members)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsMissingMeal(){
        val input=plan()
        input.getJSONArray("meals").remove(20)
        parseFamilyWeek(input,members)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsDuplicateMember(){
        val input=plan()
        input.getJSONArray("meals").getJSONObject(0).getJSONArray("portions").getJSONObject(1).put("member_number",1)
        parseFamilyWeek(input,members)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsInvalidPortion(){
        val input=plan()
        input.getJSONArray("meals").getJSONObject(0).getJSONArray("portions").getJSONObject(1).put("grams",0)
        parseFamilyWeek(input,members)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsNoConsent(){
        parseFamilyWeek(plan(),emptyList())
    }
}
