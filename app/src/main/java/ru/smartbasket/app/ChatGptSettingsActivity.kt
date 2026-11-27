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
    private lateinit var reconnect:Button
    private lateinit var network:Button
    private lateinit var dns:CheckBox
    private var busy=false
    private fun alive()=!isDestroyed&&!isFinishing
    private val green=android.graphics.Color.rgb(25,104,72)
    private val ink=android.graphics.Color.rgb(27,40,34)
    private val gray=android.graphics.Color.rgb(88,105,96)
    private val backgroundColor=android.graphics.Color.rgb(246,247,244)
    private val soft=android.graphics.Color.rgb(231,239,232)
    private fun dp(n:Int)=(n*resources.displayMetrics.density+.5f).toInt()
    private fun rounded(color:Int)=android.graphics.drawable.GradientDrawable().apply{
        setColor(color);cornerRadius=dp(16).toFloat()
    }
    private fun action(title:String, primary:Boolean=false, click:()->Unit)=Button(this).apply{
        text=title;isAllCaps=false;textSize=16f
        setTextColor(if(primary)android.graphics.Color.WHITE else green)
        background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x18000000),rounded(if(primary)green else soft),null)
        stateListAnimator=null
        minHeight=dp(52);minimumHeight=dp(52)
        setPadding(dp(12),dp(8),dp(12),dp(8))
        setOnClickListener{click()}
    }
    private fun LinearLayout.addAction(button:Button){
        addView(button,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
    }
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        window.statusBarColor=backgroundColor
        window.navigationBarColor=backgroundColor
        window.decorView.systemUiVisibility=android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val scroll=ScrollView(this).apply{setBackgroundColor(backgroundColor);isFillViewport=true}
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(12),dp(16),dp(16))}
        scroll.addView(root)
        val header=LinearLayout(this).apply{gravity=android.view.Gravity.CENTER_VERTICAL}
        header.addView(action("‹"){finish()}.apply{contentDescription="Назад"},LinearLayout.LayoutParams(dp(52),-2))
        header.addView(TextView(this).apply{
            text="Подключение ChatGPT";textSize=23f;setTextColor(ink)
            setTypeface(null,android.graphics.Typeface.BOLD);setPadding(dp(12),0,0,0)
        },LinearLayout.LayoutParams(0,-2,1f))
        root.addView(header)
        val card=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;background=rounded(android.graphics.Color.WHITE)
            setPadding(dp(16),dp(16),dp(16),dp(16))
        }
        root.addView(card,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(12)})
        status=TextView(this).apply{textSize=18f;setTextColor(ink);accessibilityLiveRegion=android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE}
        card.addView(status)
        card.addView(TextView(this).apply{
            text="Вход открывается в браузере. При запросе OpenAI получает его текст и необходимые данные корзины и каталога."
            textSize=15f;setTextColor(gray);setPadding(0,dp(8),0,dp(6))
        })
        connect=action("Continue with ChatGPT",true){signIn()}
        test=action("Проверить связь",true){test()}
        disconnect=action("Отключить аккаунт"){signOut()}
        reconnect=action("Подключить заново"){signIn()}
        card.addAction(connect);card.addAction(test);card.addAction(reconnect);card.addAction(disconnect)
        dns=CheckBox(this).apply{
            text="Резервный защищённый DNS (Cloudflare)"
            isChecked=connection.encryptedDnsEnabled()
            setOnCheckedChangeListener{_,checked->connection.setEncryptedDnsEnabled(checked)}
        }
        card.addView(dns)
        card.addView(TextView(this).apply{
            text="Если системный DNS не найдёт сервер OpenAI, только имена auth.openai.com и api.openai.com будут запрошены через HTTPS у Cloudflare. Токены и содержимое запросов туда не передаются. VPN и проверка сертификатов остаются без изменений."
            textSize=14f;setTextColor(gray)
        })
        network=action("Проверить сеть без входа"){work("Проверяем серверы OpenAI…"){connection.checkNetwork()}}
        card.addAction(network)
        card.addAction(action("Что передаётся и как расходуется лимит"){
            android.app.AlertDialog.Builder(this).setTitle("Данные и лимиты")
                .setMessage("Проверка связи не отправляет корзину. При запросах из приложения OpenAI получает запрос и необходимые для ответа данные корзины и каталога. Доступ зависит от разрешений и лимита аккаунта. Сеанс обновляется, пока действует разрешение.")
                .setPositiveButton("Закрыть",null).show()
        })
        root.addAction(action("Управление лимитами ChatGPT"){
            try{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://chatgpt.com/settings/usage")))}
            catch(_:android.content.ActivityNotFoundException){status.text="Не найден браузер для открытия настроек."}
        })
        setContentView(scroll);refresh()
    }
    private fun refresh(){
        val connected=connection.connected()
        status.text=if(connected)"Аккаунт подключён: "+connection.accountLabel().ifBlank{"сохранённый аккаунт"} else "ChatGPT не подключён"
        connect.visibility=if(connected)android.view.View.GONE else android.view.View.VISIBLE
        test.visibility=if(connected)android.view.View.VISIBLE else android.view.View.GONE
        disconnect.visibility=test.visibility;reconnect.visibility=test.visibility
        connect.isEnabled=!busy;test.isEnabled=!busy;disconnect.isEnabled=!busy;reconnect.isEnabled=!busy;network.isEnabled=!busy;dns.isEnabled=!busy
    }
    private fun work(message:String,task:()->String){
        if(busy)return
        busy=true;refresh();status.text=message
        Thread{
            val result=try{task()}catch(e:Exception){chatGptFailureMessage(e) ?: if(e is IllegalStateException||e is IllegalArgumentException)e.message?.takeIf{it.length<180&&!it.contains("http://")&&!it.contains("https://")}?:"Ошибка подключения" else "Не удалось подключиться: "+e.javaClass.simpleName}
            runOnUiThread{if(alive()){busy=false;refresh();status.text=result}}
        }.start()
    }
    private fun signIn()=work("Проверяем сеть и открываем вход в браузере…"){
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
