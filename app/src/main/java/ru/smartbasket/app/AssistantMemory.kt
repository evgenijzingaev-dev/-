package ru.smartbasket.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Small, device-local context; only the recent dialogue is sent to the model. */
class AssistantMemory(context:Context){
    private val prefs=context.applicationContext.getSharedPreferences("assistant_memory_v1",Context.MODE_PRIVATE)
    fun preferences():String=prefs.getString("preferences","").orEmpty().take(4000)
    fun savePreferences(value:String){require(value.length<=4000);prefs.edit().putString("preferences",value.trim()).apply()}
    fun history():List<AssistantMessage> = decodeHistory(prefs.getString("history","[]").orEmpty())
    fun saveHistory(value:List<AssistantMessage>){prefs.edit().putString("history",encodeHistory(value)).apply()}
    fun clearHistory(){prefs.edit().remove("history").apply()}
    fun clearAll(){prefs.edit().clear().apply()}
}
internal fun encodeHistory(value:List<AssistantMessage>):String {
    val arr=JSONArray()
    value.filter{it.role in listOf("user","assistant")}.takeLast(24).forEach{
        arr.put(JSONObject().put("role",it.role).put("text",it.text.take(if(it.role=="user")4000 else 12000)))
    }
    return arr.toString()
}
internal fun decodeHistory(raw:String):List<AssistantMessage> = try{
    require(raw.length<=500000)
    val arr=JSONArray(raw)
    (0 until arr.length()).mapNotNull{i->
        val row=arr.optJSONObject(i)?:return@mapNotNull null
        val role=row.optString("role")
        val text=row.optString("text")
        if(role in listOf("user","assistant")&&text.isNotBlank())AssistantMessage(role,text.take(if(role=="user")4000 else 12000))else null
    }.takeLast(24)
}catch(_:Exception){emptyList()}
