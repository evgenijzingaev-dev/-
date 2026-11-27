
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
    private var saving=false
    private var addressCheck=false
    private var siteOpening=true
    private var siteError=false
    private val localCatalog by lazy { LocalCatalog(this) }
    private val catalogLock=Any()
    private lateinit var savedButton:Button
    private var hasSavedCopy=false
    private val savedCopyNotice get()=if(hasSavedCopy) "\nСтарая копия доступна по кнопке «Сохранённый каталог»." else ""
    private fun showStatus(stage:String, detail:String=""){
        status.text=stage+if(detail.isEmpty()) "" else "\n"+detail
    }
    private fun scanStatus(stage:String,detail:String=""){
        val counts="Категорий обработано: "+done+" · в очереди: "+queue.size+" · товаров собрано: "+products.size
        showStatus(stage,counts+(if(detail.isEmpty()) "" else "\n"+detail)+savedCopyNotice)
    }
    private fun idleControls(){
        stop.isEnabled=false
        start.isEnabled=true
        apiButton.isEnabled=true
    }
    private fun startControls(){
        start.isEnabled=false
        apiButton.isEnabled=false
        stop.isEnabled=true
    }
    private fun cancelSync(){
        if(saving)return
        if(addressCheck){
            addressCheck=false;generation++;idleControls()
            showStatus("Обновление отменено",savedCopyNotice.trimStart())
        }else if(apiRunning){
            apiCancelled=true;stop.isEnabled=false
            showStatus("Останавливаем обновление…",savedCopyNotice.trimStart())
        }else if(active){
            finishSync("Обновление отменено",false)
        }
    }
    private fun resolveStore(useApi:Boolean,onResolved:(String)->Unit){
        if(active||apiRunning||addressCheck||saving)return
        if(!catalogPage(web.url.orEmpty())||siteOpening||siteError){
            if(siteError){siteError=false;siteOpening=true;showStatus("Открываем сайт «Пятёрочки»…",savedCopyNotice.trimStart());web.loadUrl("https://5ka.ru/catalog/")}
            else if(siteOpening)showStatus("Открываем сайт «Пятёрочки»…",savedCopyNotice.trimStart())
            else showStatus("Ждём адрес доставки","Открой каталог сайта и выбери адрес."+savedCopyNotice)
            return
        }
        addressCheck=true;startControls()
        showStatus("Проверяем выбранный магазин…",savedCopyNotice.trimStart())
        val g=++generation
        handler.postDelayed({
            if(addressCheck&&generation==g){
                addressCheck=false;generation++;idleControls()
                showStatus("Не удалось определить магазин","Проверь адрес доставки и повтори обновление."+savedCopyNotice)
            }
        },15000)
        web.evaluateJavascript(storeScript){raw->
            if(!addressCheck||generation!=g||isDestroyed||isFinishing)return@evaluateJavascript
            handler.removeCallbacksAndMessages(null)
            addressCheck=false
            val code=try{JSONTokener(raw).nextValue() as String}catch(_:Exception){""}
            if(!code.matches(Regex("[A-Za-z0-9_-]{1,40}"))){
                idleControls()
                showStatus("Ждём адрес доставки","Выбери адрес на сайте, затем повтори обновление."+savedCopyNotice)
                return@evaluateJavascript
            }
            if(useApi&&apiBlocked()){idleControls();return@evaluateJavascript}
            onResolved(code)
        }
    }
    private lateinit var apiButton:Button
    private val settings by lazy { getSharedPreferences("catalog_sync",MODE_PRIVATE) }
    private fun apiBlocked()=settings.getBoolean("api_forbidden",false)
    private fun dp(n:Int)=(n*resources.displayMetrics.density+.5f).toInt()
    private fun decode(raw:String)=try{JSONObject(JSONTokener(raw).nextValue() as String)}catch(_:Exception){null}
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(244,247,244))}
        hasSavedCopy=java.io.File(filesDir,"store-catalog.json").isFile
        status=TextView(this).apply{setPadding(dp(12),dp(10),dp(12),dp(10));textSize=16f}
        showStatus("Открываем сайт «Пятёрочки»…",savedCopyNotice.trimStart())
        root.addView(status)
        start=Button(this).apply{text="Обновить каталог и цены";isAllCaps=false;setOnClickListener{if(apiBlocked())begin() else beginApi()}}
        stop=Button(this).apply{text="Остановить";isAllCaps=false;isEnabled=false;setOnClickListener{cancelSync()}}
        apiButton=Button(this).apply{text="Загрузить через сайт";isAllCaps=false;setOnClickListener{begin()}}
        savedButton=Button(this).apply{
            text="Сохранённый каталог";isAllCaps=false
            visibility=if(hasSavedCopy) android.view.View.VISIBLE else android.view.View.GONE
            setOnClickListener{openSavedCatalog()}
        }
        root.addView(start);root.addView(stop);if(!apiBlocked())root.addView(apiButton);root.addView(savedButton)
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
                if(active&&samePage(url,current))schedule(generation,500)
                else if(siteOpening&&!active&&!apiRunning&&!saving&&catalogPage(url)){
                    siteOpening=false
                    if(!siteError)showStatus("Каталог сайта открыт","Проверь адрес доставки и нажми «Обновить каталог и цены»."+savedCopyNotice)
                }
            }
            override fun onReceivedError(view:WebView,request:WebResourceRequest,error:WebResourceError){
                if(request.isForMainFrame&&catalogPage(request.url.toString()))webFailure("Сайт магазина не отвечает")
            }
            override fun onReceivedSslError(view:WebView,handler:SslErrorHandler,error:SslError){
                handler.cancel()
                if(catalogPage(error.url.orEmpty()))webFailure("Не удалось установить защищённое соединение")
            }
            override fun onReceivedHttpError(view:WebView,request:WebResourceRequest,response:WebResourceResponse){
                if(request.isForMainFrame&&catalogPage(request.url.toString())){
                    val code=response.statusCode
                    webFailure(if(code in listOf(401,403,429))"Магазин ограничил доступ (HTTP "+code+")" else "Сайт магазина вернул HTTP "+code)
                }
            }
        }
        root.addView(web,LinearLayout.LayoutParams(-1,0,1f));setContentView(root)
        web.loadUrl("https://5ka.ru/catalog/")
    }
    private fun openSavedCatalog(){
        savedButton.isEnabled=false
        Thread{
            val snapshot=synchronized(catalogLock){localCatalog.read()}
            runOnUiThread{
                if(isDestroyed||isFinishing)return@runOnUiThread
                savedButton.isEnabled=true
                if(snapshot==null){
                    Toast.makeText(this,"Сохранённая копия недоступна",Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(4),dp(16),0)}
                val input=EditText(this).apply{hint="Поиск по сохранённому каталогу";isSingleLine=true}
                val count=TextView(this)
                val list=ListView(this)
                val adapter=ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,mutableListOf())
                list.adapter=adapter
                panel.addView(input);panel.addView(count);panel.addView(list,LinearLayout.LayoutParams(-1,dp(280)))
                fun fill(){
                    val q=input.text.toString().trim()
                    val matches=if(q.isEmpty())snapshot.entries else snapshot.entries.filter{
                        it.name.contains(q,ignoreCase=true)||it.text.contains(q,ignoreCase=true)
                    }
                    adapter.clear()
                    adapter.addAll(matches.take(100).map{catalogDisplay(it).label()})
                    count.text=if(matches.size>100) "Найдено: "+matches.size+" · показано 100" else "Найдено: "+matches.size
                }
                input.addTextChangedListener(object:android.text.TextWatcher{
                    override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
                    override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){fill()}
                    override fun afterTextChanged(s:android.text.Editable?){}
                })
                fill()
                AlertDialog.Builder(this).setTitle("Сохранённый каталог").setView(panel)
                    .setPositiveButton("Закрыть",null).show()
            }
        }.start()
    }
    private fun webFailure(reason:String){
        if(active){finishSync(reason,false);return}
        if(apiRunning||saving)return
        siteOpening=false;siteError=true
        if(addressCheck){addressCheck=false;generation++;handler.removeCallbacksAndMessages(null);idleControls()}
        showStatus("Не удалось открыть каталог",reason+". Нажми «Обновить каталог и цены», чтобы повторить."+savedCopyNotice)
    }
    private fun beginApi(){
        if(apiBlocked())return
        resolveStore(true){code->
            apiRunning=true;apiCancelled=false;startControls()
            val apiGeneration=generation
            showStatus("Загружаем категории…",savedCopyNotice.trimStart())
            Thread{
                try{
                    val snapshot=CatalogApi({apiCancelled}).download(code){i,total,count->
                        runOnUiThread{
                            if(!isDestroyed&&!isFinishing&&apiRunning&&generation==apiGeneration&&!apiCancelled)
                                showStatus("Обходим категории…","Категорий: "+i+"/"+total+" · товаров собрано: "+count+savedCopyNotice)
                        }
                    }
                    runOnUiThread{
                        if(isDestroyed||isFinishing||!apiRunning||generation!=apiGeneration)return@runOnUiThread
                        if(apiCancelled){endApi("Обновление отменено");return@runOnUiThread}
                        showStatus("Проверяем магазин перед сохранением…",savedCopyNotice.trimStart())
                        val check=Runnable{if(apiRunning&&generation==apiGeneration)endApi(if(apiCancelled)"Обновление отменено" else "Каталог не обновлён",if(apiCancelled)"" else "Сайт не подтвердил магазин. Повтори обновление.")}
                        handler.postDelayed(check,15000)
                        web.evaluateJavascript(storeScript){current->
                            handler.removeCallbacks(check)
                            if(isDestroyed||isFinishing||!apiRunning||generation!=apiGeneration)return@evaluateJavascript
                            val confirmed=try{JSONTokener(current).nextValue() as String}catch(_:Exception){""}
                            if(apiCancelled){endApi("Обновление отменено");return@evaluateJavascript}
                            if(confirmed!=code){endApi("Каталог не обновлён","Магазин доставки изменился.");return@evaluateJavascript}
                            if(snapshot.entries.isEmpty()||snapshot.entries.none{it.regular!=null}){
                                endApi("Каталог не обновлён","Не получено ни одной карточки с подтверждённой ценой.")
                                return@evaluateJavascript
                            }
                            apiRunning=false
                            saveSnapshot(snapshot,snapshot.entries.count{it.regular!=null})
                        }
                    }
                }catch(e:Exception){runOnUiThread{
                    if(isDestroyed||isFinishing||!apiRunning||generation!=apiGeneration)return@runOnUiThread
                    if(apiCancelled){endApi("Обновление отменено");return@runOnUiThread}
                    if(e is CatalogHttpException&&e.status==403){
                        settings.edit().putBoolean("api_forbidden",true).apply()
                        apiFailure="API HTTP 403"
                        endApi("Прямое обновление недоступно","Можно обновить каталог через сайт.")
                        apiButton.visibility=android.view.View.GONE
                    }else{
                        apiFailure=if(e is CatalogHttpException) "API HTTP "+e.status else "API: "+e.javaClass.simpleName
                        endApi("Каталог не обновлён","Не удалось загрузить данные. Попробуй «Загрузить через сайт».")
                    }
                }}
            }.start()
        }
    }
    private fun endApi(stage:String,detail:String=""){
        apiRunning=false;idleControls()
        showStatus(stage,(detail+savedCopyNotice).trimStart())
    }
    private fun begin(){
        resolveStore(false){code->
            store=code;queue.clear();seen.clear();rootSections.clear();products.clear();done=0;failed=0;active=true
            lastScan="Сканирование ещё не началось"
            startControls()
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
        scanStatus(if(atRoot())"Открываем список категорий…" else "Открываем категорию…")
        val g=generation;web.loadUrl(current)
        handler.postDelayed({if(active&&generation==g)skipStalled("Превышено время загрузки категории")},900000)
        schedule(g,1200)
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
        scanStatus("Повторяем загрузку категории…",reason)
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
    private fun read(g:Int,collect:Boolean=false){
        if(reading||!active||g!=generation)return
        if(!samePage(web.url.orEmpty(),current)){
            lastScan="Ожидание страницы каталога";scanStatus("Ждём загрузку страницы…")
            if(android.os.SystemClock.elapsedRealtime()-lastMotionAt>=CatalogStallPolicy.RELOAD_MS)recoverStalled(lastScan) else schedule(g,1000)
            return
        }
        reading=true;watchCallback(g)
        web.evaluateJavascript(if(collect) scanScript else probeScript){raw->
            if(!active||g!=generation||isDestroyed||isFinishing)return@evaluateJavascript
            clearCallbackWatchdog();val j=decode(raw);reading=false
            val actual=j?.optString("store").orEmpty()
            if(actual.isBlank()){
                val now=android.os.SystemClock.elapsedRealtime()
                if(missingStoreSince==0L)missingStoreSince=now
                lastScan=if(j==null) "Скрипт не вернул данные страницы" else "Страница ещё не подтвердила магазин"
                scanStatus("Ждём подтверждение магазина…",lastScan)
                if(now-missingStoreSince>=CatalogStallPolicy.RELOAD_MS)recoverStalled(lastScan) else schedule(g,1000)
                return@evaluateJavascript
            }
            if(actual!=store){finishSync("Магазин доставки изменился. Выгрузка остановлена.",false);return@evaluateJavascript}
            missingStoreSince=0L
            if(j==null)return@evaluateJavascript
            val pageState=j.optString("pageState")
            val now=android.os.SystemClock.elapsedRealtime()
            val quietEnd=j.optBoolean("bottom")&&!j.optBoolean("more")&&!j.optBoolean("loading")
            val motion=pageState+"|"+j.optInt("scrollTop")
            if(motion!=lastMotion){lastMotion=motion;lastMotionAt=now;recoveryStage=0}
            val idle=now-lastMotionAt
            if(collect&&quietEnd&&pageState==lastPageState){
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

                if(j.optBoolean("lostCards"))failed++
                if(products.size>=30000){failed++;finishSync("Достигнут лимит выгрузки",false);return@evaluateJavascript}
                if(atRoot()){
                    if(rootSections.isEmpty()){finishSync("Не найдены основные категории каталога",false);return@evaluateJavascript}
                    enqueueRootSections()
                }else{
                    if(pageProducts.isEmpty())failed++
                    done++
                }
                next();return@evaluateJavascript
            }
            rounds++
            if(pageState!=lastPageState||!quietEnd){
                lastPageState=pageState;stableSince=now
            }else if(stableSince==0L)stableSince=now
            scanStatus(if(j.optBoolean("loading"))"Ждём подгрузку товаров…" else if(quietEnd)"Проверяем конец категории…" else "Обходим категории…",
                "Карточек на странице: "+j.optInt("cardCount"))
            if(rounds>=2400){skipStalled("Превышен лимит проверок категории");return@evaluateJavascript}
            if(quietEnd&&now-stableSince>=CatalogStallPolicy.END_STABLE_MS){
                scanStatus("Собираем карточки категории…")
                read(g,true);return@evaluateJavascript
            }
            when(CatalogStallPolicy.action(idle,recoveryStage,quietEnd)){
                3->{recoverStalled("Каталог не меняется 20 секунд");return@evaluateJavascript}
                2,1->{
                    recoveryStage=CatalogStallPolicy.action(idle,recoveryStage,quietEnd)
                    scanStatus("Восстанавливаем прокрутку…")
                    reading=true;watchCallback(g)
                    val script=if(recoveryStage==1)advanceScript else retryScrollScript
                    web.evaluateJavascript(script){_ ->
                        if(!active||g!=generation||isDestroyed||isFinishing)return@evaluateJavascript
                        clearCallbackWatchdog();reading=false;schedule(g,CatalogStallPolicy.POLL_MS)
                    }
                    return@evaluateJavascript
                }
            }
            if(j.optBoolean("loading")||quietEnd){schedule(g,CatalogStallPolicy.POLL_MS);return@evaluateJavascript}
            reading=true;watchCallback(g)
            web.evaluateJavascript(advanceScript){_ ->
                if(!active||g!=generation||isDestroyed||isFinishing)return@evaluateJavascript
                clearCallbackWatchdog();reading=false;schedule(g,CatalogStallPolicy.POLL_MS)
            }
        }
    }
    private fun finishSync(reason:String,ended:Boolean){
        active=false;generation++;handler.removeCallbacksAndMessages(null)
        stop.isEnabled=false
        val count=products.size
        if(!ended){
            web.stopLoading();idleControls()
            if(reason=="Обновление отменено")showStatus(reason,savedCopyNotice.trimStart())
            else showStatus("Каталог не обновлён",reason.trimEnd('.')+"."+savedCopyNotice)
            return
        }
        val priced=products.values.count{displayCatalogPrice(it.text)!=null}
        if(count==0||priced==0){
            idleControls()
            showStatus("Каталог не обновлён","Не получено ни одной карточки с подтверждённой ценой."+savedCopyNotice)
            val report=reason+"\n"+apiFailure+"\n"+lastScan+"\nОсновных категорий: "+rootSections.size+"\nТоваров: "+count+" · цен: "+priced+savedCopyNotice
            AlertDialog.Builder(this).setTitle("Каталог не обновлён").setMessage(report)
                .setNeutralButton("Копировать отчёт"){_,_->
                    val cm=getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("SmartBasket",report))
                }.setPositiveButton("ОК",null).show()
            return
        }
        saveSnapshot(CatalogSnapshot(store,System.currentTimeMillis(),done,failed,products.values.toList()),priced)
    }
    private fun saveSnapshot(snapshot:CatalogSnapshot,priced:Int){
        saving=true;start.isEnabled=false;apiButton.isEnabled=false;stop.isEnabled=false
        showStatus("Сохраняем каталог…","Товаров: "+snapshot.entries.size+" · с ценой: "+priced+savedCopyNotice)
        Thread{
            val result=try{
                synchronized(catalogLock){
                    val previous=localCatalog.read()
                    if(snapshot.failed>0&&previous!=null) "Новый каталог неполный. Предыдущая копия не заменена."
                    else if(previous!=null&&previous.store==snapshot.store&&previous.entries.size>snapshot.entries.size)
                        "Найдено меньше товаров, чем в сохранённой копии. Предыдущая копия не заменена."
                    else{localCatalog.write(snapshot);null}
                }
            }catch(_:Exception){"Не удалось сохранить новую копию."}
            runOnUiThread{
                if(isDestroyed||isFinishing)return@runOnUiThread
                saving=false;idleControls()
                if(result!=null){showStatus("Каталог не обновлён",result+savedCopyNotice);return@runOnUiThread}
                hasSavedCopy=true
                savedButton.visibility=android.view.View.VISIBLE
                setResult(RESULT_OK)
                val title=if(snapshot.failed>0)"Сохранена неполная копия" else "Каталог обновлён"
                val detail="Товаров: "+snapshot.entries.size+" · с ценой: "+priced+
                    (if(snapshot.failed>0)" · категорий с проблемами: "+snapshot.failed else "")+
                    "\nПолный ассортимент сайта не подтверждён."
                showStatus(title,detail)
                AlertDialog.Builder(this).setTitle(title).setMessage(detail)
                    .setPositiveButton("Готово"){_,_->finish()}.show()
            }
        }.start()
    }
    @Deprecated("Legacy activity back navigation")
    override fun onBackPressed(){
        if(saving)return
        super.onBackPressed()
    }
    override fun onDestroy(){apiCancelled=true;active=false;addressCheck=false;generation++;handler.removeCallbacksAndMessages(null);if(::web.isInitialized){web.stopLoading();web.destroy()};super.onDestroy()}
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
            for(const a of anchors.slice(0,32)){
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
        private val probeScript=catalogScript(false)
        private val scanScript=catalogScript(true)
        private fun catalogScript(collect:Boolean)="""
            (function(){
              if(location.protocol!=='https:'||!['5ka.ru','www.5ka.ru'].includes(location.hostname)||!(location.pathname==='/catalog'||location.pathname.startsWith('/catalog/')))return '{}';
              let store='';try{store=String(JSON.parse(localStorage.getItem('DeliveryPanelStore')||'{}').selectedStore?.sapCode||'');}catch(e){}
              ${scrollHelpers}
              const cards=document.querySelectorAll('a[href*="/product/"]');
              const tracked=isRootCatalog?anchors:cards;
              const first=tracked.length?tracked[0].href:'';
              const last=tracked.length?tracked[tracked.length-1].href:'';
              const pageState=tracked.length+'|'+scroll.scrollHeight+'|'+first+'|'+last;
              // Track only anchor identity while loading, not prices or product data.
              let state=window.__smartBasketLoadState;
              if(!state||state.path!==location.pathname)state=window.__smartBasketLoadState={path:location.pathname,first:'',lost:false};
              if(state.first&&![...tracked].some(a=>a.href===state.first))state.lost=true;
              if(!state.first&&first)state.first=first;
              const meta={store,cardCount:cards.length,more:bottom&&moreButtons.length===1,loading,pageState,scrollTop:Math.round(scroll.scrollTop),bottom,lostCards:state.lost};
              if(!${collect})return JSON.stringify(meta);
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
              return JSON.stringify({...meta,categories:[...categories.values()],products:[...products.values()]});
            })()
        """.trimIndent()
        private val retryScrollScript="""
            (function(){
              if(location.protocol!=='https:'||!['5ka.ru','www.5ka.ru'].includes(location.hostname)||!(location.pathname==='/catalog'||location.pathname.startsWith('/catalog/')))return false;
              ${scrollHelpers}
              if(!bottom){
                scroll.scrollTop=scroll.scrollHeight;
                return true;
              }
              if(moreButtons.length===1&&!loading){const now=Date.now();if(now-(window.__smartBasketMoreAt||0)>=1000){window.__smartBasketMoreAt=now;moreButtons[0].click();}return true;}
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
              if(moreButtons.length===1&&bottom&&!loading){const now=Date.now();if(now-(window.__smartBasketMoreAt||0)>=1000){window.__smartBasketMoreAt=now;moreButtons[0].click();}return true;}
              if(!bottom){
                scroll.scrollTop=scroll.scrollHeight;
              }
              return true;
            })()
        """.trimIndent()
    }
}

