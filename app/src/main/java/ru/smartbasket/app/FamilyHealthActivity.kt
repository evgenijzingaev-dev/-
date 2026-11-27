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

class FamilyHealthActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val permissions = setOf(HealthPermission.getReadPermission(StepsRecord::class), HealthPermission.getReadPermission(WeightRecord::class))
    private lateinit var status: TextView
    private lateinit var profiles: FamilyProfiles
    private lateinit var selector: Spinner
    private lateinit var importButton: Button
    private var memberId: String? = null
    private var members = emptyList<FamilyMember>()
    private val request = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        val id = memberId
        if (id != null) read(id) else reset("Выбери профиль и повтори импорт.")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profiles = FamilyProfiles(this)
        members = profiles.read().filter { it.age >= 18 }
        memberId = savedInstanceState?.getString("member_id")
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        panel.addView(TextView(this).apply { text = "Импортируй только собственные данные Health Connect. Выбери свой взрослый профиль." })
        selector = Spinner(this).apply {
            adapter = ArrayAdapter(this@FamilyHealthActivity, android.R.layout.simple_spinner_dropdown_item, members.map { it.name })
            val index = members.indexOfFirst { it.id == memberId }
            if (index >= 0) setSelection(index)
        }
        panel.addView(selector)
        status = TextView(this)
        panel.addView(status)
        importButton = Button(this).apply {
            text = "Прочитать мои данные"
            isEnabled = members.isNotEmpty() && memberId == null
            setOnClickListener {
                val id = members.getOrNull(selector.selectedItemPosition)?.id ?: return@setOnClickListener
                if (HealthConnectClient.getSdkStatus(this@FamilyHealthActivity) != HealthConnectClient.SDK_AVAILABLE) {
                    status.text = "Health Connect недоступен. Установи или обнови его либо введи данные вручную."
                    return@setOnClickListener
                }
                memberId = id
                selector.isEnabled = false
                importButton.isEnabled = false
                status.text = "Проверяем разрешения…"
                try { request.launch(permissions) } catch (e: Exception) { reset("Не удалось открыть разрешения Health Connect.") }
            }
        }
        selector.isEnabled = memberId == null
        panel.addView(importButton)
        panel.addView(Button(this).apply {
            text = "Как используются данные"
            setOnClickListener { startActivity(android.content.Intent(this@FamilyHealthActivity, HealthPrivacyActivity::class.java)) }
        })
        setContentView(ScrollView(this).apply { addView(panel) })
        if (memberId != null) reset("Если импорт прервался, запусти его ещё раз.")
    }
    private fun reset(message: String) {
        memberId = null
        status.text = message
        selector.isEnabled = true
        importButton.isEnabled = members.isNotEmpty()
    }
    private fun read(id: String) {
        scope.launch {
            try {
                val member = profiles.read().firstOrNull { it.id == id && it.age >= 18 }
                if (member == null) { reset("Профиль недоступен."); return@launch }
                status.text = "Читаем разрешённые данные…"
                val client = HealthConnectClient.getOrCreate(this@FamilyHealthActivity)
                val granted = client.permissionController.getGrantedPermissions()
                val now = Instant.now()
                val steps = if (HealthPermission.getReadPermission(StepsRecord::class) in granted)
                    client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(now.minus(7, ChronoUnit.DAYS), now)))[StepsRecord.COUNT_TOTAL] else null
                val weight = if (HealthPermission.getReadPermission(WeightRecord::class) in granted)
                    client.readRecords(ReadRecordsRequest(WeightRecord::class, TimeRangeFilter.between(now.minus(30, ChronoUnit.DAYS), now), ascendingOrder = false, pageSize = 1)).records.firstOrNull()?.weight?.inKilograms?.takeIf { it.isFinite() && it in 1.0..350.0 } else null
                if (weight == null && steps == null) { reset("Нет разрешённых данных за выбранный период."); return@launch }
                val description = "Вес: " + (weight?.let { String.format(java.util.Locale.US, "%.1f кг", it) } ?: "нет данных") + "; шаги за 7 дней: " + (steps?.toString() ?: "нет данных")
                status.text = description
                android.app.AlertDialog.Builder(this@FamilyHealthActivity)
                    .setTitle("Сохранить в профиль «" + member.name + "»?")
                    .setMessage(description)
                    .setNegativeButton("Нет") { _, _ -> reset("Импорт отменён.") }
                    .setPositiveButton("Сохранить") { _, _ ->
                        val current = profiles.read()
                        if (current.none { it.id == id && it.age >= 18 }) {
                            reset("Профиль изменён или удалён. Импорт отменён.")
                        } else {
                            profiles.save(current.map { if (it.id == id && it.age >= 18) it.copy(weight = weight ?: it.weight, activity = steps?.let { v -> "Health Connect: " + v + " шагов за 7 дней" } ?: it.activity) else it })
                            reset("Данные сохранены локально.")
                        }
                    }
                    .setOnCancelListener { reset("Импорт отменён.") }.show()
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { reset("Не удалось прочитать данные. Доступен ручной ввод.") }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("member_id", memberId)
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
