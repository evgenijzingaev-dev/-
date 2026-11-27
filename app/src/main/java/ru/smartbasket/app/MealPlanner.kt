package ru.smartbasket.app

import java.math.BigDecimal

data class Meal(val day:Int,val kind:String,val recipe:Recipe,val sizePercent:Int=100)
data class MealPlan(val meals:List<Meal>,val portions:Int,val budget:Long) {
    fun ingredients():List<BasketItem> = meals.fold(emptyList()) { items, meal ->
        val denominator=meal.recipe.servings*100L
        val scaled=meal.recipe.ingredients.map{it.copy(amount=(it.amount*portions*meal.sizePercent+denominator-1)/denominator)}
        merge(items,scaled,1,1)
    }
}
data class PlanRequest(val days:Int,val portions:Int,val budget:Long,val kinds:Set<String>,val dishes:List<Recipe>,val kcalTarget:Int?=null,val kcalCeiling:Boolean=false)
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
    val cal=Regex("(?iu)(до\\s+)?(\\d{3,4}|\\d\\s\\d{3})\\s*(?:ккал|калори\\p{L}*)").find(s)
    val target=cal?.groupValues?.get(2)?.replace(" ","")?.toIntOrNull()
    if(cal!=null)require(target!=null&&target in 100..6000){"Укажи калорийность от 100 до 6000 ккал для выбранных приёмов пищи."}
    return PlanRequest(days,people,budget,kinds,dishes,target,cal?.groupValues?.get(1)?.isNotBlank()==true)
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


data class PricedMealPlan(val plan:MealPlan,val items:List<BasketItem>,val total:Long)

/** Bounded search over recipes, pricing combined ingredients as whole packages. */
fun buildCatalogPlan(r:PlanRequest, entries:List<CatalogEntry>, delivery:Long=0L,
                     home:List<BasketItem> = emptyList(), nutrition:(String)->ProductNutrition? = {null}):PricedMealPlan? {
    if(entries.isEmpty() || delivery>r.budget)return null
    val stock=home.associateBy{key(it.name,it.unit)}
    val cache=mutableMapOf<Pair<String,Long>,CatalogMatch?>()
    val candidates=mutableMapOf<String,List<CatalogMatch>>()
    fun priced(meals:List<Meal>):PricedMealPlan? {
        val plan=MealPlan(meals,r.portions,r.budget)
        val items=plan.ingredients().map { raw->
            val k=key(raw.name,raw.unit)
            val item=raw.copy(atHome=stock[k]?.atHome?:0L)
            if(item.need==0L)item else {
                val match=cache.getOrPut(k to item.need){
                    candidates.getOrPut(k){catalogMatches(entries,item).toList()}.minByOrNull{it.offer.total(item.need)}
                } ?: return null
                item.copy(pack=match.offer.pack,price=match.offer.price,storeName=match.offer.description,storeUrl=match.entry.url)
            }
        }
        val summary=summarize(items,r.budget,delivery)
        return if(summary.complete&&!summary.over)PricedMealPlan(plan,items,summary.totalKnown) else null
    }
    val target=r.kcalTarget
    val slots=if(r.dishes.isNotEmpty())r.dishes.size else r.kinds.size
    val slotOptions=if(r.dishes.isNotEmpty())r.dishes.map{"Блюдо" to listOf(it)} else r.kinds.map{kind->
        kind to when(kind){"Завтрак"->breakfast;"Обед"->lunch;else->dinner}
    }
    fun dayReports(state:PricedMealPlan)=state.plan.meals.groupBy{it.day}.values.map{meals->
        meals.size to NutritionReport(meals.flatMap{mealNutrition(it,state.items,nutrition).lines})
    }
    fun score(state:PricedMealPlan):Double{
        if(target==null)return 0.0
        return dayReports(state).sumOf{(count,report)->
            val expected=target.toDouble()*count/slots
            val delta=kotlin.math.abs(report.total.kcal-expected)
            if(report.missing>0)100000.0+delta else delta
        }
    }
    var states=listOf(PricedMealPlan(MealPlan(emptyList(),r.portions,r.budget),emptyList(),delivery))
    for(day in 1..r.days)for((kind,pool) in slotOptions){
        val sizes=if(target==null)listOf(100) else listOf(75,100,125,150,175,200)
        val next=states.flatMap{state->pool.flatMap{recipe->sizes.mapNotNull{size->
            priced(state.plan.meals+Meal(day,kind,recipe,size))
        }}}
        if(next.isEmpty())return null
        val eligible=if(target!=null&&r.kcalCeiling)next.filter{state->dayReports(state).all{(_,report)->report.missing==0&&report.total.kcal<=target}}else next
        if(eligible.isEmpty())return null
        states=eligible.map{state->state to score(state)}.sortedWith(compareBy<Pair<PricedMealPlan,Double>>{it.second}.thenBy{
            val same=it.first.plan.meals.filter{meal->meal.kind==kind}.map{meal->meal.recipe.name}
            if(same.size>1&&same.last()==same[same.lastIndex-1])1 else 0
        }.thenBy{it.first.total}).take(if(target==null)6 else 12).map{it.first}
    }
    return states.firstOrNull()
}

fun mealNutrition(meal:Meal,items:List<BasketItem>,lookup:(String)->ProductNutrition?):NutritionReport =
    NutritionReport(recipeNutrition(meal.recipe,items,lookup).lines.map{line->line.copy(total=line.total?.times(meal.sizePercent/100.0))})

fun mealTitle(meal:Meal):String=meal.recipe.name+(if(meal.sizePercent==100)"" else " · "+meal.sizePercent+"% стандартной порции")

fun calorieGoalSummary(plan:MealPlan,items:List<BasketItem>,target:Int,ceiling:Boolean,lookup:(String)->ProductNutrition?):String{
    val days=plan.meals.groupBy{it.day}.values.map{meals->NutritionReport(meals.flatMap{mealNutrition(it,items,lookup).lines})}
    if(days.isEmpty()||days.any{it.missing>0})return "Цель калорийности не подтверждена: не хватает данных."
    val reached=days.all{if(ceiling)it.total.kcal<=target else kotlin.math.abs(it.total.kcal-target)<=target*0.1}
    val range=days.map{it.total.kcal.toInt()}
    return (if(ceiling)"Ограничение: до " else "Цель: около ")+target+" ккал на человека в день.\n"+
        "Расчёт: "+range.minOrNull()+"–"+range.maxOrNull()+" ккал/день. "+
        (if(reached)"По расчёту цель достигнута." else "Цель не достигнута: ближайший найденный вариант.")+
        (if(days.any{it.estimated>0})" Есть приблизительные значения." else "")+
        " Не персональная норма питания."
}
