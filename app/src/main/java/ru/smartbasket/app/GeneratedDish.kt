package ru.smartbasket.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Nutrition is per serving, not the entire recipe or the purchased packages. */
data class GeneratedDish(val recipe:Recipe,val report:NutritionReport,val modelEstimates:Int)
private fun positiveInteger(o:JSONObject,k:String,max:Long):Long{
    val x=o.get(k)
    require(x is Number&&x.toDouble().isFinite()&&x.toDouble()==x.toLong().toDouble())
    return x.toLong().also{require(it in 1..max)}
}
private fun value(o:JSONObject):Nutrition=Nutrition(o.getDouble("kcal"),o.getDouble("protein"),o.getDouble("fat"),o.getDouble("carbs"))
private fun nutritionJson(n:Nutrition)=JSONObject().put("kcal",n.kcal).put("protein",n.protein).put("fat",n.fat).put("carbs",n.carbs)
private fun validTotal(n:Nutrition,amount:Long,unit:String):Boolean{
    val grams=if(unit=="шт")amount*1000.0 else amount*1.0
    return listOf(n.kcal,n.protein,n.fat,n.carbs).all{it.isFinite()&&it>=0}&&n.kcal<=grams*10&&n.protein+n.fat+n.carbs<=grams*1.1
}
internal fun parseGeneratedDish(o:JSONObject,lookup:(Ingredient)->ProductNutrition?):GeneratedDish{
    val name=o.getString("name").trim()
    val method=o.getString("method").trim()
    val servings=positiveInteger(o,"servings",20).toInt()
    require(name.length in 1..120&&method.length in 1..5000)
    val arr=o.getJSONArray("ingredients")
    require(arr.length() in 1..40)
    val ingredients=mutableListOf<Ingredient>()
    val lines=mutableListOf<NutritionLine>()
    val seen=mutableSetOf<String>()
    var model=0
    for(i in 0 until arr.length()){
        val row=arr.getJSONObject(i)
        val ingredient=Ingredient(row.getString("name").trim(),positiveInteger(row,"amount",100000),row.getString("unit"))
        require(ingredient.name.length in 1..120&&ingredient.unit in listOf("г","мл","шт")&&seen.add(key(ingredient.name,ingredient.unit)))
        ingredients.add(ingredient)
        val local=nutritionLine(ingredient.name,ingredient.amount.toDouble()/servings,ingredient.unit,lookup(ingredient))
        val estimate=row.optJSONObject("estimated_total")?.let{value(it)}?.takeIf{validTotal(it,ingredient.amount,ingredient.unit)}
        if(local.total!=null)lines.add(local)
        else if(estimate!=null){model++;lines.add(NutritionLine(ingredient.name,estimate*(1.0/servings),true))}
        else lines.add(local)
    }
    return GeneratedDish(Recipe(name,servings,ingredients,method),NutritionReport(lines),model)
}
fun dishNutritionLabel(d:GeneratedDish)=d.report.summary()+" / порция"+(if(d.modelEstimates>0)"\nИз них оценка ChatGPT: "+d.modelEstimates else "")+"\nЗначения до учёта потерь при приготовлении."
internal fun encodeDishes(dishes:List<GeneratedDish>):String{
    val arr=JSONArray()
    dishes.takeLast(60).forEach{d->
        val rows=JSONArray()
        d.recipe.ingredients.forEachIndexed{i,g->
            val line=d.report.lines[i]
            rows.put(JSONObject().put("name",g.name).put("amount",g.amount).put("unit",g.unit)
                .put("per_serving",line.total?.let{nutritionJson(it)}?:JSONObject.NULL).put("estimated",line.estimated))
        }
        arr.put(JSONObject().put("name",d.recipe.name).put("servings",d.recipe.servings).put("method",d.recipe.method)
            .put("ingredients",rows).put("model_estimates",d.modelEstimates))
    }
    return arr.toString()
}
internal fun decodeDishes(raw:String):List<GeneratedDish> = try{
    require(raw.length<=2_000_000)
    val arr=JSONArray(raw)
    (0 until arr.length()).mapNotNull{i->
        try{
            val o=arr.getJSONObject(i)
            val recipe=parseGeneratedDish(o){null}.recipe
            val rows=o.getJSONArray("ingredients")
            val lines=recipe.ingredients.mapIndexed{j,g->
                val row=rows.getJSONObject(j)
                val total=row.optJSONObject("per_serving")?.let{value(it)}?.takeIf{validTotal(it*recipe.servings.toDouble(),g.amount,g.unit)}
                NutritionLine(g.name,total,row.optBoolean("estimated"))
            }
            GeneratedDish(recipe,NutritionReport(lines),o.optInt("model_estimates").coerceIn(0,lines.count{it.estimated&&it.total!=null}))
        }catch(_:Exception){null}
    }.takeLast(60)
}catch(_:Exception){emptyList()}
class GeneratedDishStore(context:Context){
    private val prefs=context.applicationContext.getSharedPreferences("generated_dishes_v1",Context.MODE_PRIVATE)
    fun read()=decodeDishes(prefs.getString("dishes","[]").orEmpty())
    fun write(dishes:List<GeneratedDish>){prefs.edit().putString("dishes",encodeDishes(dishes)).apply()}
}
