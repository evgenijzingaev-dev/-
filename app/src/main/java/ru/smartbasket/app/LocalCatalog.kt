
package ru.smartbasket.app

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject

data class CatalogEntry(val url:String,val text:String)
data class CatalogSnapshot(val store:String,val updated:Long,val categories:Int,val failed:Int,val entries:List<CatalogEntry>)
data class CatalogMatch(val entry:CatalogEntry,val offer:StoreOffer)

fun findCatalogMatch(entries:List<CatalogEntry>,item:BasketItem):CatalogMatch? =
    entries.asSequence().filter{validTransferUrl(it.url)}
        .mapNotNull{e->parseStoreOffer(e.text,item.unit)?.takeIf{!it.conditional&&offerMatches(item.name,it.description)}?.let{CatalogMatch(e,it)}}
        .minByOrNull{it.offer.total(item.need)}

fun displayCatalogPrice(text:String):Long? {
    val t=text.replace(Regex("[\\s\\u00a0\\u202f]+")," ")
    val re=Regex("(?iu)(старая\\s+)?цена\\s+([0-9][0-9 ]*?)[,. ]([0-9]{2})\\s*₽")
    val m=re.findAll(t).filter{it.groupValues[1].isEmpty()}.singleOrNull()?:return null
    return money(m.groupValues[2].replace(" ","")+"."+m.groupValues[3])
}
class LocalCatalog(context:Context){
    private val file=AtomicFile(java.io.File(context.filesDir,"store-catalog.json"))
    fun read():CatalogSnapshot?=try{
        val j=JSONObject(file.readFully().toString(Charsets.UTF_8))
        val a=j.getJSONArray("entries")
        CatalogSnapshot(j.getString("store"),j.getLong("updated"),j.getInt("categories"),j.getInt("failed"),(0 until a.length()).map{
            val o=a.getJSONObject(it);CatalogEntry(o.getString("url"),o.getString("text"))
        })
    }catch(_:Exception){null}
    fun write(s:CatalogSnapshot){
        val a=JSONArray()
        for(e in s.entries) a.put(JSONObject().put("url",e.url).put("text",e.text))
        val j=JSONObject().put("store",s.store).put("updated",s.updated).put("categories",s.categories).put("failed",s.failed).put("entries",a)
        val stream=file.startWrite()
        try{stream.write(j.toString().toByteArray(Charsets.UTF_8));file.finishWrite(stream)}
        catch(e:Exception){file.failWrite(stream);throw e}
    }
}
