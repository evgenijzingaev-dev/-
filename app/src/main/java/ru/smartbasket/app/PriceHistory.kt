package ru.smartbasket.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.net.URI

private const val DAY_MS=86_400_000L
data class ObservedPrice(val product:String,val title:String,val unit:String,val pack:Long,val price:Long,val advertisedOld:Long?)
data class DiscountAssessment(val typical:Long,val low:Long,val days:Int,val saving:Long,val oldAboveTypical:Boolean)

/** Compare like-for-like public prices; sparse observations do not establish a discount. */
fun assessDiscount(current:Long,old:Long?,prior:List<Pair<Long,Long>>):DiscountAssessment? {
    val daily=prior.filter{it.second>0}.groupBy{it.first}.mapValues{(_,v)->v.last().second}
    if(current<=0||daily.size<3||daily.keys.maxOrNull()!!-daily.keys.minOrNull()!!<7)return null
    val ordered=daily.values.sorted()
    val typical=ordered[ordered.size/2]
    return DiscountAssessment(typical,ordered.first(),daily.size,typical-current,old!=null&&old*100>typical*110)
}

fun observedPublicPrice(e:CatalogEntry):ObservedPrice? {
    // API "regular" is not necessarily the amount charged; only use comparable visible offers.
    if(e.plu.isNotBlank()||!validTransferUrl(e.url))return null
    val t=e.text.replace(Regex("[\\s\\u00a0\\u202f]+")," ").trim()
    val unitOffer=listOf("г","мл","шт").mapNotNull{u->parseStoreOffer(t,u)?.let{u to it}}.singleOrNull()?:return null
    val (unit,offer)=unitOffer
    if(offer.conditional||offer.price<=0||Regex("(?iu)(нет в наличии|товар недоступен|цена от|недоступно)").containsMatchIn(t))return null
    val oldMatch=Regex("(?iu)старая\\s+цена\\s+([0-9][0-9 ]*?)[,. ]([0-9]{2})\\s*₽").findAll(t).singleOrNull()
    val old=oldMatch?.let{money(it.groupValues[1].replace(" ","")+"."+it.groupValues[2])}?.takeIf{it>offer.price}
    val path=try{URI(e.url).path.trimEnd('/')}catch(_:Exception){return null}
    return ObservedPrice("$path|$unit|"+offer.pack,offer.description,unit,offer.pack,offer.price,old)
}

class PriceHistory(context:Context):SQLiteOpenHelper(context.applicationContext,"price-history.db",null,1) {
    override fun onCreate(db:SQLiteDatabase){
        db.execSQL("CREATE TABLE observation (store TEXT NOT NULL, product TEXT NOT NULL, day INTEGER NOT NULL, price INTEGER NOT NULL, PRIMARY KEY(store,product,day))")
        db.execSQL("CREATE INDEX observation_day ON observation(day)")
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int)=Unit
    fun record(snapshot:CatalogSnapshot){
        if(snapshot.source!="web"||snapshot.store.isBlank()||snapshot.updated<=0)return
        val day=snapshot.updated/DAY_MS
        val db=writableDatabase
        db.beginTransaction()
        try{
            for(e in snapshot.entries){
                val p=observedPublicPrice(e)?:continue
                val row=ContentValues().apply{put("store",snapshot.store);put("product",p.product);put("day",day);put("price",p.price)}
                db.insertWithOnConflict("observation",null,row,SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.delete("observation","day < ?",arrayOf((day-90).toString()))
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }
    fun report(snapshot:CatalogSnapshot):String {
        val day=snapshot.updated/DAY_MS
        val db=readableDatabase
        val deals=mutableListOf<Pair<Long,String>>()
        var unknown=0
        var comparable=0
        val seen=hashSetOf<String>()
        for(e in snapshot.entries){
            val p=observedPublicPrice(e)?:continue
            if(!seen.add(p.product))continue
            comparable++
            val prior=mutableListOf<Pair<Long,Long>>()
            db.rawQuery("SELECT day,price FROM observation WHERE store=? AND product=? AND day>=? AND day<? ORDER BY day",arrayOf(snapshot.store,p.product,(day-30).toString(),day.toString())).use{c->
                while(c.moveToNext())prior.add(c.getLong(0) to c.getLong(1))
            }
            val a=assessDiscount(p.price,p.advertisedOld,prior)
            if(a==null){if(p.advertisedOld!=null)unknown++;continue}
            if(p.advertisedOld==null && a.saving*100<a.typical*5)continue
            val tag=when{
                p.price<=a.low&&a.saving*100>=a.typical*5->"Не дороже минимума за период"
                a.saving*100>=a.typical*5->"Ниже обычной наблюдавшейся цены"
                else->"Заметная выгода не подтверждена"
            }
            val warning=if(a.oldAboveTypical)"\nЗачёркнутая цена выше типичной более чем на 10%; причина не установлена." else ""
            val base=if(p.unit=="шт")1L else 1000L
            val normalizedPrice=(p.price*base+p.pack/2)/p.pack
            val label=when(p.unit){"г"->"кг";"мл"->"л";else->"шт"}
            val item=p.title+"\n"+tag+" · "+rub(p.price)+" ("+rub(normalizedPrice)+"/"+label+")\nТипичная: "+rub(a.typical)+" · минимум: "+rub(a.low)+" · дней наблюдений: "+a.days+
                (p.advertisedOld?.let{"\nЦена до скидки на витрине: "+rub(it)}?:"")+warning
            deals.add((if(a.saving>0)a.saving else 0L) to item)
        }
        return "Сравнение за 30 дней по этому магазину. Минимум 3 предыдущих дня наблюдений с интервалом не менее 7 дней. Проверяемые предложения: $comparable. Акции без достаточной истории: $unknown.\n\n"+
            (if(deals.isEmpty())"Пока нет подтверждённых выгодных предложений или акций с достаточной историей." else deals.sortedByDescending{it.first}.take(100).joinToString("\n\n"){it.second})+
            "\n\nНаблюдаемые цены, не полный рыночный мониторинг. Персональные и условные акции исключены."
    }
}
