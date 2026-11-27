package ru.smartbasket.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class FamilyMember(val name:String,val age:Int,val height:Int?,val weight:Double?,val activity:String,val goal:String,val tastes:String,val restrictions:String,val id:String=java.util.UUID.randomUUID().toString(),val sex:String="",val activityLevel:String=""){
    fun valid()=name.isNotBlank() && name.length<=60 && age in 0..120 &&
        (height==null || height in 30..250) && (weight==null || weight.isFinite() && weight in 1.0..350.0) &&
        activity.length<=100 && goal.length<=100 && tastes.length<=500 && restrictions.length<=500
    fun maintenance()=FamilyNutrition.maintenance(age,height,weight,sex,activityLevel)
    fun macros()=maintenance()?.let{FamilyNutrition.macros(it)}
    fun json()=JSONObject().put("name",name).put("id",id).put("age",age).put("height_cm",height?:JSONObject.NULL)
        .put("weight_kg",weight?:JSONObject.NULL).put("activity",activity)
        .put("goal",if(age<18)"Возрастное питание без дефицита калорий" else goal)
        .put("sex",sex).put("activity_level",activityLevel).put("maintenance_kcal_estimate",maintenance()?:JSONObject.NULL).put("maintenance_macros_example",macros()?.let{JSONObject().put("protein_g",it.protein).put("fat_g",it.fat).put("carbs_g",it.carbs)}?:JSONObject.NULL).put("preferences",tastes).put("restrictions",restrictions)
}
class FamilyProfiles(context:Context){
    private val prefs=context.getSharedPreferences("family_profiles",Context.MODE_PRIVATE)
    fun read():List<FamilyMember> = try{
        val a=JSONArray(prefs.getString("members","[]"))
        (0 until a.length()).mapNotNull{i->
            val o=a.getJSONObject(i)
            FamilyMember(o.getString("name"),o.getInt("age"),if(o.isNull("height_cm"))null else o.getInt("height_cm"),
                if(o.isNull("weight_kg"))null else o.getDouble("weight_kg"),o.optString("activity"),o.optString("goal"),
                o.optString("preferences"),o.optString("restrictions"),o.optString("id","legacy-"+i),o.optString("sex"),o.optString("activity_level")).takeIf{it.valid()}
        }.take(12)
    }catch(_:Exception){emptyList()}
    fun save(members:List<FamilyMember>){
        require(members.size<=12 && members.all{it.valid()})
        val old=read()
        val edit=prefs.edit().putString("members",JSONArray(members.map{it.json()}).toString())
        old.filter{previous->members.none{it.id==previous.id}}.forEach{edit.remove("food_log_id_"+it.id)}
        edit.apply()
    }
    fun sharedContext():String = if(sharing()) JSONArray(read().mapIndexed{i,m->
        m.json().removeName().put("member_number",i+1).put("recent_food_log",JSONArray(log(i)))
    }).toString() else "[]"
    fun log(index:Int):List<String> = try{
        val a=JSONArray(prefs.getString("food_log_id_"+read()[index].id,"[]"))
        (0 until a.length()).map{a.getString(it)}.takeLast(14)
    }catch(_:Exception){emptyList()}
    fun record(index:Int,food:String,rating:String){
        require(index in read().indices && food.isNotBlank() && food.length<=200 && rating in listOf("Съедено","Понравилось","Не понравилось"))
        val entry=java.time.LocalDate.now().toString()+" · "+rating+" · "+food.trim()
        prefs.edit().putString("food_log_id_"+read()[index].id,JSONArray((log(index)+entry).takeLast(14)).toString()).apply()
    }
    fun clearLog(index:Int){prefs.edit().remove("food_log_id_"+read()[index].id).apply()}
    fun sharing()=prefs.getBoolean("share",false)
    fun setSharing(value:Boolean){prefs.edit().putBoolean("share",value).apply()}
    private fun JSONObject.removeName():JSONObject{remove("name");remove("id");return this}
}
