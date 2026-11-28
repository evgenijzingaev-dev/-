package ru.smartbasket.app

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

internal data class CatalogProductDetails(
    val fields: Map<String,String>, val images: List<String>,
    val nutrition: ProductNutrition?, val collectedAt: Long
)

internal fun trustedCatalogPhoto(raw:String):String=try{
    val u=URI(publicImageUrl(raw))
    val host=u.host?.lowercase(Locale.ROOT).orEmpty()
    raw.takeIf{host=="5ka.ru"||host.endsWith(".5ka.ru")||host=="x5.ru"||host.endsWith(".x5.ru")}.orEmpty()
}catch(_:Exception){""}

internal class CatalogDetailsStore(context:Context){
    private val root=File(context.applicationContext.filesDir,"catalog-details-v1")
    private fun file(url:String):AtomicFile?{
        val u=Uri.parse(url)
        if(!storeProductUrl(u)||u.query!=null||u.fragment!=null)return null
        val key=u.path.orEmpty().trimEnd('/')
        val digest=MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString(""){String.format(Locale.ROOT,"%02x",it.toInt() and 255)}
        return AtomicFile(File(root,digest+".json"))
    }
    fun read(url:String):CatalogProductDetails?=try{
        val f=file(url)
        if(f==null||!f.baseFile.exists())null else decodeCatalogDetails(JSONObject(f.readFully().toString(Charsets.UTF_8)))
    }catch(_:Exception){null}
    fun write(url:String,details:CatalogProductDetails):Boolean{
        val file=file(url)?:return false
        val previous=read(url)
        val merged=details.copy(nutrition=details.nutrition?:previous?.nutrition,
            fields=previous?.fields.orEmpty()+details.fields,
            images=if(details.images.isNotEmpty())details.images else previous?.images.orEmpty())
        val json=JSONObject().put("fields",JSONObject(merged.fields))
            .put("images",JSONArray(merged.images)).put("collectedAt",merged.collectedAt)
        merged.nutrition?.let{n->json.put("nutrition",JSONObject().put("basis",n.basis)
            .put("kcal",n.value.kcal).put("protein",n.value.protein).put("fat",n.value.fat).put("carbs",n.value.carbs))}
        return try{
            root.mkdirs()
            val stream=file.startWrite()
            try{stream.write(json.toString().toByteArray(Charsets.UTF_8));file.finishWrite(stream);true}
            catch(e:Exception){file.failWrite(stream);false}
        }catch(_:Exception){false}
    }
}

private val detailFields=listOf("Название","Описание","Состав","Бренд","Производитель",
    "Страна производства","Вес","Объём","Фасовка","Срок годности","Условия хранения","Аллергены")

private fun decodeCatalogDetails(o:JSONObject):CatalogProductDetails?{
    val fields=o.optJSONObject("fields")?:JSONObject()
    val values=detailFields.mapNotNull{k->fields.optString(k).trim().take(1500)
        .takeIf{it.isNotBlank()&&it!="null"}?.let{k to it}}.toMap()
    val gallery=o.optJSONArray("images")?:JSONArray()
    val images=(0 until minOf(gallery.length(),8)).map{trustedCatalogPhoto(gallery.optString(it))}
        .filter{it.isNotBlank()}.distinct()
    val n=o.optJSONObject("nutrition")?.let{
        val v=Nutrition(it.optDouble("kcal"),it.optDouble("protein"),it.optDouble("fat"),it.optDouble("carbs"))
        val b=it.optString("basis")
        ProductNutrition(v,b).takeIf{v.valid()&&b in listOf("г","мл")}
    }?:parseNutrition(o.optString("productText"))
    if(values.keys.all{it=="Название"}&&images.isEmpty()&&n==null)return null
    return CatalogProductDetails(values,images,n,o.optLong("collectedAt",System.currentTimeMillis()))
}

