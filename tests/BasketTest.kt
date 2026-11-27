package ru.smartbasket.app
fun main() {
    check(money("129,99") == 12999L); check(money("-1") == null); check(money("1.001") == null)
    val a = merge(emptyList(), recipes[0].ingredients, 2, 2)
    val b = merge(a, recipes[1].ingredients, 2, 2)
    check(b.single { it.name == "Масло сливочное" }.amount == 40L)
    val item = BasketItem("Молоко", 1500, "мл", 100, 900, 10999)
    check(item.packs == 2L && item.cost == 21998L)
    check(item.copy(atHome = 1500, pack = 0, price = null).cost == 0L)
    val sum = summarize(listOf(item, BasketItem("Хлеб",120,"г")), 20000, 1000)
    check(sum.over && !sum.complete && sum.totalKnown == 22998L)
    check(merge(emptyList(), listOf(Ingredient("Яйца",1,"шт")),3,2).single().amount == 2L)
    check(merge(listOf(BasketItem("МЁД",10,"г",pack=100,price=7000,checked=true)), listOf(Ingredient("мед",20,"г")),1,1).single().let { it.amount==30L && it.price==7000L && !it.checked })
    println("8 groups of checks passed")
}
