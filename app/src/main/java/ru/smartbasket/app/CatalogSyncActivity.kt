
package ru.smartbasket.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.*
import android.widget.*
import org.json.JSONObject
import org.json.JSONTokener

/** Normal catalog navigation, no private API or access-control workarounds. */
class CatalogSyncActivity:Activity(){
    private lateinit var web:WebView
    private lateinit var status:TextView
    private lateinit var start:Button
    private lateinit var stop:Button
    private val handler=Handler(Looper.getMainLooper())
    private val queue=java.util.ArrayDeque<String>()
    private val seen=linkedSetOf<String>()
    private val products=linkedMapOf<String,CatalogEntry>()
    private val pageProducts=linkedSetOf<String>()
    private var active=false
    private var generation=0
    private var store=""
    private var current=""
    private var rounds=0
    private var stable=0
    private var done=0
    private var failed=0
    private var reading=false
    @Volatile private var apiCancelled=false
    private var apiRunning=false
    private lateinit var apiButton:Button
    private val settings by lazy { getSharedPreferences("catalog_sync",MODE_PRIVATE) }
    private fun apiBlocked()=settings.getBoolean("api_forbidden",false)
    private fun dp(n:Int)=(n*resources.displayMetrics.density+.5f).toInt()
    private fun decode(raw:String)=try{JSONObject(JSONTokener(raw).nextValue() as String)}catch(_:Exception){null}
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(244,247,244))}
        status=TextView(this).apply{setPadding(dp(12),dp(10),dp(12),dp(10));textSize=16f;text="Выбери адрес доставки на сайте. Затем нажми «Загрузить каталог». Вся доступная выдача будет загружаться независимо от рецептов."}
        root.addView(status)
        start=Button(this).apply{text="Загрузить каталог через сайт";isAllCaps=false;setOnClickListener{begin()}}
        stop=Button(this).apply{text="Остановить";isAllCaps=false;isEnabled=false;setOnClickListener{if(apiRunning){apiCancelled=true;status.text="Отмена запроса…";stop.isEnabled=false}else finishSync("Остановлено пользователем",false)}}
        apiButton=Button(this).apply{text="Загрузить через API (эксперимент)";isAllCaps=false;setOnClickListener{beginApi()}}
        root.addView(start);root.addView(stop);if(!apiBlocked())root.addView(apiButton)
        if(apiBlocked())status.text="Прямой API недоступен. Выбери адрес доставки и загрузи каталог через сайт."
        web=WebView(this)
        web.settings.apply{javaScriptEnabled=true;domStorageEnabled=true;allowFileAccess=false;allowContentAccess=false;mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW;setSupportMultipleWindows(false);javaScriptCanOpenWindowsAutomatically=false}
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false)
        web.webChromeClient=WebChromeClient()
        web.webViewClient=object:WebViewClient(){
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean{
                if(!request.isForMainFrame)return false
                if(officialStoreUrl(request.url))return false
                if(active)finishSync("Внешний адрес заблокирован",false)
                return true
            }
            override fun onPageFinished(view:WebView,url:String){
                if(active&&samePage(url,current))schedule(generation,1800)
            }
            override fun onReceivedSslError(view:WebView,handler:SslErrorHandler,error:SslError){handler.cancel();if(active)finishSync("Ошибка TLS",false)}
            override fun onReceivedHttpError(view:WebView,request:WebResourceRequest,response:WebResourceResponse){
                if(active&&request.isForMainFrame&&response.statusCode in listOf(401,403,429))finishSync("Магазин ограничил доступ ("+response.statusCode+").",false)
            }
        }
        root.addView(web,LinearLayout.LayoutParams(-1,0,1f));setContentView(root)
        web.loadUrl("https://5ka.ru/catalog/")
    }
    private fun beginApi(){
        if(apiBlocked())return
        if(active||apiRunning||!catalogPage(web.url.orEmpty())){Toast.makeText(this,"Сначала выбери адрес в каталоге",Toast.LENGTH_LONG).show();return}
        apiButton.isEnabled=false;start.isEnabled=false
        web.evaluateJavascript(storeScript){raw->
            if(isFinishing||isDestroyed)return@evaluateJavascript
            val code=try{JSONTokener(raw).nextValue() as String}catch(_:Exception){""}
            if(!code.matches(Regex("[A-Za-z0-9_-]{1,40}"))){
                apiButton.isEnabled=true;start.isEnabled=true;status.text="Магазин не определён. Выбери адрес доставки.";return@evaluateJavascript
            }
            apiRunning=true;apiCancelled=false;stop.isEnabled=true
            status.text="API: магазин "+code+" · загрузка дерева категорий"
            Thread{
                try{
                    val snapshot=CatalogApi({apiCancelled}).download(code){i,total,count->
                        runOnUiThread{if(!isDestroyed&&!isFinishing&&apiRunning)status.text="API: категории "+i+"/"+total+" · товаров "+count}
                    }
                    runOnUiThread{
                        if(isDestroyed||isFinishing)return@runOnUiThread
                        web.evaluateJavascript(storeScript){current->
                            if(isDestroyed||isFinishing)return@evaluateJavascript
                            val confirmed=try{JSONTokener(current).nextValue() as String}catch(_:Exception){""}
                            if(apiCancelled||confirmed!=code){endApi("Магазин изменился или загрузка отменена. Прежняя база сохранена.");return@evaluateJavascript}
                            try{
                                LocalCatalog(this).write(snapshot)
                                endApi("Получено "+snapshot.entries.size+" товаров, обычных цен "+snapshot.entries.count{it.regular!=null}+". Полнота и акции ещё не подтверждены.")
                                AlertDialog.Builder(this).setTitle("Каталог обновлён").setMessage(status.text).setPositiveButton("Готово"){_,_->setResult(RESULT_OK);finish()}.show()
                            }catch(_:Exception){endApi("Ошибка записи базы.")}
                        }
                    }
                 }catch(e:Exception){runOnUiThread{
                    if(isDestroyed||isFinishing)return@runOnUiThread
                    if(e is CatalogHttpException&&e.status==403){
                        settings.edit().putBoolean("api_forbidden",true).apply()
                        endApi("Прямой API недоступен (HTTP 403). Прежняя база сохранена.")
                        apiButton.visibility=android.view.View.GONE
                        AlertDialog.Builder(this).setTitle("Загрузить каталог через сайт?")
                            .setMessage("Сервер отказал в прямом доступе. Можно загрузить доступный ассортимент из открытого каталога. Повторять отклонённый API-запрос не будем.")
                            .setNegativeButton("Отмена",null).setPositiveButton("Через сайт"){_,_->begin()}.show()
                    }else endApi((e.message?:"Ошибка API")+". Прежняя база сохранена. Можно использовать загрузку через сайт.")
                }}
            }.start()
        }
    }
    private fun endApi(message:String){apiRunning=false;apiButton.isEnabled=true;start.isEnabled=true;stop.isEnabled=false;status.text=message}
    private fun begin(){
        if(active||apiRunning||!catalogPage(web.url.orEmpty())){Toast.makeText(this,"Сначала открой каталог и выбери адрес",Toast.LENGTH_LONG).show();return}
        start.isEnabled=false;apiButton.isEnabled=false
        web.evaluateJavascript(storeScript){raw->
            if(isDestroyed||isFinishing)return@evaluateJavascript
            val code=try{JSONTokener(raw).nextValue() as String}catch(_:Exception){""}
            if(!code.matches(Regex("[A-Za-z0-9_-]{1,40}"))){
                start.isEnabled=true;apiButton.isEnabled=true;status.text="Не определён магазин доставки. Выбери адрес на сайте, затем повтори.";return@evaluateJavascript
            }
            store=code;queue.clear();seen.clear();products.clear();done=0;failed=0;active=true;stop.isEnabled=true
            enqueue("https://5ka.ru/catalog/");next()
        }
    }
    private fun catalogPage(raw:String):Boolean{
        val u=Uri.parse(raw)
        return officialStoreUrl(u)&&u.host in listOf("5ka.ru","www.5ka.ru")&&u.path.orEmpty().startsWith("/catalog/")&&u.query==null
    }
    private fun samePage(a:String,b:String)=catalogPage(a)&&Uri.parse(a).path.orEmpty().trimEnd('/')==Uri.parse(b).path.orEmpty().trimEnd('/')
    private fun enqueue(raw:String){
        if(!catalogPage(raw))return
        val path=Uri.parse(raw).path.orEmpty().trimEnd('/')+"/"
        if(path!="/catalog/"&&!Regex("^/catalog/[A-Za-z0-9_-]+--[A-Za-z0-9_-]+/$").matches(path))return
        val canonical="https://5ka.ru"+path
        if(!seen.contains(canonical)){if(seen.size>=400){failed++;return};seen.add(canonical);queue.add(canonical)}
    }
    private fun next(){
        generation++;handler.removeCallbacksAndMessages(null);reading=false
        if(queue.isEmpty()){finishSync("Доступные категории обработаны",true);return}
        current=queue.removeFirst();rounds=0;stable=0;pageProducts.clear()
        status.text="Категорий обработано: "+done+" · в очереди: "+queue.size+" · товаров: "+products.size
        val g=generation;web.loadUrl(current)
        handler.postDelayed({if(active&&generation==g){failed++;done++;next()}},180000)
        schedule(g,5000)
    }
    private fun schedule(g:Int,ms:Long){handler.postDelayed({if(active&&generation==g)read(g)},ms)}
    private fun read(g:Int){
        if(reading||!active||g!=generation)return
        if(!samePage(web.url.orEmpty(),current)){schedule(g,3000);return}
        reading=true
        web.evaluateJavascript(scanScript){raw->
            if(!active||g!=generation||isDestroyed||isFinishing)return@evaluateJavascript
            val j=decode(raw);reading=false
            if(j==null||j.optString("store")!=store){finishSync("Не удалось подтвердить тот же магазин доставки. Выгрузка остановлена.",false);return@evaluateJavascript}
            val links=j.optJSONArray("categories")
            if(links!=null)for(i in 0 until links.length())enqueue(links.optString(i))
            val list=j.optJSONArray("products")
            var added=0
            if(list!=null)for(i in 0 until list.length()){
                val o=list.optJSONObject(i)?:continue
                val url=o.optString("url");val text=o.optString("text").take(1200)
                if(validTransferUrl(url)&&text.isNotBlank()){
                    val k=Uri.parse(url).path.orEmpty().trimEnd('/')
                    if(pageProducts.add(k))added++
                    val old=products[k]
                    if(old==null||displayCatalogPrice(text)!=null||displayCatalogPrice(old.text)==null)products[k]=CatalogEntry(url,text)
                }
            }
            rounds++;stable=if(added==0)stable+1 else 0
            status.text="Категорий обработано: "+done+" · найдено товаров: "+products.size+" · страниц в очереди: "+queue.size
            if(products.size>=30000||rounds>=50){failed++;done++;if(products.size>=30000)finishSync("Достигнут лимит выгрузки",false)else next();return@evaluateJavascript}
            // Also scroll the root catalog: categories can load below banners.
            if(stable>=3&&j.optBoolean("bottom")&&!j.optBoolean("more")){
                if(pageProducts.isEmpty()&&(links==null||links.length()==0))failed++
                done++;next();return@evaluateJavascript
            }
            reading=true
            web.evaluateJavascript(advanceScript){_ ->
                if(!active||g!=generation||isDestroyed||isFinishing)return@evaluateJavascript
                reading=false;schedule(g,1800)
            }
        }
    }
    private fun finishSync(reason:String,ended:Boolean){
        active=false;generation++;handler.removeCallbacksAndMessages(null);stop.isEnabled=false;start.isEnabled=true;apiButton.isEnabled=true
        val count=products.size
        val priced=products.values.count{displayCatalogPrice(it.text)!=null}
        status.text=reason+". Найдено "+count+" товаров, цен "+priced+"."
        if(count==0){AlertDialog.Builder(this).setTitle("Каталог не обновлён").setMessage(reason+". Прежняя база сохранена.").setPositiveButton("ОК",null).show();return}
        val snapshot=CatalogSnapshot(store,System.currentTimeMillis(),done,failed+(if(ended)0 else 1),products.values.toList())
        val previous=LocalCatalog(this).read()
        if(snapshot.failed>0&&previous!=null&&previous.store==snapshot.store&&previous.entries.size>snapshot.entries.size){
            AlertDialog.Builder(this).setTitle("Неполная выгрузка").setMessage("Получено "+count+" товаров; предыдущая база содержит "+previous.entries.size+". Прежняя база сохранена, чтобы не потерять ассортимент.").setPositiveButton("ОК",null).show();return
        }
        try{LocalCatalog(this).write(snapshot)}catch(_:Exception){Toast.makeText(this,"Ошибка сохранения базы",Toast.LENGTH_LONG).show();return}
        AlertDialog.Builder(this).setTitle("Каталог загружен частично или полностью по видимой выдаче").setMessage("Магазин: "+store+"\nТоваров: "+count+"\nС распознанной ценой: "+priced+"\nКатегорий обработано: "+done+"\nПроблемных: "+snapshot.failed+"\nПолный ассортимент не подтверждён. Цены требуют сверки перед заказом.").setPositiveButton("Готово"){_,_->setResult(RESULT_OK);finish()}.show()
    }
    override fun onDestroy(){apiCancelled=true;active=false;generation++;handler.removeCallbacksAndMessages(null);if(::web.isInitialized){web.stopLoading();web.destroy()};super.onDestroy()}
    companion object {
        private val storeScript="""(function(){try{if(!['5ka.ru','www.5ka.ru'].includes(location.hostname))return '';const x=JSON.parse(localStorage.getItem('DeliveryPanelStore')||'{}');return String(x.selectedStore?.sapCode||'');}catch(e){return '';}})()"""
        private val scanScript="""
            (function(){
              if(location.protocol!=='https:'||!['5ka.ru','www.5ka.ru'].includes(location.hostname)||!location.pathname.startsWith('/catalog/'))return '{}';
              let store='';try{store=String(JSON.parse(localStorage.getItem('DeliveryPanelStore')||'{}').selectedStore?.sapCode||'');}catch(e){}
              const categories=new Set(), products=new Map(), visible=e=>e.getClientRects().length>0&&getComputedStyle(e).visibility==='visible';
              for(const a of document.querySelectorAll('a[href]')){
                if(!visible(a))continue;
                const u=new URL(a.href,location.href);
                if(u.origin!==location.origin)continue;
                if(u.pathname.startsWith('/catalog/')&&u.pathname!='/catalog/'&&!u.search)categories.add(u.origin+u.pathname);
                if(!u.pathname.startsWith('/product/'))continue;
                let t=(a.innerText||'').trim();
                if(!t.includes('₽')){
                  let p=a.parentElement;
                  for(let i=0;p&&i<3;i++,p=p.parentElement){
                    const keys=new Set([...p.querySelectorAll('a[href*="/product/"]')].map(b=>new URL(b.href,location.href).pathname));
                    if(keys.size!==1)break;
                    const v=(p.innerText||'').trim();
                    if(v.includes('₽')&&v.length<=1200){t=v;break;}
                  }
                }
                if(t.length>0&&t.length<=1200)products.set(u.pathname,{url:u.origin+u.pathname,text:t});
              }
              const more=[...document.querySelectorAll('button,[role="button"]')].filter(b=>visible(b)&&!b.disabled&&/^(показать ещё|показать еще|загрузить ещё|загрузить еще|ещё товары|еще товары)$/i.test((b.innerText||'').trim())).length===1;
              return JSON.stringify({store,categories:[...categories],products:[...products.values()],more,bottom:window.scrollY+window.innerHeight>=document.documentElement.scrollHeight-20});
            })()
        """.trimIndent()
        private val advanceScript="""
            (function(){
              if(location.protocol!=='https:'||!['5ka.ru','www.5ka.ru'].includes(location.hostname)||!location.pathname.startsWith('/catalog/'))return false;
              const b=[...document.querySelectorAll('button,[role="button"]')].filter(x=>x.getClientRects().length>0&&!x.disabled&&/^(показать ещё|показать еще|загрузить ещё|загрузить еще|ещё товары|еще товары)$/i.test((x.innerText||'').trim()));
              if(b.length===1){b[0].click();return true;}
              window.scrollBy(0,Math.max(300,window.innerHeight*0.85));return true;
            })()
        """.trimIndent()
    }
}
