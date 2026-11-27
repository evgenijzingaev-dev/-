package ru.smartbasket.app

import org.json.JSONArray
import org.json.JSONObject

data class BasketProposal(val items:List<BasketItem>,val catalogUpdated:Long?)
data class AssistantResult(val text:String,val proposal:BasketProposal?,val preferenceProposal:String?=null)

internal fun proposeBasket(args:JSONObject,previous:List<BasketItem>,snapshot:CatalogSnapshot?,now:Long):BasketProposal {
    val rows=args.getJSONArray("items")
    require(rows.length() in 1..100)
    val seen=mutableSetOf<String>()
    val old=previous.associateBy{key(it.name,it.unit)}
    val fresh=snapshot?.takeIf{now-it.updated in 0L..21_600_000L}
    val entries=fresh?.entries.orEmpty().filter{it.available!=false}
    val proposed=(0 until rows.length()).map{index->
        val row=rows.getJSONObject(index)
        val name=row.getString("name").trim()
        val unit=row.getString("unit")
        val raw=row.get("amount")
        require(raw is Number&&raw.toDouble().isFinite()&&raw.toDouble()==raw.toLong().toDouble())
        val amount=raw.toLong()
        require(name.length in 1..120&&name.none{it.isISOControl()}&&unit in listOf("г","мл","шт")&&amount in 1..1_000_000)
        val k=key(name,unit)
        require(seen.add(k))
        val item=BasketItem(name,amount,unit,atHome=old[k]?.atHome?:0L)
        val match=if(item.need>0)findCatalogMatch(entries,item)else null
        if(match==null)item else item.copy(pack=match.offer.pack,price=match.offer.price,storeUrl=match.entry.url,storeName=match.offer.description)
    }
    return BasketProposal(proposed,fresh?.updated)
}
