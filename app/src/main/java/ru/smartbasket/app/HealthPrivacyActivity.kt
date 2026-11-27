package ru.smartbasket.app

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Public entry point: displays information only; never reads health records. */
class HealthPrivacyActivity: Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p,p,p,p)
        }
        panel.addView(TextView(this).apply {
            textSize = 18f
            text = "Конфиденциальность Health Connect"
        })
        panel.addView(TextView(this).apply {
            textSize = 16f
            text = """SmartBasket запрашивает только чтение веса и количества шагов для рекомендаций по питанию взрослого владельца устройства.

Чтение запускается вручную. Вес и сумма шагов за 7 дней сохраняются в выбранном взрослом профиле только после подтверждения. Приложение не записывает данные в Health Connect и не запрашивает фоновый доступ.

Если в разделе «Моя семья» отдельно включена передача семейных данных в ChatGPT, сохранённые параметры профиля могут передаваться подключённому сервису OpenAI при запросах. Имя и внутренний идентификатор профиля исключаются, но данные не являются гарантированно анонимными.

Передачу можно отключить в настройках семьи; сохранённые данные можно удалить вместе с профилем или изменить вручную. Разрешения на чтение можно отозвать в системных настройках Health Connect. Отзыв разрешений не удаляет ранее сохранённую локальную копию."""
        })
        panel.addView(Button(this).apply { text = "Закрыть"; setOnClickListener { finish() } })
        setContentView(ScrollView(this).apply { addView(panel) })
    }
}
