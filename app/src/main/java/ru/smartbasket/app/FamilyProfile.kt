package ru.smartbasket.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class FamilyMember(val name:String,val age:Int,val height:Int?,val weight:Double?,val activity:String,val goal:String,val tastes:String,val restrictions:String){
    fun valid()=name.isNotBlank() && name.length<=60 && age in 0..120 &&
        (height==null || height in 30..250) && (weight==null || weight.isFinite() && weight in 1.0..350.0) &&
        activity.length<=100 && goal.length<=100 && tastes.length<=500 && restrictions.length<=500
    fun json()=JSONObject().put("name",name).put("age",age).put("height_cm",height?:JSONObject.NULL)
        .put("weight_kg",weight?:JSONObject.NULL).put("activity",activity)
        .put("goal",if(age<18)"Возрастное питание без дефицита калорий" else goal)
        .put("preferences",tastes).put("restrictions",restrictions)
}
class FamilyProfiles(context:Context){
    private val prefs=context.getSharedPreferences("family_profiles",Context.MODE_PRIVATE)
    fun read():List<FamilyMember> = try{
        val a=JSONArray(prefs.getString("members","[]"))
        (0 until a.length()).mapNotNull{i->
            val o=a.getJSONObject(i)
            FamilyMember(o.getString("name"),o.getInt("age"),if(o.isNull("height_cm"))null else o.getInt("height_cm"),
                if(o.isNull("weight_kg"))null else o.getDouble("weight_kg"),o.optString("activity"),o.optString("goal"),
                o.optString("preferences"),o.optString("restrictions")).takeIf{it.valid()}
        }.take(12)
    }catch(_:Exception){emptyList()}
    fun save(members:List<FamilyMember>){
        require(members.size<=12 && members.all{it.valid()})
        prefs.edit().putString("members",JSONArray(members.map{it.json()}).toString()).apply()
    }
    fun sharedContext():String = if(prefs.getBoolean("share",false)) JSONArray(read().map{it.json().removeName()}).toString() else "[]"
    fun sharing()=prefs.getBoolean("share",false)
    fun setSharing(value:Boolean){prefs.edit().putBoolean("share",value).apply()}
    private fun JSONObject.removeName():JSONObject{remove("name");return this}
}
