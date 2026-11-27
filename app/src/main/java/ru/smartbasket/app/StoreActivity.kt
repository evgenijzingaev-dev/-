package ru.smartbasket.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.webkit.*
import android.widget.*
import android.text.InputType
import org.json.JSONArray
import org.json.JSONTokener

class StoreActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var scan: Button
    private lateinit var transfer: Button
    private lateinit var add: Button
    private val room get()=intent.getLongExtra("room",Long.MAX_VALUE)
    private val unknown get()=intent.getIntExtra("unknown",0)
    private var attempted=false
    private val ingredient get()=intent.getStringExtra("name").orEmpty()
    private val unit get()=intent.getStringExtra("unit").orEmpty()
    private val need get()=intent.getLongExtra("need",0)
    private var chosen: Candidate?=null
    private data class Candidate(val url:String,val offer:StoreOffer){val description get()=offer.description;val pack get()=offer.pack;val price get()=offer.price}
    private fun dp(v:Int)=(v*resources.displayMetrics.density+.5f).toInt()
    private fun official(u:Uri)=u.scheme=="https"&&u.port in listOf(-1,443)&&u.userInfo==null&&u.host in setOf("5ka.ru","www.5ka.ru","id.x5.ru","id-prev.x5.ru")
    private fun catalog(u:Uri)=official(u)&&u.host in setOf("5ka.ru","www.5ka.ru")&&(u.path.orEmpty().startsWith("/catalog/")||u.path.orEmpty().startsWith("/product/"))
    private fun product(u:Uri)=catalog(u)&&u.path.orEmpty().startsWith("/product/")
    private fun button(text:String,action:()->Unit)=Button(this).apply{this.text=text;isAllCaps=false;setOnClickListener{action()}}
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val layout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(244,247,244))}
        val bar=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        bar.addView(button("Назад"){if(web.canGoBack())web.goBack() else finish()},LinearLayout.LayoutParams(0,dp(48),1f))
        bar.addView(button("Каталог"){web.loadUrl(category(ingredient))},LinearLayout.LayoutParams(0,dp(48),1f))
        bar.addView(button("В Chrome"){startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://5ka.ru/")))},LinearLayout.LayoutParams(0,dp(48),1f))
        layout.addView(bar)
        status=TextView(this).apply{setPadding(dp(12),dp(5),dp(12),dp(5));textSize=14f;text=if(ingredient.isBlank())"Официальный сайт • вход и адрес задаются на сайте" else "$ingredient — купить $need $unit. Выбери адрес, затем товары."}
        layout.addView(status)
        scan=button("Считать видимые товары"){readCards()};scan.isEnabled=false
        transfer=button("Перенести цену и фасовку"){confirmPrice(chosen)};transfer.isEnabled=ingredient.isNotBlank()
        add=button("Добавить одну упаковку в магазин"){addSelected()};add.isEnabled=false
        if(ingredient.isNotBlank()){layout.addView(scan);layout.addView(add);layout.addView(transfer)}
        web=WebView(this)
        web.settings.apply{javaScriptEnabled=true;domStorageEnabled=true;allowFileAccess=false;allowContentAccess=false;mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW;javaScriptCanOpenWindowsAutomatically=false;setSupportMultipleWindows(false)}
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false)
        web.webChromeClient=WebChromeClient()
        web.webViewClient=object:WebViewClient(){
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean{
                if(!request.isForMainFrame)return false
                if(official(request.url))return false
                status.text="Внешняя ссылка заблокирована. При необходимости открой официальный сайт в Chrome."
                return true
            }
            override fun onPageStarted(view:WebView,url:String,favicon:android.graphics.Bitmap?){
                scan.isEnabled=false;add.isEnabled=false
                if(!official(Uri.parse(url)))view.stopLoading()
            }
            override fun onPageFinished(view:WebView,url:String){
                CookieManager.getInstance().flush()
                scan.isEnabled=ingredient.isNotBlank()&&catalog(Uri.parse(url))
                add.isEnabled=!attempted&&chosen?.url==url&&product(Uri.parse(url))
                if(ingredient.isNotBlank())status.text="$ingredient — купить $need $unit. Цены зависят от выбранного адреса."
            }
            override fun onReceivedSslError(view:WebView,handler:SslErrorHandler,error:SslError){handler.cancel();status.text="Ошибка защищённого соединения. Загрузка остановлена."}
        }
        layout.addView(web,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(layout)
        if(state==null||web.restoreState(state)==null)web.loadUrl(category(ingredient))
    }
    override fun onSaveInstanceState(outState:Bundle){web.saveState(outState);super.onSaveInstanceState(outState)}
    override fun onDestroy(){web.stopLoading();web.destroy();super.onDestroy()}
    private fun readCards(){
        val current=web.url?:return
        if(!catalog(Uri.parse(current)))return
        scan.isEnabled=false
        // Read only currently rendered product links. No cookies, credentials, API tokens or hidden state.
        web.evaluateJavascript("""
            (function(){
              if(location.protocol!=='https:'||!['5ka.ru','www.5ka.ru'].includes(location.hostname)||!(location.pathname.startsWith("/catalog/")||location.pathname.startsWith("/product/")))return '[]';
              var found=[], seen=new Set();
              for(var a of document.querySelectorAll('a[href]')){
                var u=new URL(a.href,location.href);
                if(u.origin!==location.origin||!u.pathname.startsWith('/product/')||seen.has(u.pathname)||a.getClientRects().length===0)continue;
                var t=(a.innerText||'').trim();
                if(!/цена/i.test(t)||!t.includes('₽')){
                  var p=a.parentElement;
                  for(var j=0;j<3&&p;j++,p=p.parentElement){
                    if(p.querySelectorAll('a[href*="/product/"]').length>2)break;
                    var v=(p.innerText||'').trim();
                    if(/цена/i.test(v)&&v.includes('₽')&&v.length<=1000){t=v;break;}
                  }
                }
                if(!/цена/i.test(t)||!t.includes('₽')||t.length>1000)continue;
                seen.add(u.pathname);found.push({url:u.origin+u.pathname,text:t});if(found.length>=100)break;
              }
              return JSON.stringify(found);
            })()
        """.trimIndent()){raw->
            if(isFinishing||isDestroyed)return@evaluateJavascript
            scan.isEnabled=catalog(Uri.parse(web.url.orEmpty()))
            if(web.url!=current)return@evaluateJavascript
            val candidates=try{
                val arr=JSONArray(JSONTokener(raw).nextValue() as String)
                (0 until arr.length()).mapNotNull{n->val o=arr.getJSONObject(n);parse(o.getString("url"),o.getString("text"))}
                    .filter{offerMatches(ingredient,it.description)}
                    .sortedWith(compareBy<Candidate>{it.offer.conditional}.thenBy{it.offer.total(need)})
            }catch(_:Exception){emptyList()}
            if(candidates.isEmpty()){
                AlertDialog.Builder(this).setTitle("Товары не распознаны").setMessage("Открой категорию или воспользуйся поиском сайта. Если формат карточек изменился, введи цену и фасовку вручную. Защиту сайта приложение не обходит.").setPositiveButton("ОК",null).show()
                return@evaluateJavascript
            }
            val labels=candidates.map{c->"${c.description}\n${c.offer.packages(need)} уп. × ${rub(c.price)} = ${rub(c.offer.total(need))}" +
                (if(c.offer.conditional) " • условная акция, проверь" else "") +
                (if(c.offer.total(need)>room) " • выше остатка бюджета" else "")}.toTypedArray()
            AlertDialog.Builder(this).setTitle("Выбери подходящий продукт").setItems(labels){_,n->
                chosen=candidates[n];attempted=false;add.isEnabled=false;web.loadUrl(candidates[n].url)
                Toast.makeText(this,"Проверь товар. Можно добавить одну упаковку кнопкой приложения, остальные — на сайте.",Toast.LENGTH_LONG).show()
            }.setNegativeButton("Отмена",null).show()
        }
    }
    private fun parse(url:String,raw:String):Candidate? {
        if(!product(Uri.parse(url)))return null
        return parseStoreOffer(raw,unit)?.let{Candidate(url,it)}
    }
    private fun addSelected(){
        val c=chosen?:return
        if(attempted||web.url!=c.url||!product(Uri.parse(c.url)))return
        if(c.offer.conditional){Toast.makeText(this,"Проверь условия акции и добавь товар вручную.",Toast.LENGTH_LONG).show();return}
        if(c.offer.total(need)>room){Toast.makeText(this,"Все нужные упаковки превышают остаток бюджета.",Toast.LENGTH_LONG).show();return}
        val message="Добавить одну упаковку на текущей странице? Нужно ${c.offer.packages(need)} уп., расчёт ${rub(c.offer.total(need))}. Сайт может изменить цену. "+if(unknown>0)"У других $unknown позиций нет подтверждённой цены. Бюджет пока не гарантирован."else"Сверь итог с магазином."
        AlertDialog.Builder(this).setTitle("Добавление в корзину").setMessage(message).setNegativeButton("Отмена",null).setPositiveButton("Добавить 1"){_,_->
            if(attempted||web.url!=c.url)return@setPositiveButton
            attempted=true;add.isEnabled=false
            // One explicit user-authorized DOM click. Never checkout, repeat, or call private endpoints.
            val expected=org.json.JSONObject.quote(c.url)
            web.evaluateJavascript("""
                (function(){
                  if(location.origin+location.pathname!==$expected)return 'wrong-page';
                  var buttons=[...document.querySelectorAll('button,[role="button"]')].filter(function(b){
                    return b.getClientRects().length>0&&!b.disabled&&b.getAttribute('aria-disabled')!=='true'&&
                      (b.innerText||'').trim().toLowerCase()==='в корзину';
                  });
                  if(buttons.length!==1)return 'ambiguous';
                  buttons[0].click();return 'clicked';
                })()
            """.trimIndent()){raw->
                if(isFinishing||isDestroyed)return@evaluateJavascript
                val text=if(raw=="\"clicked\"")"Нажатие отправлено. Проверь, появился ли товар в корзине; добавь остальные упаковки вручную." else "Однозначная кнопка не найдена. Добавь товар вручную на сайте."
                AlertDialog.Builder(this).setTitle("Проверка корзины").setMessage(text).setPositiveButton("ОК",null).show()
            }
        }.show()
    }
    private fun confirmPrice(c:Candidate?){
        val k=intent.getStringExtra("key")?:return
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),dp(8))}
        val pack=EditText(this).apply{hint="Фасовка, $unit";inputType=InputType.TYPE_CLASS_NUMBER;setText(c?.pack?.toString().orEmpty())}
        val price=EditText(this).apply{hint="Цена упаковки, ₽";inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL;setText(c?.price?.let{(it/100).toString()+"."+(it%100).toString().padStart(2,'0')}.orEmpty())}
        box.addView(TextView(this).apply{text="Сверь цену и фасовку с карточкой. Перенос не добавляет товар в корзину магазина."})
        box.addView(pack);box.addView(price)
        val d=AlertDialog.Builder(this).setTitle(ingredient).setView(box).setNegativeButton("Отмена",null).setPositiveButton("Перенести",null).create()
        d.setOnShowListener{d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            val q=pack.text.toString().toLongOrNull();val p=money(price.text.toString())
            if(q==null||q !in 1..10_000_000||p==null){Toast.makeText(this,"Проверь значения",Toast.LENGTH_SHORT).show();return@setOnClickListener}
            val cost=((need.coerceAtLeast(1)-1)/q+1)*p
            if(cost>room){Toast.makeText(this,"Превышение остатка бюджета. Выбери другой товар.",Toast.LENGTH_LONG).show();return@setOnClickListener}
            setResult(RESULT_OK,Intent().putExtra("key",k).putExtra("pack",q).putExtra("price",p));d.dismiss();finish()
        }};d.show()
    }
    private fun category(name:String):String{
        val n=normalized(name)
        val slug=when {
            n.contains("молоко")->"moloko--251C51947"
            n.contains("манн")||n.contains("манка")->"manka--251C53134"
            n.contains("яйц")->"yaytsa--251C52032"
            n.contains("масло слив")->"maslo-margarin--251C52027"
            n.contains("масло раст")->"maslo-uksus--251C52958"
            n.contains("хлеб")->"khleb-lavash--251C13089"
            n.contains("рыба")->"ryba--251C13077"
            n.contains("курин")->"myaso-ptitsa--251C52038"
            n.contains("творог")->"tvorog-smetana--251C51979"
            n.contains("сыр")->"syr--251C51985"
            n.contains("сахар")->"sakhar-sol-spetsii--251C52957"
            listOf("рис","гречка","макарон").any{n.contains(it)}->"makarony-krupy--251C52952"
            listOf("помидор","картоф","лук","морков","шампиньон").any{n.contains(it)}->"ovoshchi-zelen-griby--251C51628"
            else->""
        }
        return "https://5ka.ru/catalog/"+if(slug.isEmpty())"" else "$slug/"
    }
}
