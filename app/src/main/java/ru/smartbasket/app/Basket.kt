package ru.smartbasket.app

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/** Quantities are integer grams, millilitres or individual units; money is kopecks. */
data class Ingredient(val name: String, val amount: Long, val unit: String)
data class Recipe(val name: String, val servings: Int, val ingredients: List<Ingredient>, val method: String)
data class BasketItem(val name: String, val amount: Long, val unit: String, val atHome: Long = 0, val pack: Long = 0, val price: Long? = null, val checked: Boolean = false) {
    val need: Long get() = (amount - atHome).coerceAtLeast(0)
    val packs: Long? get() = if (need == 0L) 0 else if (pack > 0) (need - 1) / pack + 1 else null
    val cost: Long? get() = if (need == 0L) 0 else if (price != null && packs != null) Math.multiplyExact(price, packs!!) else null
}
fun money(s: String): Long? = try {
    BigDecimal(s.trim().replace(',', '.')).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact().takeIf { it in 0..100_000_000 }
} catch (_: Exception) { null }
fun rub(k: Long): String = "%d,%02d ₽".format(Locale.ROOT, k / 100, k % 100)
fun normalized(name: String) = name.trim().lowercase(Locale.ROOT).replace('ё', 'е').replace(Regex("\\s+"), " ")
fun key(name: String, unit: String) = "${normalized(name)}|$unit"
fun merge(items: List<BasketItem>, ingredients: List<Ingredient>, portions: Int, base: Int): List<BasketItem> {
    require(portions in 1..100 && base in 1..100)
    val result = items.associateBy { key(it.name,it.unit) }.toMutableMap()
    for (i in ingredients) {
        require(i.amount in 1..1_000_000 && i.unit in listOf("г", "мл", "шт") && i.name.isNotBlank())
        val q = (i.amount * portions + base - 1) / base
        val k = key(i.name, i.unit)
        val old = result[k]
        val sum = (old?.amount ?: 0L) + q
        require(sum <= 10_000_000) { "Слишком большое количество" }
        result[k] = old?.copy(amount = sum, checked = false) ?: BasketItem(i.name.trim(), q, i.unit)
    }
    return result.values.toList()
}
data class Summary(val known: Long, val unknown: Int, val delivery: Long, val budget: Long) {
    val totalKnown get() = known + delivery
    val over get() = totalKnown > budget
    val complete get() = unknown == 0
}
fun summarize(items: List<BasketItem>, budget: Long, delivery: Long): Summary = Summary(items.sumOf { it.cost ?: 0L }, items.count { it.cost == null }, delivery, budget)

val recipes = listOf(
    Recipe("Манная каша", 2, listOf(Ingredient("Молоко",500,"мл"),Ingredient("Манная крупа",50,"г"),Ingredient("Масло сливочное",20,"г"),Ingredient("Сахар",15,"г")), "Нагрейте молоко, всыпьте крупу тонкой струйкой, помешивайте до готовности; добавьте масло."),
    Recipe("Тосты с красной рыбой", 2, listOf(Ingredient("Хлеб",120,"г"),Ingredient("Красная рыба слабосолёная",100,"г"),Ingredient("Масло сливочное",20,"г"),Ingredient("Авокадо",1,"шт")), "Подсушите хлеб, намажьте маслом, добавьте авокадо и рыбу."),
    Recipe("Омлет с помидорами",2,listOf(Ingredient("Яйца",4,"шт"),Ingredient("Молоко",60,"мл"),Ingredient("Помидоры",150,"г"),Ingredient("Масло сливочное",10,"г")), "Взбейте яйца с молоком. Обжарьте помидоры и залейте смесью, готовьте под крышкой."),
    Recipe("Курица с рисом",3,listOf(Ingredient("Куриное филе",450,"г"),Ingredient("Рис",210,"г"),Ingredient("Морковь",100,"г"),Ingredient("Лук",100,"г"),Ingredient("Масло растительное",20,"мл")), "Обжарьте овощи и курицу, добавьте промытый рис и воду, тушите до готовности курицы и риса."),
    Recipe("Гречка с грибами",3,listOf(Ingredient("Гречка",210,"г"),Ingredient("Шампиньоны",300,"г"),Ingredient("Лук",100,"г"),Ingredient("Масло растительное",20,"мл")), "Сварите гречку, отдельно обжарьте грибы с луком, соедините."),
    Recipe("Макароны с томатным соусом",3,listOf(Ingredient("Макароны",250,"г"),Ingredient("Томаты протёртые",300,"г"),Ingredient("Сыр",80,"г"),Ingredient("Масло растительное",15,"мл")), "Отварите макароны, прогрейте томаты с маслом, смешайте и посыпьте сыром."),
    Recipe("Сырники",3,listOf(Ingredient("Творог",400,"г"),Ingredient("Яйца",1,"шт"),Ingredient("Мука",60,"г"),Ingredient("Сахар",20,"г"),Ingredient("Масло растительное",20,"мл")), "Смешайте творог, яйцо, муку и сахар. Сформируйте сырники и обжарьте до готовности."),
    Recipe("Картофельный суп с курицей",4,listOf(Ingredient("Куриное филе",350,"г"),Ingredient("Картофель",500,"г"),Ingredient("Морковь",100,"г"),Ingredient("Лук",100,"г")), "Отварите курицу, добавьте нарезанные овощи, варите до мягкости и полной готовности мяса.")
)
