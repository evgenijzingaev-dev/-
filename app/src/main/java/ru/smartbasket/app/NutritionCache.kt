package ru.smartbasket.app

import android.content.Context
import org.json.JSONObject

class NutritionCache(context:Context){
    private val prefs=context.applicationContext.getSharedPreferences("nutrition_v1",Context.MODE_PRIVATE)
    private fun id(url:String)=try{
        val u=java.net.URI(url)
        if(u.scheme=="https"&&u.host in listOf("5ka.ru","www.5ka.ru")&&u.port in listOf(-1,443)&&u.userInfo==null&&u.path.startsWith("/product/"))u.path.trimEnd('/') else ""
    }catch(_:Exception){""}
    fun get(url:String):ProductNutrition?=try{
        val k=id(url);if(k.isBlank())null else {
            val o=JSONObject(prefs.getString(k,null)?: "{}")
            val n=Nutrition(o.getDouble("kcal"),o.getDouble("p"),o.getDouble("f"),o.getDouble("c"))
            val basis=o.getString("basis")
            ProductNutrition(n,basis).takeIf{n.valid()&&basis in listOf("г","мл")}
        }
    }catch(_:Exception){null}
    fun put(url:String,n:ProductNutrition){
        val k=id(url);if(k.isBlank()||!n.value.valid())return
        val v=n.value
        prefs.edit().putString(k,JSONObject().put("kcal",v.kcal).put("p",v.protein).put("f",v.fat).put("c",v.carbs).put("basis",n.basis).toString()).apply()
    }
}
