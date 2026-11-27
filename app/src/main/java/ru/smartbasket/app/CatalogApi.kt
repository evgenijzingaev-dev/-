
package ru.smartbasket.app

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class CatalogHttpException(val status:Int):IllegalStateException("API вернул HTTP "+status)

/** Read-only, experimental site API. No authentication material, impersonation, redirects or retries. */
class CatalogApi(private val cancelled:()->Boolean,private val getOverride:((String)->String)?=null){
    private var requests=0
    private fun get(path:String):Any{
        if(cancelled())throw IllegalStateException("Загрузка отменена")
        if(++requests>1200)throw IllegalStateException("Лимит запросов; база не заменена")
        if(requests>1)Thread.sleep(600)
        if(cancelled())throw IllegalStateException("Загрузка отменена")
        val url="https://5d.5ka.ru/api/catalog/v2/stores/"+path
        val body=getOverride?.invoke(url)?:run{
            val c=URL(url).openConnection() as HttpsURLConnection
            c.requestMethod="GET";c.instanceFollowRedirects=false;c.connectTimeout=12000;c.readTimeout=20000
            c.setRequestProperty("Accept","application/json")
            try{
                val status=c.responseCode
                if(status !in 200..299)throw CatalogHttpException(status)
                if(!c.contentType.orEmpty().contains("json",true))throw IllegalStateException("API не вернул JSON")
                c.inputStream.bufferedReader(Charsets.UTF_8).use{reader->
                    val out=StringBuilder();val buf=CharArray(8192)
                    while(true){if(cancelled())throw IllegalStateException("Загрузка отменена");val n=reader.read(buf);if(n<0)break;out.append(buf,0,n);if(out.length>12_000_000)throw IllegalStateException("Слишком большой ответ")}
                    out.toString()
                }
            }finally{c.disconnect()}
        }
        return JSONTokener(body).nextValue()
    }
    /** Optional read-only product detail endpoint; use only when normal access is available. */
    fun productDetail(store:String,plu:String):JSONObject{
        require(store.matches(Regex("[A-Za-z0-9_-]{1,40}"))&&plu.matches(Regex("[0-9]{1,20}")))
        return get(store+"/products/"+plu+"?mode=delivery&include_restrict=true") as? JSONObject
            ?:throw IllegalStateException("Неизвестный формат карточки")
    }
    fun download(store:String,progress:(Int,Int,Int)->Unit):CatalogSnapshot{
        require(store.matches(Regex("[A-Za-z0-9_-]{1,40}")))
        val tree=get(store+"/categories?mode=delivery&include_restrict=true&include_subcategories=0") as? JSONArray?:throw IllegalStateException("Неизвестный формат дерева категорий")
        val ids=primaryApiCategories(tree)
        require(ids.isNotEmpty()&&ids.size<=400){"Дерево категорий пустое или превышает лимит"}
        val entries=linkedMapOf<String,CatalogEntry>()
        for((i,id) in ids.withIndex()){
            var offset=0
            val seen=hashSetOf<String>()
            var pages=0
            while(true){
                if(++pages>300)throw IllegalStateException("Не подтверждён конец выдачи категории "+id)
                val page=get(store+"/categories/"+id+"/products?mode=delivery&include_restrict=true&limit=100&offset="+offset) as? JSONObject?:throw IllegalStateException("Неизвестный формат товаров")
                val a=page.optJSONArray("products")?:throw IllegalStateException("В ответе нет списка товаров")
                if(a.length()==0)break
                var newCount=0
                for(n in 0 until a.length()){
                    val product=parseApiProduct(a.getJSONObject(n))?:throw IllegalStateException("Неизвестная структура товара: прежняя база сохранена")
                    if(seen.add(product.plu))newCount++
                    val old=entries[product.plu]
                    if(old!=null&&(old.regular!=product.regular||old.available!=product.available))throw IllegalStateException("Цена или наличие изменились в ходе выгрузки. Повтори обновление позднее.")
                    entries[product.plu]=product
                }
                if(newCount!=a.length())throw IllegalStateException("API повторяет страницу; полная выгрузка не подтверждена")
                offset+=a.length()
                if(entries.size>30000)throw IllegalStateException("Превышен предел 30 000 товаров")
                progress(i+1,ids.size,entries.size)
                val total=page.optLong("total",-1)
                if(total>=0&&offset.toLong()>=total)break
            }
            progress(i+1,ids.size,entries.size)
        }
        require(entries.isNotEmpty()){"Каталог пуст; прежняя база сохранена"}
        if(cancelled())throw IllegalStateException("Загрузка отменена")
        return CatalogSnapshot(store,System.currentTimeMillis(),ids.size,0,entries.values.toList(),"api")
    }
}
/** Use the catalog's top-level sections; nested promotional collections are not separate scans. */
fun primaryApiCategories(tree:JSONArray):List<String>{
    val result=linkedSetOf<String>()
    for(i in 0 until tree.length()){
        val id=tree.getJSONObject(i).optString("id")
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}"))){"Некорректный ID категории"}
        result.add(id)
    }
    return result.toList()
}
fun leafCategories(tree:JSONArray):List<String>{
    val result=linkedSetOf<String>()
    fun walk(arr:JSONArray,depth:Int){
        require(depth<=12)
        for(i in 0 until arr.length()){
            val o=arr.getJSONObject(i);val sub=o.optJSONArray("categories")
            if(sub!=null&&sub.length()>0)walk(sub,depth+1)
            else {
                val id=o.optString("id")
                require(id.matches(Regex("[A-Za-z0-9_-]{1,80}"))){"Некорректный ID категории"}
                result.add(id)
            }
        }
    }
    walk(tree,0);return result.toList()
}
fun parseApiProduct(o:JSONObject):CatalogEntry?{
    val plu=o.optString("plu")
    val name=o.optString("name").trim()
    if(!plu.matches(Regex("[0-9]{1,20}"))||name.isBlank()||name.length>500)return null
    val uom=o.optString("uom")
    val prices=o.optJSONObject("prices")
    val price=prices?.optString("regular")?.let(::money)?.takeIf{it>0}
    val available=when(o.opt("is_available")){true->true;false->false;else->null}
    val url=o.optString("url").takeIf(::validTransferUrl).orEmpty()
    val text=name+"\n"+(if(price==null)"Цена неизвестна" else "Обычная цена: "+rub(price)+(if(uom=="кг")" / кг" else ""))+"\n"+(when(available){true->"В наличии";false->"Нет в наличии";null->"Наличие неизвестно"})
    return CatalogEntry(url,text,plu,name,price,available,uom)
}
fun apiOffer(e:CatalogEntry,unit:String):StoreOffer?{
    val price=e.regular?:return null
    if(e.available!=true||e.uom!="шт"||price<=0)return null
    val raw=e.name.trim()
    if(unit=="шт"){
        val n=Regex("(?iu)([0-9]+)\\s*шт\\.?\\s*$").find(raw)?.groupValues?.get(1)?.toLongOrNull()
        return StoreOffer(raw,n?:1L,price,false).takeIf{it.pack in 1..1000000}
    }
    val priceText=(price/100).toString()+","+(price%100).toString().padStart(2,'0')
    return parseStoreOffer(raw+" Цена "+priceText+" ₽",unit)
}
