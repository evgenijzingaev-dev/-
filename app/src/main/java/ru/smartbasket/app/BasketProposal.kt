package ru.smartbasket.app

import org.json.JSONArray
import org.json.JSONObject

data class BasketProposal(val items:List<BasketItem>,val catalogUpdated:Long?, val catalogHint:String? = null)
data class ProductOption(val title:String, val pack:String, val price:String, val imageUrl:String, val url:String, val comment:String)
data class ProductGroup(val name:String, val options:List<ProductOption>)
data class AssistantResult(val text:String,val proposal:BasketProposal?,val preferenceProposal:String?=null,val dishes:List<GeneratedDish> = emptyList(), val groups:List<ProductGroup> = emptyList())

internal fun proposeBasket(args:JSONObject,previous:List<BasketItem>,snapshot:CatalogSnapshot?,now:Long, selected:Map<Int,CatalogEntry> = emptyMap()):BasketProposal {
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
        val sku = selected[index]
        if (sku != null && item.need > 0) {
            val offer = if (fresh == null || fresh.failed != 0) null
                else if (sku.plu.isNotBlank()) apiOffer(sku, unit) else parseStoreOffer(sku.text, unit)
            val regular = offer?.takeUnless { it.conditional }
            return@map item.copy(storeName = catalogDisplay(sku).title, storeUrl = sku.url.takeIf(::validTransferUrl).orEmpty(),
                pack = regular?.pack ?: 0L, price = regular?.price)
        }
        val match=if(item.need>0)findCatalogMatch(entries,item)else null
        if(match==null)item else item.copy(pack=match.offer.pack,price=match.offer.price,storeUrl=match.entry.url,storeName=match.offer.description)
    }
    val catalogHint = when {
        proposed.none { it.cost == null } -> null
        snapshot == null -> "Локальный каталог недоступен."
        fresh == null -> "Цены в сохранённом каталоге устарели."
        else -> null
    }?.let { "$it Чтобы получить текущие цены, нажми «Обновить цены» и повтори подбор." }
    return BasketProposal(proposed, fresh?.updated, catalogHint)
}

internal fun BasketProposal.previewRows(lookup: (String) -> ProductNutrition?): String =
    items.joinToString("\n\n") { proposalItemLabel(it, catalogUpdated != null, lookup) }

internal fun proposalItemLabel(item: BasketItem, hasFreshCatalog: Boolean = false, lookup: (String) -> ProductNutrition?): String {
    val product = item.storeUrl.takeIf { it.isNotBlank() }?.let(lookup)
    val line = nutritionLine(item.name, item.amount.toDouble(), item.unit, product)
    return buildString {
        append("${item.name} — ${item.amount} ${item.unit}")
        if (item.storeName.isNotBlank()) append("\n${item.storeName}")
        append("\nК покупке: ")
        if (item.need == 0L) append("всё есть дома")
        else if (item.cost == null) {
            append("цена неизвестна")
            if (hasFreshCatalog) append(" — не найдена подходящая доступная карточка с подтверждённой ценой")
        }
        else append("${item.packs} уп. × ${rub(requireNotNull(item.price))} = ${rub(requireNotNull(item.cost))}")
        append("\nКБЖУ на ${item.amount} ${item.unit}: ")
        if (line.total == null) append("нет данных")
        else {
            if (line.estimated) append("оценка · ")
            append(line.total.label())
        }
    }
}
