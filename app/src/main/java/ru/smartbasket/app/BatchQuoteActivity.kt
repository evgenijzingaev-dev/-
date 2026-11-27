package ru.smartbasket.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

class BatchQuoteActivity:Activity(){
    private data class Need(val key:String,val name:String,val unit:String,val amount:Long)
    private data class Quote(val need:Need,val found:VisibleOffer?)
    private lateinit var web:WebView
    private lateinit var status:TextView
    private lateinit var start:Button
    private lateinit var skip:Button
    private val handler=Handler(Looper.getMainLooper())
    private val needs=mutableListOf<Need>()
    private val quotes=mutableListOf<Quote>()
    private var index=0
    private var running=false
    private var generation=0
    private var attempts=0
    private var expected=""
    private var reading=false
    private fun dp(n:Int)=(n*resources.displayMetrics.density+.5f).toInt()
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        try {
            val arr=JSONArray(intent.getStringExtra("needs")?:"[]")
            for(i in 0 until minOf(arr.length(),100)){
                val o=arr.getJSONObject(i)
                val n=Need(o.getString("key"),o.getString("name"),o.getString("unit"),o.getLong("amount"))
                if(n.amount in 1..10_000_000&&n.unit in listOf("г","мл","шт"))needs.add(n)
            }
        }catch(_:Exception){finish();return}
        if(needs.isEmpty()){finish();return}
        val layout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(244,247,244))}
        status=TextView(this).apply{setPadding(dp(12),dp(10),dp(12),dp(10));textSize=16f;text="Войди на сайте при необходимости, выбери адрес доставки. Затем запусти подбор. Товары пока не добавляются."}
        layout.addView(status)
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        start=Button(this).apply{text="Подобрать все цены";isAllCaps=false;setOnClickListener{if(!running){running=true;isEnabled=false;index=0;quotes.clear();next()}}}
        skip=Button(this).apply{text="Пропустить";isAllCaps=false;isEnabled=false;setOnClickListener{if(running)complete(null)}}
        row.addView(start,LinearLayout.LayoutParams(0,dp(54),2f))
        row.addView(skip,LinearLayout.LayoutParams(0,dp(54),1f))
        layout.addView(row)
        web=WebView(this)
        web.settings.apply{javaScriptEnabled=true;domStorageEnabled=true;allowFileAccess=false;allowContentAccess=false;mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW;setSupportMultipleWindows(false);javaScriptCanOpenWindowsAutomatically=false}
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false)
        web.webChromeClient=WebChromeClient()
        web.webViewClient=object:WebViewClient(){
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean{
                if(!request.isForMainFrame)return false
                if(officialStoreUrl(request.url))return false
                status.text="Внешняя ссылка заблокирована."
                return true
            }
            override fun onPageFinished(view:WebView,url:String){
                CookieManager.getInstance().flush()
                if(running&&sameCategory(url,expected)&&!reading)queueRead(generation,1800)
            }
            override fun onReceivedSslError(view:WebView,handler:SslErrorHandler,error:SslError){handler.cancel();status.text="Ошибка защищённого соединения. Не продолжай вход."}
        }
        layout.addView(web,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(layout)
        web.loadUrl("https://5ka.ru/catalog/")
    }
    private fun sameCategory(a:String,b:String):Boolean{
        val x=Uri.parse(a);val y=Uri.parse(b)
        return officialStoreUrl(x)&&x.host in listOf("5ka.ru","www.5ka.ru")&&x.path.orEmpty().trimEnd('/')==y.path.orEmpty().trimEnd('/')
    }
    private fun next(){
        generation++
        handler.removeCallbacksAndMessages(null)
        reading=false
        if(index>=needs.size){running=false;skip.isEnabled=false;review();return}
        val n=needs[index];attempts=0;expected=storeCategory(n.name)
        status.text="Подбор "+(index+1)+"/"+needs.size+" · "+n.name+" · нужно "+n.amount+" "+n.unit
        skip.isEnabled=true
        val g=generation
        web.loadUrl(expected)
        handler.postDelayed({if(running&&generation==g)complete(null)},18000)
        queueRead(g,4000)
    }
    private fun queueRead(g:Int,delay:Long){
        handler.postDelayed({if(running&&generation==g)read(g)},delay)
    }
    private fun read(g:Int){
        if(reading||!running||generation!=g)return
        if(!sameCategory(web.url.orEmpty(),expected)){queueRead(g,2500);return}
        reading=true;attempts++
        val n=needs[index]
        web.evaluateJavascript(catalogScript){raw->
            if(!running||generation!=g||isFinishing||isDestroyed)return@evaluateJavascript
            reading=false
            if(!sameCategory(web.url.orEmpty(),expected)){queueRead(g,2500);return@evaluateJavascript}
            val match=visibleOffers(raw,n.name,n.unit,n.amount).firstOrNull()
            if(match!=null){complete(match)}
            else if(attempts<3){queueRead(g,2200)}
            else complete(null)
        }
    }
    private fun complete(v:VisibleOffer?){
        if(!running||index>=needs.size)return
        quotes.add(Quote(needs[index],v));index++;next()
    }
    private fun review(){
        val known=quotes.filter{it.found!=null}
        val unknown=quotes.size-known.size
        val subtotal=known.sumOf{it.found!!.offer.total(it.need.amount)}
        val budget=intent.getLongExtra("budget",0)
        val delivery=intent.getLongExtra("delivery",0)
        val other=intent.getLongExtra("other",0)
        val lines=quotes.joinToString("\n\n"){q->
            val f=q.found
            if(f==null)q.need.name+": не найдено" else q.need.name+": "+f.offer.description+"\n"+f.offer.packages(q.need.amount)+" уп. = "+rub(f.offer.total(q.need.amount))
        }
        status.text="Результат подбора: "+known.size+"/"+quotes.size
        val total=subtotal+delivery+other
        val message="Известные расходы с доставкой: "+rub(total)+"\nБюджет: "+rub(budget)+"\nНе найдено: "+unknown+"\n"+(if(total>budget)"Известные расходы выше бюджета.\n" else if(unknown>0)"Бюджет ещё не подтверждён.\n" else "Проверь реальные цены в магазине.\n")+"\n"+lines+"\n\nПеренос сохраняет только локальные цены. Товары в магазине не добавлены."
        val d=AlertDialog.Builder(this).setTitle("Проверь предложенные товары").setMessage(message).setNegativeButton("Закрыть"){_,_->finish()}
        if(known.isNotEmpty())d.setPositiveButton("Перенести цены"){_,_->
            val a=JSONArray()
            for(q in quotes){val f=q.found;a.put(JSONObject().put("key",q.need.key).put("pack",f?.offer?.pack?:0L).put("price",f?.offer?.price?:-1L))}
            setResult(RESULT_OK,Intent().putExtra("quotes",a.toString()));finish()
        }
        d.show()
    }
    override fun onDestroy(){running=false;generation++;handler.removeCallbacksAndMessages(null);if(::web.isInitialized){web.stopLoading();web.destroy()};super.onDestroy()}
}
