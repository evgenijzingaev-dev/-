package ru.smartbasket.app

import java.math.BigDecimal

data class Meal(val day:Int,val kind:String,val recipe:Recipe)
data class MealPlan(val meals:List<Meal>,val portions:Int,val budget:Long) {
    fun ingredients():List<BasketItem> = meals.fold(emptyList()) { items, meal ->
        merge(items,meal.recipe.ingredients,portions,meal.recipe.servings)
    }
}
data class PlanRequest(val days:Int,val portions:Int,val budget:Long,val kinds:Set<String>,val dishes:List<Recipe>)
private val breakfast get()=listOf(recipes[0],recipes[2],recipes[6],recipes[1])
private val lunch get()=listOf(recipes[7],recipes[3],recipes[4],recipes[5])
private val dinner get()=listOf(recipes[3],recipes[4],recipes[5],recipes[7])

fun parseRequest(input:String, defaultDays:Int, defaultPortions:Int, defaultBudget:Long):PlanRequest {
    val s=normalized(input)
    require(s.length<=3000){"Запрос слишком длинный"}
    require(!Regex("(?iu)\\b(без|аллерг\\p{L}*|нельзя|не ем|не едим|исключ\\p{L}*|веган\\p{L}*|вегетариан\\p{L}*)\\b").containsMatchIn(s)) {
        "Ограничения питания пока не распознаются автоматически. Выбери подходящие рецепты вручную."
    }
    val days=when{
        s.contains("недел")->7
        s.contains("сегодня")||s.contains("на завтра")&&!s.contains("завтрак")->1
        else->Regex("(?iu)(\\d+)\\s*(?:дня|дней|день)").find(s)?.groupValues?.get(1)?.toIntOrNull()?:defaultDays
    }
    val people=Regex("(?iu)(\\d+)\\s*(?:порц\\p{L}*|человек\\p{L}*|персон\\p{L}*)").find(s)?.groupValues?.get(1)?.toIntOrNull()?:defaultPortions
    val budgetMatch=Regex("(?iu)(\\d[\\d ]{0,8})(?:[.,](\\d{1,2}))?\\s*(₽|руб\\p{L}*|тыс\\.?|тысяч\\p{L}*)").find(s)
    val budget=budgetMatch?.let{
        val num=BigDecimal(it.groupValues[1].replace(" ","")+(if(it.groupValues[2].isNotEmpty()) "."+it.groupValues[2] else ""))
        val rubles=if(it.groupValues[3].startsWith("тыс"))num.multiply(BigDecimal(1000))else num
        rubles.movePointRight(2).longValueExact()
    }?:defaultBudget
    require(days in 1..14&&people in 1..20&&budget in 0..100_000_000){"Поддерживается от 1 до 14 дней, от 1 до 20 порций, бюджет до 1 млн ₽."}
    val names=listOf(listOf("манн","манк"),listOf("тост","бутерброд"),listOf("омлет"),listOf("куриц с рис","курица с рис","курицу с рис","курицей с рис"),listOf("гречк"),listOf("макарон"),listOf("сырник"),listOf("суп"))
    val dishes=recipes.filterIndexed{i,_->names[i].any{s.contains(it)}}
    val kinds=linkedSetOf<String>()
    if(s.contains("завтрак"))kinds.add("Завтрак")
    if(s.contains("обед"))kinds.add("Обед")
    if(s.contains("ужин"))kinds.add("Ужин")
    val recognized=dishes.isNotEmpty()||kinds.isNotEmpty()||listOf("меню","продукт","корзин","покупк","семь","недел","дня","дней","день","сегодня").any{s.contains(it)}
    require(s.isBlank()||recognized){"Не удалось распознать запрос. Укажи блюдо из каталога или «меню на 3 дня»."}
    if(kinds.isEmpty())kinds.addAll(listOf("Завтрак","Обед","Ужин"))
    return PlanRequest(days,people,budget,kinds,dishes)
}
fun buildPlan(r:PlanRequest):MealPlan {
    val meals=mutableListOf<Meal>()
    if(r.dishes.isNotEmpty()){
        for(day in 1..r.days)for(recipe in r.dishes)meals.add(Meal(day,"Блюдо",recipe))
    }else{
        for(day in 1..r.days)for(kind in r.kinds){
            val pool=when(kind){"Завтрак"->breakfast;"Обед"->lunch;else->dinner}
            val recipe=pool[(day-1)%pool.size]
            meals.add(Meal(day,kind,recipe))
        }
    }
    return MealPlan(meals,r.portions,r.budget)
}
data class ImportedIngredients(val ingredients:List<Ingredient>,val ignored:List<String>)
fun importIngredients(raw:String):ImportedIngredients {
    require(raw.length<=50000){"Список слишком длинный"}
    val entries=mutableListOf<Ingredient>()
    val ignored=mutableListOf<String>()
    val pattern=Regex("(?iu)^(.+?)\\s*[:—–-]?\\s*(\\d+(?:[.,]\\d+)?)\\s*(кг|мл|г|л|шт\\.?|штук\\p{L}*|грамм\\p{L}*|литр\\p{L}*)\\s*[.]?$")
    val lines=raw.lineSequence().map{it.trim().replace("**","").replace(Regex("^\\s*(?:[-•*]|\\d+[.)])\\s+"),"").trim()}.filter{it.isNotEmpty()}.toList()
    require(lines.size<=200){"Слишком много строк"}
    for(line in lines) {
        val p=line.split(';').map(String::trim)
        val m=if(p.size==3)null else pattern.matchEntire(line)
        val name=if(p.size==3)p[0] else m?.groupValues?.get(1)?.trim()?.trimEnd(':','—','–','-')?.trim()
        val amount=if(p.size==3)p[1] else m?.groupValues?.get(2)
        val unit=if(p.size==3)p[2] else m?.groupValues?.get(3)
        val i=try{
            require(!name.isNullOrBlank()&&name.length<=120&&!amount.isNullOrBlank()&&!unit.isNullOrBlank())
            val u=normalized(unit).trimEnd('.')
            val v=BigDecimal(amount.replace(',','.'))
            val base=when {u=="кг"->"г";u=="л"||u.startsWith("литр")->"мл";u.startsWith("грамм")->"г";u.startsWith("шт")||u.startsWith("штук")->"шт";else->u}
            val q=(if(u=="кг"||u=="л"||u.startsWith("литр"))v.multiply(BigDecimal(1000))else v).longValueExact()
            require(q in 1..1_000_000&&base in listOf("г","мл","шт"))
            Ingredient(name,q,base)
        }catch(_:Exception){null}
        if(i!=null)entries.add(i)else ignored.add(line)
    }
    return ImportedIngredients(entries,ignored)
}
