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
    private var active=true
    private var deadline=0L
    private var reading=false
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        urls=intent.getStringArrayListExtra("urls").orEmpty().distinct().filter{storeProductUrl(Uri.parse(it))}.take(100)
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
                return !officialStoreUrl(request.url)
            }
            override fun onReceivedSslError(view:WebView,handler:SslErrorHandler,error:SslError){handler.cancel();finishResult()}
            override fun onReceivedHttpError(view:WebView,request:WebResourceRequest,response:WebResourceResponse){
                if(request.isForMainFrame&&response.statusCode in listOf(401,403,429))finishResult()
            }
        }
        layout.addView(web,LinearLayout.LayoutParams(-1,0,1f));setContentView(layout);next()
    }
    private fun next(){
        if(!active)return
        handler.removeCallbacksAndMessages(null);generation++;reading=false
        if(index>=urls.size){finishResult();return}
        status.text="КБЖУ: товар "+(index+1)+" / "+urls.size+" · получено "+saved
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
        web.evaluateJavascript("(function(){return document.body?document.body.innerText.slice(0,60000):''})()"){raw->
            if(!active||g!=generation)return@evaluateJavascript
            reading=false
            val text=try{JSONTokener(raw).nextValue() as? String ?: ""}catch(_:Exception){""}
            val n=parseNutrition(text)
            if(n!=null){cache.put(urls[index],n);saved++;index++;next()}
            else handler.postDelayed({probe(g)},1000)
        }
    }
    private fun finishResult(){
        if(!active)return
        active=false;generation++;handler.removeCallbacksAndMessages(null)
        setResult(RESULT_OK);finish()
    }
    override fun onDestroy(){active=false;generation++;handler.removeCallbacksAndMessages(null);if(::web.isInitialized){web.stopLoading();web.destroy()};super.onDestroy()}
}
