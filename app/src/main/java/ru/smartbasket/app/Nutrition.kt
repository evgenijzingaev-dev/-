package ru.smartbasket.app

import java.util.Locale

data class Nutrition(val kcal:Double,val protein:Double,val fat:Double,val carbs:Double){
    operator fun plus(o:Nutrition)=Nutrition(kcal+o.kcal,protein+o.protein,fat+o.fat,carbs+o.carbs)
    operator fun times(f:Double)=Nutrition(kcal*f,protein*f,fat*f,carbs*f)
    fun valid()=listOf(kcal,protein,fat,carbs).all{it.isFinite()&&it>=0}&&kcal<=1000&&protein<=100&&fat<=100&&carbs<=100&&protein+fat+carbs<=105
    fun label()=String.format(Locale("ru","RU"),"%.0f ккал · Б %.1f · Ж %.1f · У %.1f г",kcal,protein,fat,carbs)
}
data class ProductNutrition(val value:Nutrition,val basis:String="г")
/** Parse only explicitly labelled nutrition section; never infer from unrelated page numbers. */
fun parseNutrition(text:String):ProductNutrition?{
    val header=Regex("(?iu)пищевая\\s+ценность\\s+на\\s+100\\s*(мл|г)(?!\\p{L})").find(text)?:return null
    val body=text.substring(header.range.last+1).take(700).trimStart()
    val section=body.split(Regex("(?iu)\\b(?:состав|описание|характеристики|срок хранения)\\b"),limit=2).first()
    val names=listOf("белки","жиры","углеводы","ккал")
    val number="([0-9]{1,4}(?:[.,][0-9]{1,3})?)"
    fun read(label:String):Double?{
        val before=Regex("(?iu)(?<![\\d.,])"+number+"\\s*(?:г\\s+)?"+label+"(?!\\p{L})")
        val after=Regex("(?iu)"+label+"\\s*[:–-]?\\s*"+number+"(?![\\d.,])")
        // Product cards normally render value before label. Use label-first only for an entirely label-first section.
        val pattern=if(section.startsWith("белки",true))after else before
        return pattern.findAll(section).mapNotNull{it.groupValues[1].replace(',','.').toDoubleOrNull()}.toList().singleOrNull()
    }
    val values=names.map{read(it)?:return null}
    val n=Nutrition(values[3],values[0],values[1],values[2])
    return ProductNutrition(n,header.groupValues[1].lowercase(Locale.ROOT)).takeIf{n.valid()}
}
private data class Reference(val value:Nutrition,val gramsPerUnit:Double=1.0)
private fun ref(k:Double,p:Double,f:Double,c:Double,g:Double=1.0)=Reference(Nutrition(k,p,f,c),g)
// Generic raw-food reference approximations, not measurements or branded product claims.
private val reference=mapOf(
    "молоко" to ref(52.0,3.0,2.5,4.7,1.03),
    "манная крупа" to ref(333.0,10.3,1.0,70.6),
    "масло сливочное" to ref(748.0,0.5,82.5,0.8),
    "сахар" to ref(399.0,0.0,0.0,99.8),
    "хлеб" to ref(265.0,8.0,3.0,49.0),
    "красная рыба слабосоленая" to ref(200.0,21.0,13.0,0.0),
    "авокадо" to ref(160.0,2.0,14.7,8.5,130.0),
    "яйца" to ref(143.0,12.6,9.5,0.7,50.0),
    "помидоры" to ref(18.0,0.9,0.2,3.9),
    "куриное филе" to ref(120.0,22.5,2.6,0.0),
    "рис" to ref(350.0,7.0,0.7,79.0),
    "морковь" to ref(41.0,0.9,0.2,9.6),
    "лук" to ref(40.0,1.1,0.1,9.3),
    "масло растительное" to ref(884.0,0.0,100.0,0.0,0.92),
    "гречка" to ref(343.0,13.3,3.4,71.5),
    "шампиньоны" to ref(22.0,3.1,0.3,3.3),
    "макароны" to ref(350.0,12.0,1.5,72.0),
    "томаты протертые" to ref(30.0,1.5,0.2,5.0),
    "сыр" to ref(350.0,25.0,27.0,1.0),
    "творог" to ref(121.0,17.0,5.0,1.8),
    "мука" to ref(334.0,10.3,1.1,70.6),
    "картофель" to ref(77.0,2.0,0.1,17.0)
)
data class NutritionLine(val name:String,val total:Nutrition?,val estimated:Boolean)
data class NutritionReport(val lines:List<NutritionLine>){
    val total get()=lines.mapNotNull{it.total}.fold(Nutrition(0.0,0.0,0.0,0.0)){a,b->a+b}
    val missing get()=lines.count{it.total==null}
    val estimated get()=lines.count{it.estimated&&it.total!=null}
    fun summary():String{
        if(lines.isEmpty()||missing==lines.size)return "Недостаточно данных о КБЖУ"
        val prefix=if(missing>0)"Частичный итог: " else if(estimated>0)"Примерно: " else ""
        return prefix+total.label()+"\nДанные карточек: "+(lines.size-missing-estimated)+" · оценка: "+estimated+" · нет данных: "+missing
    }
}
fun nutritionLine(name:String,amount:Double,unit:String,product:ProductNutrition?):NutritionLine{
    val r=reference[normalized(name)]
    if(!amount.isFinite()||amount<=0)return NutritionLine(name,null,false)
    if(product!=null&&product.basis==unit&&unit in listOf("г","мл"))return NutritionLine(name,product.value*(amount/100),false)
    if(product!=null&&product.basis=="г"&&unit!="г"&&r!=null&&r.gramsPerUnit!=1.0)
        return NutritionLine(name,product.value*(amount*r.gramsPerUnit/100),true)
    if(r!=null&&(unit=="г"||r.gramsPerUnit!=1.0&&unit in listOf("мл","шт")))
        return NutritionLine(name,r.value*(amount*(if(unit=="г")1.0 else r.gramsPerUnit)/100),true)
    return NutritionLine(name,null,false)
}
fun basketNutrition(items:List<BasketItem>,lookup:(String)->ProductNutrition?)=NutritionReport(items.map{
    nutritionLine(it.name,it.amount.toDouble(),it.unit,lookup(it.storeUrl))
})
fun recipeNutrition(recipe:Recipe,items:List<BasketItem>,lookup:(String)->ProductNutrition?):NutritionReport{
    val selected=items.associateBy{key(it.name,it.unit)}
    return NutritionReport(recipe.ingredients.map{
        nutritionLine(it.name,it.amount.toDouble()/recipe.servings,it.unit,lookup(selected[key(it.name,it.unit)]?.storeUrl.orEmpty()))
    })
}
