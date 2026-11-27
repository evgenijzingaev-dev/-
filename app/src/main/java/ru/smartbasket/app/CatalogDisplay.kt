package ru.smartbasket.app

internal data class CatalogDisplay(val title: String, val priceLabel: String) {
    fun label(): String = "$title\n$priceLabel"
}

private val cardSpace = Regex("[\\s\\u00a0\\u202f]+")
private val cardMetadata = Regex(
    """(?iu)^(?:хит|новинка|акция|скидка|в наличии|нет в наличии|товар закончился|в корзину|добавить)(?:$|\s*[:!])|^(?:%|[+−-]?\d+(?:[.,]\d+)?\s*(?:%|₽)?$)"""
)
private val cardPriceHeader = Regex(
    """(?iu)(?<![\p{L}\p{N}])(?:(?:старая|обычная|регулярная|акционная|новая)\s+)?цена(?![\p{L}\p{N}])"""
)
private val cardConditional = Regex(
    """(?iu)(?:от\s*\d+\s*(?:шт|товар|упак)|\d+\s*\+\s*\d+|по\s+карте|с\s+картой|по\s+купону|комбо|при\s+покупке|акционная\s+цена|цена\s+со\s+скидкой)"""
)
private val cardPriceValue = Regex(
    """(?iu)(?<![\p{L}\p{N}])((?:(?:старая|обычная|регулярная|новая)\s+)?)цена\s*:?\s*([0-9]{1,3}(?: [0-9]{3})+|[0-9]+)(?:[.,]| )([0-9]{2})\s*₽"""
)
private val cardPriceBasis = Regex(
    """(?iu)(?:цена\s+(?:за\s+|/\s*)|₽\s*(?:за\s+|/\s*))((?:[0-9]+(?:[.,][0-9]+)?\s*)?(?:кг|мл|г|л|шт|упаковку))(?![\p{L}\p{N}])"""
)
private fun cleanCardText(raw: String) = raw.replace(cardSpace, " ").trim()

private fun catalogTitle(entry: CatalogEntry): String {
    cleanCardText(entry.name).takeIf { it.isNotEmpty() }?.let { return it }
    return entry.text.lineSequence()
        .map(::cleanCardText)
        .filter { it.isNotEmpty() && !cardMetadata.containsMatchIn(it) }
        .map { line ->
            val priceStart = cardPriceHeader.find(line)?.range?.first
            if (priceStart == null) line else line.take(priceStart).trim()
        }
        .firstOrNull { it.any(Char::isLetter) }
        ?.take(220)
        ?: "Название не распознано"
}

private fun cardWebPrice(text: String): Long? {
    val matches = cardPriceValue.findAll(text).toList()
    fun isRegular(match: MatchResult): Boolean =
        match.groupValues[1].trim().lowercase() in setOf("обычная", "регулярная")
    val regular = matches.filter(::isRegular)
    val current = if (regular.isNotEmpty()) regular.singleOrNull()
        else if (cardConditional.containsMatchIn(text)) null
        else matches.filter { it.groupValues[1].isBlank() }.singleOrNull()
    current ?: return null
    return money(current.groupValues[2].replace(" ", "") + "." + current.groupValues[3])
        ?.takeIf { it > 0 }
}

internal fun catalogDisplay(entry: CatalogEntry): CatalogDisplay {
    val title = catalogTitle(entry)
    val text = cleanCardText(entry.text)
    val structured = entry.plu.isNotBlank() || entry.regular != null
    val price = if (structured) entry.regular?.takeIf { it > 0 } else cardWebPrice(text)
    if (price == null) return CatalogDisplay(title, "Цена не подтверждена")

    val rawBases = cardPriceBasis.findAll(text)
        .map { it.groupValues[1].replace(" ", "").lowercase() }
        .distinct()
        .toList()
    val webBasis = rawBases.singleOrNull()?.replace(
        Regex("""^([0-9]+(?:[.,][0-9]+)?)(?=\p{L})"""), "$1 "
    )
    val basis = when {
        structured -> when (entry.uom.trim().lowercase()) {
            "кг" -> "кг"
            "г" -> "г"
            "л" -> "л"
            "мл" -> "мл"
            "шт" -> "шт"
            else -> null
        }
        else -> webBasis
    }
    return CatalogDisplay(
        title,
        if (basis == null) "${rub(price)} · единица цены не подтверждена"
        else "${rub(price)} за $basis"
    )
}
