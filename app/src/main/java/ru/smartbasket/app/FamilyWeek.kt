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

/** Strict tool output: exactly seven days, three meals each, one portion for each member. */
fun parseFamilyWeek(args:JSONObject, memberIds:List<String>):List<WeekMeal>{
    require(memberIds.size in 1..12 && memberIds.distinct().size==memberIds.size)
    val rows=args.getJSONArray("meals")
    require(rows.length()==21)
    val meals=(0 until rows.length()).map{i->
        val row=rows.getJSONObject(i)
        val portions=row.getJSONArray("portions")
        require(portions.length()==memberIds.size)
        val mapped=linkedMapOf<String,Int>()
        for(j in 0 until portions.length()){
            val p=portions.getJSONObject(j)
            val number=p.getInt("member_number")
            val grams=p.getInt("grams")
            require(number in 1..memberIds.size && grams in 1..2000)
            require(mapped.put(memberIds[number-1],grams)==null)
        }
        WeekMeal(row.getInt("day"),row.getInt("slot"),row.getString("name").trim(),mapped).also{require(it.valid())}
    }
    require(meals.map{it.day to it.slot}.distinct().size==21)
    return meals.sortedWith(compareBy<WeekMeal>{it.day}.thenBy{it.slot})
}
