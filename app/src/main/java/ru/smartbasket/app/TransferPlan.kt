
package ru.smartbasket.app

import java.net.URI

data class TransferLine(val name:String,val url:String,val quantity:Long,val price:Long)
data class TransferPlan(val lines:List<TransferLine>,val total:Long,val error:String?=null)

fun validTransferUrl(raw:String):Boolean=try{
    val u=URI(raw)
    u.scheme=="https"&&u.host in setOf("5ka.ru","www.5ka.ru")&&u.port in listOf(-1,443)&&u.userInfo==null&&u.rawQuery==null&&u.fragment==null&&u.path.startsWith("/product/")&&u.path.length>9
}catch(_:Exception){false}

/** Refuse incomplete, duplicate, or over-budget plans; never infer existing cart contents. */
fun buildTransfer(items:List<BasketItem>,budget:Long,delivery:Long):TransferPlan {
    fun fail(s:String)=TransferPlan(emptyList(),0,s)
    val pending=items.filter{it.need>0&&!it.checked}
    if(pending.isEmpty())return fail("Нет продуктов для переноса.")
    if(pending.size>50)return fail("За один перенос не более 50 товаров.")
    if(items.any{it.cost==null})return fail("Не все цены известны. Сначала подбери все товары.")
    val summary=summarize(items,budget,delivery)
    if(summary.over)return fail("Расчёт выше бюджета. Уменьши список или измени бюджет.")
    if(pending.any{!validTransferUrl(it.storeUrl)||it.storeName.isBlank()})return fail("Не для всех продуктов есть конкретная карточка магазина. Выполни подбор.")
    val lines=pending.map{TransferLine(it.storeName,it.storeUrl,it.packs?:0L,it.price?:-1L)}
    if(lines.any{it.quantity !in 1..30||it.price !in 1..100_000_000})return fail("Допустимо от 1 до 30 упаковок одного товара с ненулевой ценой.")
    val keys=lines.map{URI(it.url).path.trimEnd('/')}
    if(keys.toSet().size!=keys.size)return fail("Одна карточка выбрана для нескольких ингредиентов. Объедини позиции вручную.")
    if(lines.sumOf{it.quantity}>150)return fail("За один перенос не более 150 упаковок.")
    return TransferPlan(lines,summary.totalKnown)
}
