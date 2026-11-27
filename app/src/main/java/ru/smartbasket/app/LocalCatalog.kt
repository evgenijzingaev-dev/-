
package ru.smartbasket.app

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject

data class CatalogEntry(val url:String,val text:String,val plu:String="",val name:String="",val regular:Long?=null,val available:Boolean?=null,val uom:String="")
data class CatalogSnapshot(val store:String,val updated:Long,val categories:Int,val failed:Int,val entries:List<CatalogEntry>,val source:String="web")
data class CatalogMatch(val entry:CatalogEntry,val offer:StoreOffer)

fun findCatalogMatch(entries:List<CatalogEntry>,item:BasketItem):CatalogMatch? =
    entries.asSequence().filter{validTransferUrl(it.url)||it.plu.matches(Regex("[0-9]{1,20}"))}
        .mapNotNull{e->(if(e.plu.isNotBlank()) apiOffer(e,item.unit) else parseStoreOffer(e.text,item.unit))?.takeIf{!it.conditional&&offerMatches(item.name,it.description)}?.let{CatalogMatch(e,it)}}
        .minByOrNull{it.offer.total(item.need)}

fun displayCatalogPrice(text:String):Long? {
    val t=text.replace(Regex("[\\s\\u00a0\\u202f]+")," ")
    val re=Regex("(?iu)(старая\\s+)?цена\\s+([0-9][0-9 ]*?)[,. ]([0-9]{2})\\s*₽")
    val m=re.findAll(t).filter{it.groupValues[1].isEmpty()}.singleOrNull()?:return null
    return money(m.groupValues[2].replace(" ","")+"."+m.groupValues[3])
}
class LocalCatalog(context:Context){
    private val appContext=context.applicationContext
    private val file=AtomicFile(java.io.File(context.filesDir,"store-catalog.json"))
    fun read():CatalogSnapshot?=try{
        val j=JSONObject(file.readFully().toString(Charsets.UTF_8))
        val a=j.getJSONArray("entries")
        CatalogSnapshot(j.getString("store"),j.getLong("updated"),j.getInt("categories"),j.getInt("failed"),(0 until a.length()).map{
            val o=a.getJSONObject(it);CatalogEntry(o.getString("url"),o.getString("text"),o.optString("plu"),o.optString("name"),if(o.isNull("regular"))null else o.getLong("regular"),if(o.isNull("available"))null else o.getBoolean("available"),o.optString("uom"))
        },j.optString("source","web"))
    }catch(_:Exception){null}
    fun write(s:CatalogSnapshot){
        val a=JSONArray()
        for(e in s.entries) a.put(JSONObject().put("url",e.url).put("text",e.text).put("plu",e.plu).put("name",e.name).put("regular",e.regular?:JSONObject.NULL).put("available",e.available?:JSONObject.NULL).put("uom",e.uom))
        val j=JSONObject().put("store",s.store).put("updated",s.updated).put("categories",s.categories).put("failed",s.failed).put("entries",a).put("source",s.source)
        val stream=file.startWrite()
        try{stream.write(j.toString().toByteArray(Charsets.UTF_8));file.finishWrite(stream)}
        catch(e:Exception){file.failWrite(stream);throw e}
        try{PriceHistory(appContext).use{it.record(s)}}catch(e:Exception){android.util.Log.w("PriceHistory","Price observations not saved",e)}
    }
}
