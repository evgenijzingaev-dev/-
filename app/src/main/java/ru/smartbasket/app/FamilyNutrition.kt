package ru.smartbasket.app

import kotlin.math.roundToInt

/** Rough adult maintenance estimate, not a prescription or a weight-loss target. */
object FamilyNutrition {
    data class Macros(val protein:Int,val fat:Int,val carbs:Int)
    fun macros(kcal:Int):Macros? {
        if(kcal !in 1200..4500)return null
        return Macros((kcal*0.20/4).roundToInt(),(kcal*0.30/9).roundToInt(),(kcal*0.50/4).roundToInt())
    }
    fun maintenance(age:Int,height:Int?,weight:Double?,sex:String,activity:String):Int? {
        if(age !in 18..65 || height==null || height !in 130..220 || weight==null ||
            !weight.isFinite() || weight !in 40.0..200.0) return null
        val offset=when(sex){"Мужской"->5;"Женский"->-161;else->return null}
        val factor=when(activity){"Низкая"->1.2;"Лёгкая"->1.375;"Умеренная"->1.55;"Высокая"->1.725;else->return null}
        val bmi=weight/((height/100.0)*(height/100.0))
        if(bmi !in 18.5..40.0)return null
        return ((10*weight+6.25*height-5*age+offset)*factor).roundToInt()
    }
}
