package ru.smartbasket.app

data class StoreOffer(val description:String,val pack:Long,val price:Long,val conditional:Boolean) {
    fun packages(need:Long):Long = if(need<=0)0 else (need-1)/pack+1
    fun total(need:Long):Long = Math.multiplyExact(packages(need),price)
}

/** Parses visible catalog card text only; refuses unclear or per-weight prices. */
fun parseStoreOffer(raw:String,unit:String):StoreOffer? {
    val t=raw.replace(Regex("[\\s\\u00a0\\u202f]+")," ").trim()
    if(t.length !in 3..1200 || unit !in listOf("г","мл","шт")) return null
    if(Regex("(?i)(?:за|/|₽/)\\s*(?:1\\s*)?(?:кг|л|100\\s*г)(?:\\b|$)").containsMatchIn(t)) return null
    val priceRe=Regex("(?i)(старая\\s+)?цена\\s+([0-9][0-9 ]*?)(?:[.,]| )([0-9]{2})\\s*₽")
    val matches=priceRe.findAll(t).toList()
    val current=matches.filter{it.groupValues[1].isEmpty()}.singleOrNull()?:return null
    val price=money(current.groupValues[2].replace(" ","")+"."+current.groupValues[3])?:return null
    val before=t.substring(0,matches.first().range.first).trim()
    val q=Regex("(?i)([0-9]+(?:[.,][0-9]+)?)\\s*(кг|мл|г|л|шт)\\s*$").find(before)?:return null
    val value=q.groupValues[1].replace(',','.').toBigDecimalOrNull()?:return null
    val u=q.groupValues[2].lowercase()
    if((when(u){"кг"->"г";"л"->"мл";else->u})!=unit)return null
    val pack=try{(if(u=="кг"||u=="л")value.multiply(java.math.BigDecimal(1000))else value).longValueExact()}catch(_:Exception){return null}
    if(pack !in 1..10_000_000)return null
    val conditional=Regex("(?i)(от\\s*\\d+\\s*шт|\\d+\\s*\\+\\s*\\d+|по карте|с картой|комбо)").containsMatchIn(t)
    return StoreOffer(before.take(220),pack,price,conditional)
}
fun offerMatches(name:String,description:String):Boolean {
    val n=normalized(name)
    val d=normalized(description)
    val words=n.split(Regex("[^а-яa-z0-9]+")).filter{it.length>=3}
    val synonyms=mapOf("помидоры" to listOf("помидор","томат"),"яйца" to listOf("яйцо","яйца"),"манная" to listOf("манная","манн","манка"),"слабосоленая" to listOf("слабосол"),"красная" to listOf("красная","лосос","семг","форел","горбуш","кета"),"рыба" to listOf("рыба","лосос","семг","форел","горбуш","кета"))
    return words.isNotEmpty()&&words.all{w->(synonyms[w]?:listOf(w)).any{d.contains(it)}}
}
