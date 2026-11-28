package ru.smartbasket.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.net.http.SslError
import android.webkit.*
import android.widget.*
import org.json.JSONTokener
import org.json.JSONObject

/** User-triggered, sequential read of selected public product cards, independent of catalog sync. */
class NutritionActivity:Activity(){
    private lateinit var web:WebView
    private lateinit var status:TextView
    private val handler=Handler(Looper.getMainLooper())
    private val cache by lazy{NutritionCache(this)}
    private var urls=emptyList<String>()
    private var index=0
    private var saved=0
    private var generation=0
    private val details by lazy{CatalogDetailsStore(this)}
    private var auto=false
    private var reused=0
    private var withoutLink=0
    private var withNutrition=0
    private var firstDetailsAt=0L
    private var active=true
    private var deadline=0L
    private var reading=false
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        val catalogStore=intent.getStringExtra("catalog_details_store")
        auto=catalogStore!=null
        if(!auto)urls=intent.getStringArrayListExtra("urls").orEmpty().distinct().filter{storeProductUrl(Uri.parse(it))}.take(100)
        val layout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        status=TextView(this).apply{textSize=18f;setPadding(24,20,24,20)}
        layout.addView(status)
        layout.addView(Button(this).apply{text="Остановить и показать результат";isAllCaps=false;setOnClickListener{finishResult()}})
        web=WebView(this)
        web.settings.apply{javaScriptEnabled=true;domStorageEnabled=true;allowFileAccess=false;allowContentAccess=false;mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW;setSupportMultipleWindows(false)}
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false)
        web.webViewClient=object:WebViewClient(){
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean{
                if(!request.isForMainFrame)return false
                return if(auto)!storeProductUrl(request.url) else !officialStoreUrl(request.url)
            }
            override fun onReceivedSslError(view:WebView,handler:SslErrorHandler,error:SslError){handler.cancel();finishResult("Не удалось установить защищённое соединение")}
            override fun onReceivedHttpError(view:WebView,request:WebResourceRequest,response:WebResourceResponse){
                if(request.isForMainFrame&&response.statusCode in listOf(401,403,429))finishResult("Магазин ограничил доступ")
            }
        }
        layout.addView(web,LinearLayout.LayoutParams(-1,0,1f));setContentView(layout)
        if(!auto)next() else {
            status.text="Готовим очередь карточек…"
            Thread{
                val snapshot=LocalCatalog(this).read()?.takeIf{it.store==catalogStore}
                val entries=snapshot?.entries.orEmpty()
                val all=entries.map{it.url}.filter{validTransferUrl(it)}
                    .distinctBy{Uri.parse(it).path.orEmpty().trimEnd('/')}
                var cached=0
                val now=System.currentTimeMillis()
                val pending=all.filter{url->
                    val card=details.read(url)
                    if(card!=null&&now-card.collectedAt in 0L..604_800_000L){cached++;false}else true
                }
                runOnUiThread{
                    if(!active||isFinishing||isDestroyed)return@runOnUiThread
                    reused=cached;withoutLink=entries.count{!validTransferUrl(it.url)}
                    urls=pending
                    if(snapshot==null)finishResult("Сохранённый каталог недоступен") else next()
                }
            }.start()
        }
    }
    private fun next(){
        if(!active)return
        handler.removeCallbacksAndMessages(null);generation++;reading=false;firstDetailsAt=0L
        if(index>=urls.size){finishResult();return}
        status.text=if(auto)"Карточки товаров: "+(index+1)+" / "+urls.size+" · сохранено "+saved+" · из них с КБЖУ: "+withNutrition
            else "КБЖУ: товар "+(index+1)+" / "+urls.size+" · получено "+saved
        deadline=android.os.SystemClock.elapsedRealtime()+20000
        val g=generation
        web.loadUrl(urls[index])
        handler.postDelayed({if(active&&g==generation){index++;next()}},22000)
        handler.postDelayed({probe(g)},1000)
    }
    private fun probe(g:Int){
        if(!active||g!=generation||reading)return
        if(android.os.SystemClock.elapsedRealtime()>=deadline){index++;next();return}
        val expected=Uri.parse(urls[index]).path.orEmpty().trimEnd('/')
        val actual=Uri.parse(web.url.orEmpty())
        if(!storeProductUrl(actual)||actual.path.orEmpty().trimEnd('/')!=expected){handler.postDelayed({probe(g)},1000);return}
        reading=true
        val script=if(auto)catalogDetailScript else "(function(){return document.body?document.body.innerText.slice(0,60000):''})()"
        web.evaluateJavascript(script){raw->
            if(!active||g!=generation)return@evaluateJavascript
            reading=false
            val text=try{JSONTokener(raw).nextValue() as? String ?: ""}catch(_:Exception){""}
            if(auto){
                val result=try{readCatalogProductDetails(JSONObject(text))}catch(_:Exception){null}
                val now=android.os.SystemClock.elapsedRealtime()
                if(result!=null&&firstDetailsAt==0L)firstDetailsAt=now
                // Give late-loading characteristics and gallery time to arrive on the same page.
                if(result!=null&&now-firstDetailsAt>=3000L){
                    if(details.write(urls[index],result)){saved++;if(result.nutrition!=null)withNutrition++}
                    index++
                    handler.removeCallbacksAndMessages(null)
                    handler.postDelayed({if(active&&g==generation)next()},500)
                    return@evaluateJavascript
                }
            }else{
                val n=parseNutrition(text)
                if(n!=null){cache.put(urls[index],n);saved++;index++;next();return@evaluateJavascript}
            }
            handler.postDelayed({probe(g)},1000)
        }
    }
    private fun finishResult(reason:String=""){
        if(!active)return
        active=false;generation++;handler.removeCallbacksAndMessages(null)
        if(auto)setResult(RESULT_OK,android.content.Intent().putExtra("details_saved",saved)
            .putExtra("details_nutrition",withNutrition).putExtra("details_reused",reused)
            .putExtra("details_without_link",withoutLink)
            .putExtra("details_unread",(urls.size-saved).coerceAtLeast(0)).putExtra("details_reason",reason))
        else setResult(RESULT_OK)
        finish()
    }
    override fun onDestroy(){active=false;generation++;handler.removeCallbacksAndMessages(null);if(::web.isInitialized){web.stopLoading();web.destroy()};super.onDestroy()}
}
