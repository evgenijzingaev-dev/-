package ru.smartbasket.app

import android.os.Bundle
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.*
import java.time.Instant
import java.time.temporal.ChronoUnit

class FamilyHealthActivity:ComponentActivity(){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private val permissions=setOf(HealthPermission.getReadPermission(StepsRecord::class),HealthPermission.getReadPermission(WeightRecord::class))
    private lateinit var status:TextView
    private lateinit var profiles:FamilyProfiles
    private var memberId:String?=null
    private val request=registerForActivityResult(PermissionController.createRequestPermissionResultContract()){read()}
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        profiles=FamilyProfiles(this)
        val members=profiles.read().filter{it.age>=18}
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24)}
        panel.addView(TextView(this).apply{text="Импортируй только собственные данные Health Connect. Выбери свой профиль. Данные других членов семьи вводятся вручную."})
        val selector=Spinner(this).apply{adapter=ArrayAdapter(this@FamilyHealthActivity,android.R.layout.simple_spinner_dropdown_item,members.map{it.name})}
        panel.addView(selector)
        status=TextView(this);panel.addView(status)
        panel.addView(Button(this).apply{text="Прочитать мои данные";isEnabled=members.isNotEmpty();setOnClickListener{
            memberId=members.getOrNull(selector.selectedItemPosition)?.id
            if(HealthConnectClient.getSdkStatus(this@FamilyHealthActivity)!=HealthConnectClient.SDK_AVAILABLE){status.text="Health Connect недоступен. Используй ручной ввод.";return@setOnClickListener}
            request.launch(permissions)
        }})
        setContentView(panel)
    }
    private fun read(){scope.launch{
        try{
            val client=HealthConnectClient.getOrCreate(this@FamilyHealthActivity)
            val granted=client.permissionController.getGrantedPermissions()
            val now=Instant.now()
            val steps=if(HealthPermission.getReadPermission(StepsRecord::class) in granted)
                client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL),TimeRangeFilter.between(now.minus(7,ChronoUnit.DAYS),now)))[StepsRecord.COUNT_TOTAL] else null
            val weight=if(HealthPermission.getReadPermission(WeightRecord::class) in granted)
                client.readRecords(ReadRecordsRequest(WeightRecord::class,TimeRangeFilter.between(now.minus(30,ChronoUnit.DAYS),now),ascendingOrder=false,pageSize=1)).records.firstOrNull()?.weight?.inKilograms else null
            val description="Вес: "+(weight?.let{String.format(java.util.Locale.US,"%.1f кг",it)}?:"нет данных")+"; шаги за 7 дней: "+(steps?.toString()?:"нет данных")
            status.text=description
            if(weight==null && steps==null)return@launch
            android.app.AlertDialog.Builder(this@FamilyHealthActivity).setTitle("Сохранить в выбранный профиль?").setMessage(description).setNegativeButton("Нет",null).setPositiveButton("Сохранить"){_,_->
                val members=profiles.read()
                profiles.save(members.map{if(it.id==memberId && it.age>=18)it.copy(weight=weight?.takeIf{v->v in 1.0..350.0}?:it.weight,activity=steps?.let{v->"Health Connect: "+v+" шагов за 7 дней"}?:it.activity)else it})
                status.text="Данные сохранены локально."
            }.show()
        }catch(e:Exception){status.text="Не удалось прочитать данные. Доступен ручной ввод."}
    }}
    override fun onDestroy(){scope.cancel();super.onDestroy()}
}
