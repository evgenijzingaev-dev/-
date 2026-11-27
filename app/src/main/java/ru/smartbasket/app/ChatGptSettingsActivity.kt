package ru.smartbasket.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.*

class ChatGptSettingsActivity:Activity(){
    private val connection by lazy{ChatGptConnection(this)}
    private lateinit var status:TextView
    private lateinit var connect:Button
    private lateinit var test:Button
    private lateinit var disconnect:Button
    private var busy=false
    private fun alive()=!isDestroyed&&!isFinishing
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24)}
        root.addView(TextView(this).apply{text="ChatGPT · экспериментальное подключение";textSize=22f})
        root.addView(TextView(this).apply{text="Вход через официальный браузер. Использование плана ChatGPT зависит от разрешений и лимитов. Тест связи не отправляет корзину. При запросе из главного экрана ChatGPT может читать необходимые данные корзины и каталога. Сеанс продлевается при запросе автоматически, пока разрешение действительно. Старые подключения могут потребовать повторный вход.";textSize=16f})
        status=TextView(this).apply{textSize=17f};root.addView(status)
        connect=Button(this).apply{text="Continue with ChatGPT";isAllCaps=false;setOnClickListener{signIn()}}
        test=Button(this).apply{text="Проверить связь с ChatGPT";isAllCaps=false;setOnClickListener{test()}}
        disconnect=Button(this).apply{text="Отключить локальный аккаунт";isAllCaps=false;setOnClickListener{signOut()}}
        root.addView(connect);root.addView(test);root.addView(disconnect)
        root.addView(Button(this).apply{text="Управление лимитами ChatGPT";isAllCaps=false;setOnClickListener{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://chatgpt.com/settings/usage")))}})
        root.addView(Button(this).apply{text="Назад";isAllCaps=false;setOnClickListener{finish()}})
        setContentView(root);refresh()
    }
    private fun refresh(){
        val connected=connection.connected()
        status.text=if(connected)"ChatGPT: "+connection.accountLabel().ifBlank{"сохранённый аккаунт"}+" · проверь связь" else "Нет активного подключения"
        connect.isEnabled=!busy;test.isEnabled=!busy&&connected;disconnect.isEnabled=!busy
    }
    private fun work(message:String,task:()->String){
        if(busy)return
        busy=true;refresh();status.text=message
        Thread{
            val result=try{task()}catch(e:Exception){if(e is IllegalStateException||e is IllegalArgumentException)e.message?.takeIf{it.length<180&&!it.contains("http://")&&!it.contains("https://")}?:"Ошибка подключения" else "Не удалось подключиться: "+e.javaClass.simpleName}
            runOnUiThread{if(alive()){busy=false;refresh();status.text=result}}
        }.start()
    }
    private fun signIn()=work("Ожидаем вход в браузере… Вернись в приложение после разрешения."){
        connection.signIn{url->runOnUiThread{if(alive())startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}}
        "Вход подтверждён. Теперь проверь связь."
    }
    private fun signOut()=work("Отключаем сеанс…"){
        if(connection.disconnect())"Сеанс отозван, локальные токены удалены."
        else "Локальный вход отключён. Отзыв сеанса не подтверждён; проверь доступ в настройках ChatGPT."
    }
    private fun test()=work("Проверяем доступные модели и ответ…"){
        val model=connection.models().firstOrNull()?:error("Нет доступных моделей")
        model.second+": "+connection.test(model.first)
    }
}
