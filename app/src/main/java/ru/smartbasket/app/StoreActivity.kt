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
    private val ingredient get()=intent.getStringExtra("name").orEmpty()
    private val unit get()=intent.getStringExtra("unit").orEmpty()
    private val need get()=intent.getLongExtra("need",0)
    private var chosen: Candidate?=null
    private data class Candidate(val url:String,val description:String,val pack:Long,val price:Long)
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
        if(ingredient.isNotBlank()){layout.addView(scan);layout.addView(transfer)}
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
                scan.isEnabled=false
                if(!official(Uri.parse(url)))view.stopLoading()
            }
            override fun onPageFinished(view:WebView,url:String){
                CookieManager.getInstance().flush()
                scan.isEnabled=ingredient.isNotBlank()&&catalog(Uri.parse(url))
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
              if(location.protocol!=='https:'||!['5ka.ru','www.5ka.ru'].includes(location.hostname)||!/^\\/(catalog|product)\\//.test(location.pathname))return '[]';
              var found=[], seen=new Set();
              for(var a of document.querySelectorAll('a[href]')){
                var u=new URL(a.href,location.href);
                if(u.origin!==location.origin||!u.pathname.startsWith('/product/')||seen.has(u.pathname)||a.getClientRects().length===0)continue;
                var t=(a.innerText||'').trim();
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
                    .sortedBy { if(need>0) ((need-1)/it.pack+1)*it.price else it.price }
            }catch(_:Exception){emptyList()}
            if(candidates.isEmpty()){
                AlertDialog.Builder(this).setTitle("Товары не распознаны").setMessage("Открой категорию или воспользуйся поиском сайта. Если формат карточек изменился, введи цену и фасовку вручную. Защиту сайта приложение не обходит.").setPositiveButton("ОК",null).show()
                return@evaluateJavascript
            }
            val labels=candidates.map{c->"${c.description}\n${c.pack} $unit · ${rub(c.price)} за упаковку · нужно ${if(need>0)(need-1)/c.pack+1 else 0} уп."}.toTypedArray()
            AlertDialog.Builder(this).setTitle("Выбери подходящий продукт").setItems(labels){_,n->
                chosen=candidates[n];web.loadUrl(candidates[n].url)
                Toast.makeText(this,"Проверь товар и добавь нужное количество на сайте. Затем перенеси цену.",Toast.LENGTH_LONG).show()
            }.setNegativeButton("Отмена",null).show()
        }
    }
    private fun parse(url:String,raw:String):Candidate?{
        if(!product(Uri.parse(url)))return null
        val t=raw.replace(Regex("[\\s\\u00a0\\u202f]+")," ").trim()
        val prices=Regex("(?i)цена\\s+([0-9][0-9 ]*?)(?:[.,]| )([0-9]{2})\\s*₽").findAll(t).toList()
        val p=prices.lastOrNull()?:return null
        val price=money(p.groupValues[1].replace(" ","")+"."+p.groupValues[2])?:return null
        val before=t.substring(0,t.indexOf("Цена",ignoreCase=true).takeIf{it>=0}?:return null).replace(Regex("(?i)старая $"),"").trim()
        if(before.contains("за 1 кг",true)||before.contains("за 1 л",true))return null
        val q=Regex("(?i)([0-9]+(?:[.,][0-9]+)?)\\s*(кг|мл|г|л|шт)\\s*$").find(before)?:return null
        val value=q.groupValues[1].replace(',','.').toBigDecimalOrNull()?:return null
        val u=q.groupValues[2].lowercase()
        val base=when(u){"кг"->"г";"л"->"мл";else->u}
        if(base!=unit)return null
        val pack=try{(if(u=="кг"||u=="л")value.multiply(java.math.BigDecimal(1000)) else value).longValueExact()}catch(_:Exception){return null}
        if(pack !in 1..10_000_000)return null
        return Candidate(url,before.take(220),pack,price)
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
