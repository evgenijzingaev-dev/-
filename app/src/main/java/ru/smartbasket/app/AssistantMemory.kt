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
    val arr = JSONArray()
    val messages = value.filter { it.role == "user" || it.role == "assistant" }.takeLast(24)
    val rows = messages.map { message ->
        JSONObject().put("role", message.role)
            .put("text", message.text.take(if (message.role == "user") 4000 else 12000))
    }
    // Retain the recent dialogue first, then spend the remaining space on its newest product cards.
    var remaining = (500000 - rows.sumOf { it.toString().length + 1 } - 2).coerceAtLeast(0)
    for (index in rows.indices.reversed()) {
        val message = messages[index]
        if (message.role != "assistant" || message.groups.isEmpty()) continue
        val groups = JSONArray()
        for (group in message.groups) {
            val options = JSONArray()
            for (option in group.options) {
                options.put(JSONObject().put("title", option.title).put("pack", option.pack)
                    .put("price", option.price).put("image", option.imageUrl)
                    .put("url", option.url).put("comment", option.comment))
            }
            val entry = JSONObject().put("name", group.name).put("options", options)
            val cost = entry.toString().length + if (groups.length() == 0) 16 else 1
            if (cost > remaining) continue
            groups.put(entry)
            remaining -= cost
        }
        if (groups.length() > 0) rows[index].put("groups", groups)
    }
    rows.forEach { arr.put(it) }
    return arr.toString()
}

private fun storedGroups(raw: JSONArray?): List<ProductGroup> = buildList {
    if (raw == null) return@buildList
    for (index in 0 until raw.length()) {
        val group = raw.optJSONObject(index) ?: continue
        val entries = group.optJSONArray("options") ?: continue
        val options = (0 until entries.length()).mapNotNull { entryIndex ->
            val entry = entries.optJSONObject(entryIndex) ?: return@mapNotNull null
            val title = entry.optString("title")
            if (title.isBlank()) return@mapNotNull null
            ProductOption(title, entry.optString("pack"), entry.optString("price"),
                entry.optString("image"), entry.optString("url"), entry.optString("comment"))
        }
        if (options.isNotEmpty()) add(ProductGroup(group.optString("name"), options))
    }
}

internal fun decodeHistory(raw:String):List<AssistantMessage> = try{
    require(raw.length<=500000)
    val arr=JSONArray(raw)
    (0 until arr.length()).mapNotNull{i->
        val row=arr.optJSONObject(i)?:return@mapNotNull null
        val role=row.optString("role")
        val text=row.optString("text")
        if (role !in listOf("user", "assistant")) return@mapNotNull null
        val groups = if (role == "assistant") storedGroups(row.optJSONArray("groups")) else emptyList()
        if (text.isBlank() && groups.isEmpty()) return@mapNotNull null
        AssistantMessage(role, text.take(if (role == "user") 4000 else 12000), groups)
    }.takeLast(24)
}catch(_:Exception){emptyList()}
