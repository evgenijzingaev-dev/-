package ru.smartbasket.app
import org.junit.Assert.*
import org.junit.Test
class FamilyNutritionTest {
    @Test fun adultEstimate(){assertEquals(2759,FamilyNutrition.maintenance(30,180,80.0,"Мужской","Умеренная"))}
    @Test fun macrosExample(){assertEquals(FamilyNutrition.Macros(100,67,250),FamilyNutrition.macros(2000))}
    @Test fun noChildTarget(){assertNull(FamilyNutrition.maintenance(12,150,45.0,"Женский","Умеренная"))}
    @Test fun missingData(){assertNull(FamilyNutrition.maintenance(30,null,80.0,"Мужской","Умеренная"))}
    @Test fun unknownActivity(){assertNull(FamilyNutrition.maintenance(30,180,80.0,"Мужской",""))}
}
