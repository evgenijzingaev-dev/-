package ru.smartbasket.app
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class WeekMeal(val day:Int,val slot:Int,val name:String,val portions:Map<String,Int>){
    fun valid()=day in 0..6 && slot in 0..2 && name.isNotBlank() && name.length<=120 && portions.size<=12 && portions.values.all{it in 1..2000}
}
class FamilyWeek(context:Context){
    private val prefs=context.getSharedPreferences("family_week",Context.MODE_PRIVATE)
    fun read():List<WeekMeal> = try{
        val a=JSONArray(prefs.getString("meals","[]"))
        (0 until a.length()).mapNotNull{i->
            val o=a.getJSONObject(i);val p=o.getJSONObject("portions")
            WeekMeal(o.getInt("day"),o.getInt("slot"),o.getString("name"),p.keys().asSequence().associateWith{p.getInt(it)}).takeIf{it.valid()}
        }.distinctBy{it.day to it.slot}
    }catch(_:Exception){emptyList()}
    fun save(meals:List<WeekMeal>){
        require(meals.size<=21 && meals.all{it.valid()} && meals.distinctBy{it.day to it.slot}.size==meals.size)
        prefs.edit().putString("meals",JSONArray(meals.map{JSONObject().put("day",it.day).put("slot",it.slot).put("name",it.name).put("portions",JSONObject(it.portions))}).toString()).apply()
    }
    fun set(meal:WeekMeal){save(read().filterNot{it.day==meal.day && it.slot==meal.slot}+meal)}
    fun remove(day:Int,slot:Int){save(read().filterNot{it.day==day && it.slot==slot})}
}
fun portionNutrition(total:Double,totalGrams:Int,portionGrams:Int):Double{
    require(total.isFinite() && total>=0 && totalGrams>0 && portionGrams in 0..totalGrams)
    return total*portionGrams/totalGrams
}