// Read only the loaded public product document, not private state, cookies or other products.
internal val catalogDetailScript="""
(function(){
 if(location.protocol!=='https:'||!['5ka.ru','www.5ka.ru'].includes(location.hostname)||!location.pathname.startsWith('/product/'))return '{}';
 const main=document.querySelector('main,[role="main"]')||document.body;
 if(!main)return '{}';
 const clean=(v,n=1500)=>typeof v==='string'?v.replace(/\s+/g,' ').trim().slice(0,n):'';
 const heading=main.querySelector('h1');
 const title=clean(heading?.innerText,400);
 if(!title)return '{}';
 const fields={'Название':title}, images=[];
 const publicSections=/^(характеристики|описание|состав|о товаре|пищевая ценность|калории и бжу)$/i;
 for(const region of [...main.querySelectorAll('details:not([open])')].slice(0,20)){
   const label=region.querySelector('summary')?.innerText;
   if(publicSections.test(clean(label)))region.open=true;
 }
 for(const toggle of [...main.querySelectorAll('button[aria-expanded="false"][aria-controls]')].slice(0,20)){
   if(publicSections.test(clean(toggle.innerText))&&!toggle.dataset.sbDetailsOpened){
     toggle.dataset.sbDetailsOpened='1';toggle.click();
   }
 }
 function addImage(value){
   try{ const u=new URL(value,location.href);if(u.protocol==='https:'&&images.length<8&&!images.includes(u.href))images.push(u.href); }catch(_){}
 }
 let product=null;
 function pick(value,depth){
   if(!value||depth>4||product)return;
   if(Array.isArray(value)){for(const v of value.slice(0,24))pick(v,depth+1);return;}
   if(typeof value!=='object')return;
   if([value['@type']].flat().some(t=>t==='Product'||typeof t==='string'&&t.endsWith('/Product'))&&clean(value.name,400).toLowerCase()===title.toLowerCase()){product=value;return;}
   if(value['@graph'])pick(value['@graph'],depth+1);
   if(value.mainEntity)pick(value.mainEntity,depth+1);
 }
 for(const script of [...document.querySelectorAll('script[type="application/ld+json"]')].slice(0,20)){
   if(script.textContent.length<=180000)try{pick(JSON.parse(script.textContent),0);}catch(_){}
 }
 if(product){
   const description=clean(product.description); if(description)fields['Описание']=description;
   const brand=clean(product.brand?.name||product.brand); if(brand)fields['Бренд']=brand;
   const made=clean(product.manufacturer?.name||product.manufacturer); if(made)fields['Производитель']=made;
   const gallery=Array.isArray(product.image)?product.image:[product.image];
   for(const v of gallery){const src=typeof v==='string'?v:v?.url||v?.contentUrl;if(src)addImage(src);}
 }
 const meta=document.querySelector('meta[property="og:image"]')?.content;
 const metaTitle=clean(document.querySelector('meta[property="og:title"]')?.content,400);
 if(meta&&metaTitle.toLowerCase().includes(title.toLowerCase()))addImage(meta);
 for(const im of [...main.querySelectorAll('img[itemprop="image"], img[alt]')].slice(0,50)){
   if(im.closest('a[href*="/product/"]'))continue;
   if(im.getAttribute('itemprop')==='image'||clean(im.alt,400).toLowerCase()===title.toLowerCase()){
     const src=im.currentSrc||im.getAttribute('data-src')||im.src;if(src)addImage(src);
   }
 }
 const lines=(main.innerText||'').slice(0,24000).split(/\n/).map(l=>clean(l)).filter(Boolean);
 const labels=new Map([['описание','Описание'],['состав','Состав'],['бренд','Бренд'],['производитель','Производитель'],
 ['страна производства','Страна производства'],['страна','Страна производства'],['вес','Вес'],['масса нетто','Вес'],
 ['объём','Объём'],['объем','Объём'],['фасовка','Фасовка'],['срок годности','Срок годности'],
 ['условия хранения','Условия хранения'],['аллергены','Аллергены']]);
 const stop=/^(характеристики|о товаре|похожие товары|с этим покупают|отзывы|пищевая ценность|в корзину|купить)/i;
 for(let i=0;i<lines.length;i++){
   if(/^(похожие товары|с этим покупают|вам может понравиться|рекомендуем)/i.test(lines[i]))break;
   const match=lines[i].match(/^([^:]{1,40}):\s*(.*)$/);
   const key=labels.get((match?match[1]:lines[i]).toLowerCase());
   if(!key)continue;
   const next=match?match[2]:lines[i+1];
   if(next&&!stop.test(next)&&!labels.has(next.toLowerCase())&&!next.includes('₽'))fields[key]=next;
 }
 const end=lines.findIndex(l=>/^(похожие товары|с этим покупают|вам может понравиться|рекомендуем)/i.test(l));
 return JSON.stringify({fields,images,productText:lines.slice(0,end<0?lines.length:end).join('\n')});
})()
""".trimIndent()

internal fun readCatalogProductDetails(o:JSONObject)=decodeCatalogDetails(o)
