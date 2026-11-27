
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
    private val rootSections=linkedMapOf<String,String>()
    private val rootPath="/catalog/"
    private fun atRoot()=Uri.parse(current).path.orEmpty().trimEnd('/')==rootPath.trimEnd('/')
    private var rounds=0
    private var stableSince=0L
    private var lastPageState=""
    private var pendingRead:Runnable?=null
    private var done=0
    private var failed=0
    private var reading=false
    private var missingStoreSince=0L
    private var retryCount=0
    private var lastMotion=""
    private var lastMotionAt=0L
    private var recoveryStage=0
    private var callbackWatchdog:Runnable?=null
    private var lastScan="Сканирование ещё не началось"
    private var apiFailure=""
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
        start=Button(this).apply{text="Обновить каталог и цены";isAllCaps=false;setOnClickListener{if(apiBlocked())begin() else beginApi()}}
        stop=Button(this).apply{text="Остановить";isAllCaps=false;isEnabled=false;setOnClickListener{if(apiRunning){apiCancelled=true;status.text="Отмена запроса…";stop.isEnabled=false}else finishSync("Остановлено пользователем",false)}}
        apiButton=Button(this).apply{text="Загрузить через сайт";isAllCaps=false;setOnClickListener{begin()}}
        root.addView(start);root.addView(stop);if(!apiBlocked())root.addView(apiButton)
        if(apiBlocked())status.text="Выбери адрес доставки и нажми «Обновить каталог и цены». Используется доступный каталог сайта."
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
                        apiFailure="API HTTP 403"
                        if(!apiCancelled&&catalogPage(web.url.orEmpty()))begin()
                        else status.text="API недоступен. Открой каталог сайта и повтори обновление."
                    }else {
                        apiFailure=if(e is CatalogHttpException) "API HTTP "+e.status else "API: "+e.javaClass.simpleName
                        endApi(apiFailure+". Прежняя база сохранена. Можно использовать кнопку «Загрузить через сайт».")
                    }
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
            store=code;queue.clear();seen.clear();rootSections.clear();products.clear();done=0;failed=0;active=true;stop.isEnabled=true
            enqueue("https://5ka.ru/catalog/");next()
        }
    }
    private fun catalogPage(raw:String):Boolean{
        val u=Uri.parse(raw)
        return officialStoreUrl(u)&&u.host in listOf("5ka.ru","www.5ka.ru")&&(u.path=="/catalog"||u.path.orEmpty().startsWith("/catalog/"))&&u.query==null
    }
    private fun samePage(a:String,b:String)=catalogPage(a)&&Uri.parse(a).path.orEmpty().trimEnd('/')==Uri.parse(b).path.orEmpty().trimEnd('/')
    private fun enqueue(raw:String){
        if(!catalogPage(raw))return
        val path=Uri.parse(raw).path.orEmpty().trimEnd('/')+"/"
        if(path!="/catalog/"&&!Regex("^/catalog/[A-Za-z0-9_-]+--[A-Za-z0-9_-]+/$").matches(path))return
        val canonical="https://5ka.ru"+path
        if(!seen.contains(canonical)){if(seen.size>=400){failed++;return};seen.add(canonical);queue.add(canonical)}
    }
    private fun enqueueRootSections(){
        // Only links discovered on the official root page; never follow nested collections.
        if(rootSections.size<primarySections.values.toSet().size)failed++
        rootSections.values.forEach(::enqueue)
    }
    private fun next(){
        generation++;handler.removeCallbacksAndMessages(null);reading=false;pendingRead=null
        if(queue.isEmpty()){finishSync("Доступные категории обработаны",true);return}
        current=queue.removeFirst();retryCount=0;pageProducts.clear()
        openCurrent()
    }
    private fun openCurrent(){
        rounds=0;stableSince=0L;lastPageState="";missingStoreSince=0L
        lastMotion="";lastMotionAt=android.os.SystemClock.elapsedRealtime();recoveryStage=0
        callbackWatchdog=null
        status.text="Категорий обработано: "+done+" · в очереди: "+queue.size+" · товаров: "+products.size
        val g=generation;web.loadUrl(current)
        handler.postDelayed({if(active&&generation==g)skipStalled("Превышено время загрузки категории")},900000)
        schedule(g,5000)
    }
    private fun skipStalled(reason:String){
        lastScan=reason+" · "+lastScan
        failed++
        if(atRoot()) {
            if(rootSections.isEmpty()){finishSync(reason,false);return}
            enqueueRootSections()
        } else done++
        next()
    }
    private fun recoverStalled(reason:String){
        if(!active)return
        if(retryCount>=1){skipStalled(reason);return}
        retryCount++
        generation++;handler.removeCallbacksAndMessages(null);reading=false;pendingRead=null
        web.stopLoading()
        openCurrent()
        status.text=reason+". Повторное открытие категории (1/1); собранные товары сохранены."
    }
    private fun watchCallback(g:Int){
        clearCallbackWatchdog()
        val task=Runnable{if(active&&generation==g&&reading)recoverStalled("Сайт перестал отвечать")}
        callbackWatchdog=task;handler.postDelayed(task,15000)
    }
    private fun clearCallbackWatchdog(){
        callbackWatchdog?.let{handler.removeCallbacks(it)}
        callbackWatchdog=null
    }
    private fun schedule(g:Int,ms:Long){
        pendingRead?.let{handler.removeCallbacks(it)}
        val task=Runnable{pendingRead=null;if(active&&generation==g)read(g)}
        pendingRead=task;handler.postDelayed(task,ms)
    }
    private fun read(g:Int){
        if(reading||!active||g!=generation)return
        if(!samePage(web.url.orEmpty(),current)){
            lastScan="Ожидание страницы каталога";status.text=lastScan
            if(android.os.SystemClock.elapsedRealtime()-lastMotionAt>=CatalogStallPolicy.RELOAD_MS)recoverStalled(lastScan) else schedule(g,3000)
            return
        }
        reading=true;watchCallback(g)
        web.evaluateJavascript(scanScript){raw->
            if(!active||g!=generation||isDestroyed||isFinishing)return@evaluateJavascript
            clearCallbackWatchdog();val j=decode(raw);reading=false
            val actual=j?.optString("store").orEmpty()
            if(actual.isBlank()){
                val now=android.os.SystemClock.elapsedRealtime()
                if(missingStoreSince==0L)missingStoreSince=now
                lastScan=if(j==null) "Скрипт не вернул данные страницы" else "Страница ещё не подтвердила магазин"
                status.text=lastScan
                if(now-missingStoreSince>=CatalogStallPolicy.RELOAD_MS)recoverStalled(lastScan) else schedule(g,2500)
                return@evaluateJavascript
            }
            if(actual!=store){finishSync("Магазин доставки изменился. Выгрузка остановлена.",false);return@evaluateJavascript}
            missingStoreSince=0L
            if(j==null)return@evaluateJavascript
            val links=j.optJSONArray("categories")
            lastScan="Ссылок категорий: "+(links?.length()?:0)+" · распознано основных: "+rootSections.size+" · карточек на странице: "+j.optInt("cardCount")+" · прочитано карточек: "+(j.optJSONArray("products")?.length()?:0)
            var added=0
            if(atRoot()&&links!=null)for(i in 0 until links.length()){
                val link=links.optJSONObject(i)?:continue
                val title=normalized(link.optString("title"))
                val url=link.optString("url")
                if(!catalogPage(url)||!Regex("^/catalog/[A-Za-z0-9_-]+--[A-Za-z0-9_-]+/?$").matches(Uri.parse(url).path.orEmpty()))continue
                val group=primarySections[title]?:continue
                val old=rootSections[group]
                if(old==null){rootSections[group]=url;added++}
                // Prefer the full top-level section, not the nested "all ready meals" subset.
                else if(group=="готовая еда"&&title=="готовая еда")rootSections[group]=url
            }
            val list=if(atRoot())null else j.optJSONArray("products")
            if(list!=null)for(i in 0 until list.length()){
                val o=list.optJSONObject(i)?:continue
                val url=o.optString("url");val text=o.optString("text").take(1200);val photo=publicImageUrl(o.optString("image"))
                if(validTransferUrl(url)&&text.isNotBlank()){
                    val k=Uri.parse(url).path.orEmpty().trimEnd('/')
                    if(pageProducts.add(k))added++
                    val old=products[k]
                    if(old==null||displayCatalogPrice(text)!=null||displayCatalogPrice(old.text)==null)products[k]=CatalogEntry(url,text,imageUrl=photo.ifBlank{old?.imageUrl.orEmpty()})
                }
            }
            rounds++
            val pageState=j.optString("pageState")
            val now=android.os.SystemClock.elapsedRealtime()
            val motion=pageState+"|"+j.optInt("scrollTop")
            if(added>0||motion!=lastMotion){lastMotion=motion;lastMotionAt=now;recoveryStage=0}
            val idle=now-lastMotionAt
            if(added>0||pageState!=lastPageState||j.optBoolean("loading")||!j.optBoolean("bottom")||j.optBoolean("more")){
                lastPageState=pageState;stableSince=now
            }else if(stableSince==0L)stableSince=now
            status.text="Категорий обработано: "+done+" · найдено товаров: "+products.size+" · в очереди: "+queue.size+
                "\n"+(if(j.optBoolean("loading"))"Ожидание подгрузки" else if(j.optBoolean("bottom"))"Проверка конца категории" else "Прокрутка")+
                " · без изменений "+(idle/1000)+" с"
            if(products.size>=30000||rounds>=600){
                failed++
                if(atRoot())enqueueRootSections() else done++
                if(products.size>=30000)finishSync("Достигнут лимит выгрузки",false)else next()
                return@evaluateJavascript
            }
            // Also scroll the root catalog: categories can load below banners.
            if(now-stableSince>=12000L&&j.optBoolean("bottom")&&!j.optBoolean("more")&&!j.optBoolean("loading")){
                if(atRoot()){
                    if(rootSections.isEmpty()){finishSync("Не найдены основные категории каталога",false);return@evaluateJavascript}
                    enqueueRootSections()
                }else{
                    if(pageProducts.isEmpty())failed++
                    done++
                }
                next();return@evaluateJavascript
            }
            val quietEnd=j.optBoolean("bottom")&&!j.optBoolean("more")&&!j.optBoolean("loading")
            when(CatalogStallPolicy.action(idle,recoveryStage,quietEnd)){
                3->{recoverStalled("Каталог не меняется 20 секунд");return@evaluateJavascript}
                2,1->{
                    recoveryStage=CatalogStallPolicy.action(idle,recoveryStage,quietEnd)
                    status.text="Восстановление прокрутки "+recoveryStage+"/2 · товаров "+products.size
                    reading=true;watchCallback(g)
                    val script=if(recoveryStage==1)advanceScript else retryScrollScript
                    web.evaluateJavascript(script){_ ->
                        if(!active||g!=generation||isDestroyed||isFinishing)return@evaluateJavascript
                        clearCallbackWatchdog();reading=false;schedule(g,1000)
                    }
                    return@evaluateJavascript
                }
            }
            if(j.optBoolean("loading")||quietEnd||(j.optBoolean("more")&&recoveryStage>0)){schedule(g,1000);return@evaluateJavascript}
            reading=true;watchCallback(g)
            web.evaluateJavascript(advanceScript){_ ->
                if(!active||g!=generation||isDestroyed||isFinishing)return@evaluateJavascript
                clearCallbackWatchdog();reading=false;schedule(g,1000)
            }
        }
    }
    private fun finishSync(reason:String,ended:Boolean){
        active=false;generation++;handler.removeCallbacksAndMessages(null);stop.isEnabled=false;start.isEnabled=true;apiButton.isEnabled=true
        val count=products.size
        val priced=products.values.count{displayCatalogPrice(it.text)!=null}
        status.text=reason+". Найдено "+count+" товаров, цен "+priced+"."
        if(count==0||priced==0){
            val report=reason+"\n"+apiFailure+"\n"+lastScan+"\nОсновных категорий: "+rootSections.size+"\nТоваров: "+count+" · цен: "+priced+"\nПрежняя база сохранена."
            AlertDialog.Builder(this).setTitle("Каталог не обновлён").setMessage(report)
                .setNeutralButton("Копировать отчёт"){_,_->
                    val cm=getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("SmartBasket",report))
                }.setPositiveButton("ОК",null).show();return
        }
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
        // Official catalog's broad groups. Names are resolved to live links, not fixed IDs.
        private val primarySections=mapOf(
            "готовая еда" to "готовая еда","вся готовая еда" to "готовая еда",
            "овощи, фрукты, орехи" to "овощи",
            "молочные продукты, яйца" to "молочные","молочная продукция и яйцо" to "молочные",
            "хлеб и выпечка" to "хлеб","мясо, птица, колбасы" to "мясо",
            "рыба и морепродукты" to "рыба","сладости" to "сладости",
            "снеки и чипсы" to "снеки","бакалея" to "бакалея",
            "замороженные продукты" to "заморозка","вода и напитки" to "напитки",
            "здоровый выбор" to "здоровый выбор",
            "бытовая химия, уборка" to "уборка","стирка и уборка" to "уборка",
            "красота, гигиена, аптека" to "гигиена",
            "детские товары" to "детские","для детей" to "детские",
            "для животных" to "животные","кухня, дом, дача" to "дом","для дома и дачи" to "дом"
        )
        private val storeScript="""(function(){try{if(!['5ka.ru','www.5ka.ru'].includes(location.hostname))return '';const x=JSON.parse(localStorage.getItem('DeliveryPanelStore')||'{}');return String(x.selectedStore?.sapCode||'');}catch(e){return '';}})()"""
        private val scrollHelpers="""
            const root=document.scrollingElement||document.documentElement;
            const isRootCatalog=location.pathname.replace(/\/$/,'')==='/catalog';
            const anchorSelector=isRootCatalog?'a[href*="/catalog/"]':'a[href*="/product/"]';
            const anchors=[...document.querySelectorAll(anchorSelector)];
            const scores=new Map();
            for(const a of anchors.slice(0,250)){
              if(!a.getClientRects().length)continue;
              for(let p=a.parentElement;p&&p!==root&&p!==document.body&&p!==document.documentElement;p=p.parentElement){
                const css=getComputedStyle(p);
                if(/^(auto|scroll|overlay)$/.test(css.overflowY)&&p.clientHeight>=Math.min(180,window.innerHeight*0.4)&&p.scrollHeight-p.clientHeight>20){
                  scores.set(p,(scores.get(p)||0)+1);break;
                }
              }
            }
            let scroll=root,bestScore=0;
            for(const [p,n] of scores){if(n>bestScore){scroll=p;bestScore=n;}}
            const viewport=scroll===root?{top:0,bottom:window.innerHeight}:scroll.getBoundingClientRect();
            const near=e=>{const r=e.getBoundingClientRect();return e.getClientRects().length>0&&r.bottom>=viewport.top&&r.top<=viewport.bottom;};
            const moreButtons=[...document.querySelectorAll('button,[role="button"]')].filter(x=>near(x)&&!x.disabled&&x.getAttribute('aria-disabled')!=='true'&&/^(показать ещё|показать еще|загрузить ещё|загрузить еще|ещё товары|еще товары)(?:\s+\d+)?$/i.test((x.innerText||'').trim()));
            const bottom=scroll.scrollTop+scroll.clientHeight>=scroll.scrollHeight-20;
            const loading=bottom&&[...document.querySelectorAll('[aria-busy="true"],[role="progressbar"]')].some(x=>{
              const r=x.getBoundingClientRect();return near(x)&&(scroll===root||scroll.contains(x))&&r.bottom>=viewport.bottom-Math.max(120,scroll.clientHeight*0.35);
            });
        """.trimIndent()
        private val scanScript="""
            (function(){
              if(location.protocol!=='https:'||!['5ka.ru','www.5ka.ru'].includes(location.hostname)||!(location.pathname==='/catalog'||location.pathname.startsWith('/catalog/')))return '{}';
              let store='';try{store=String(JSON.parse(localStorage.getItem('DeliveryPanelStore')||'{}').selectedStore?.sapCode||'');}catch(e){}
              ${scrollHelpers}
              const categories=new Map(), products=new Map(), visible=e=>e.getClientRects().length>0&&getComputedStyle(e).visibility==='visible';
              for(const a of document.querySelectorAll('a[href]')){
                if(!visible(a))continue;
                const u=new URL(a.href,location.href);
                if(u.origin!==location.origin)continue;
                if(location.pathname.replace(/\/$/,'')==='/catalog'&&u.pathname.startsWith('/catalog/')&&u.pathname!='/catalog/'&&!u.search){
                  const title=(a.innerText||a.getAttribute('aria-label')||'').trim();
                  if(title)categories.set(u.pathname+'|'+title,{url:u.origin+u.pathname,title});
                }
                if(!u.pathname.startsWith('/product/'))continue;
                const rect=a.getBoundingClientRect();
                if(rect.bottom < viewport.top-scroll.clientHeight || rect.top > viewport.bottom+scroll.clientHeight)continue;
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
                if(t.length>0&&t.length<=1200){
                  let img=a.querySelector('img'), p=a.parentElement;
                  for(let i=0;!img&&p&&i<3;i++,p=p.parentElement){
                    const keys=new Set([...p.querySelectorAll('a[href*="/product/"]')].map(b=>new URL(b.href,location.href).pathname));
                    if(keys.size!==1)break;
                    img=p.querySelector('img');
                  }
                  products.set(u.pathname,{url:u.origin+u.pathname,text:t,image:img?.currentSrc||img?.src||''});
                }
              }
              const cards=document.querySelectorAll('a[href*="/product/"]');
              const pageState=cards.length+'|'+scroll.scrollHeight+'|'+(cards.length?cards[cards.length-1].href:'');
              return JSON.stringify({store,categories:[...categories.values()],products:[...products.values()],cardCount:cards.length,more:bottom&&moreButtons.length===1,loading,pageState,scrollTop:Math.round(scroll.scrollTop),bottom});
            })()
        """.trimIndent()
        private val retryScrollScript="""
            (function(){
              if(location.protocol!=='https:'||!['5ka.ru','www.5ka.ru'].includes(location.hostname)||!(location.pathname==='/catalog'||location.pathname.startsWith('/catalog/')))return false;
              ${scrollHelpers}
              if(!bottom){
                scroll.scrollTop=Math.min(scroll.scrollHeight-scroll.clientHeight,scroll.scrollTop+Math.max(80,Math.floor(scroll.clientHeight*0.75)));
                return true;
              }
              if(moreButtons.length===1&&!loading){moreButtons[0].click();return true;}
              const position=scroll.scrollTop, page=location.href;
              if(position>0){
                scroll.scrollTop=Math.max(0,position-Math.max(40,Math.floor(scroll.clientHeight*0.25)));
                setTimeout(function(){if(location.href===page&&scroll.isConnected)scroll.scrollTop=position;},250);
              }
              return true;
            })()
        """.trimIndent()
        private val advanceScript="""
            (function(){
              if(location.protocol!=='https:'||!['5ka.ru','www.5ka.ru'].includes(location.hostname)||!(location.pathname==='/catalog'||location.pathname.startsWith('/catalog/')))return false;
              ${scrollHelpers}
              if(moreButtons.length===1&&bottom&&!loading){moreButtons[0].click();return true;}
              if(!bottom){
                const step=Math.max(80,Math.floor(scroll.clientHeight*0.75));
                scroll.scrollTop=Math.min(scroll.scrollHeight-scroll.clientHeight,scroll.scrollTop+step);
              }
              return true;
            })()
        """.trimIndent()
    }
}
