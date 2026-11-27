
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
import org.json.JSONTokener

/** Experimental transfer using only visible controls. No credentials, private endpoints or checkout. */
class CartTransferActivity:Activity(){
    private lateinit var web:WebView
    private lateinit var status:TextView
    private lateinit var start:Button
    private lateinit var stop:Button
    private val handler=Handler(Looper.getMainLooper())
    private val lines=mutableListOf<TransferLine>()
    private var index=0
    private var generation=0
    private var running=false
    private var reading=false
    private var expectedQuantity=-1
    private var verifyAttempts=0
    private var pendingClick=false
    private fun dp(n:Int)=(n*resources.displayMetrics.density+.5f).toInt()
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        try {
            val a=JSONArray(intent.getStringExtra("lines")?:"[]")
            require(a.length() in 1..50)
            for(n in 0 until a.length()){
                val o=a.getJSONObject(n)
                val l=TransferLine(o.getString("name"),o.getString("url"),o.getLong("quantity"),o.getLong("price"))
                require(validTransferUrl(l.url)&&l.quantity in 1..30&&l.price in 1..100_000_000)
                lines.add(l)
            }
        }catch(_:Exception){finish();return}
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(244,247,244))}
        status=TextView(this).apply{setPadding(dp(12),dp(12),dp(12),dp(12));textSize=16f;text="Проверь вход, адрес доставки и фактическую корзину. Убедись, что в ней нет лишних товаров. Приложение не проверяет итог магазина и не оплачивает заказ."}
        root.addView(status)
        start=Button(this).apply{isAllCaps=false;text="Подтверждаю, начать перенос";setOnClickListener{confirmStart()}}
        stop=Button(this).apply{isAllCaps=false;text="Остановить";isEnabled=false;setOnClickListener{halt("Остановлено пользователем. Сверь товары на сайте.")}}
        root.addView(start);root.addView(stop)
        web=WebView(this)
        web.settings.apply{javaScriptEnabled=true;domStorageEnabled=true;allowFileAccess=false;allowContentAccess=false;mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW;setSupportMultipleWindows(false);javaScriptCanOpenWindowsAutomatically=false}
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false)
        web.webChromeClient=WebChromeClient()
        web.webViewClient=object:WebViewClient(){
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean{
                if(!request.isForMainFrame)return false
                if(officialStoreUrl(request.url))return false
                if(running)halt("Сайт открыл внешний адрес. Перенос остановлен.")
                return true
            }
            override fun onPageFinished(view:WebView,url:String){
                CookieManager.getInstance().flush()
                if(running&&sameProduct(url,lines[index].url))scheduleRead(generation,1800)
            }
            override fun onReceivedSslError(view:WebView,handler:SslErrorHandler,error:SslError){handler.cancel();if(running)halt("Ошибка защищённого соединения.")}
        }
        root.addView(web,LinearLayout.LayoutParams(-1,0,1f));setContentView(root)
        web.loadUrl("https://5ka.ru/catalog/")
    }
    private fun confirmStart(){
        if(running)return
        AlertDialog.Builder(this).setTitle("Разрешить перенос?")
            .setMessage("Добавить недостающие упаковки выбранных товаров. Уже видимое количество не увеличится сверх плана. При смене цены, неизвестном количестве или ошибке процесс остановится. Подтверди, что выбран правильный адрес и лишних товаров нет.")
            .setNegativeButton("Отмена",null).setPositiveButton("Начать"){_,_->start.isEnabled=false;stop.isEnabled=true;running=true;index=0;next()}.show()
    }
    private fun sameProduct(a:String,b:String):Boolean{
        val x=Uri.parse(a);val y=Uri.parse(b)
        return storeProductUrl(x)&&x.path.orEmpty().trimEnd('/')==y.path.orEmpty().trimEnd('/')
    }
    private fun next(){
        generation++;handler.removeCallbacksAndMessages(null)
        reading=false;expectedQuantity=-1;verifyAttempts=0;pendingClick=false
        if(index>=lines.size){halt("Все запланированные количества распознаны. Обязательно проверь фактическую корзину и итог перед оплатой.");return}
        val l=lines[index]
        status.text="Перенос "+(index+1)+"/"+lines.size+" · "+l.name+" × "+l.quantity
        val g=generation
        web.loadUrl(l.url)
        handler.postDelayed({if(running&&generation==g&&!pendingClick)halt("Карточка не распознана. Продолжи вручную на сайте.")},25000)
        scheduleRead(g,5000)
    }
    private fun scheduleRead(g:Int,delay:Long){handler.postDelayed({if(running&&generation==g)read(g)},delay)}
    private fun read(g:Int){
        if(!running||reading||generation!=g)return
        val l=lines[index]
        if(!sameProduct(web.url.orEmpty(),l.url)){if(pendingClick)halt("Страница изменилась после нажатия. Проверь корзину.")else scheduleRead(g,1500);return}
        reading=true
        web.evaluateJavascript(script(l,false,-1)){raw->
            if(!running||generation!=g||isDestroyed||isFinishing)return@evaluateJavascript
            reading=false
            val a=decode(raw)
            val state=a?.optString("state").orEmpty()
            val q=a?.optInt("quantity",-1)?:-1
            if(state!="ok"){
                if(pendingClick&&verifyAttempts++<4){scheduleRead(g,900);return@evaluateJavascript}
                halt("Не удалось однозначно проверить цену или количество: "+l.name+". Сверь корзину вручную.")
                return@evaluateJavascript
            }
            if(pendingClick){
                if(q!=expectedQuantity){
                    if(verifyAttempts++<4){scheduleRead(g,900);return@evaluateJavascript}
                    halt("Нажатие не подтверждено: "+l.name+". Повторного нажатия не будет.")
                    return@evaluateJavascript
                }
                pendingClick=false;verifyAttempts=0
            }
            if(q.toLong()>l.quantity){halt("В корзине больше товара, чем запланировано. Исправь вручную.");return@evaluateJavascript}
            if(q.toLong()==l.quantity){index++;next();return@evaluateJavascript}
            if(q<0){halt("Количество не распознано.");return@evaluateJavascript}
            click(g,l,q)
        }
    }
    private fun click(g:Int,l:TransferLine,q:Int){
        if(!running||generation!=g||pendingClick)return
        reading=true;pendingClick=true;expectedQuantity=q+1;verifyAttempts=0
        web.evaluateJavascript(script(l,true,q)){raw->
            if(!running||generation!=g||isDestroyed||isFinishing)return@evaluateJavascript
            reading=false
            if(decode(raw)?.optString("state")!="clicked"){halt("Кнопка или цена изменилась. Проверь товар вручную.");return@evaluateJavascript}
            scheduleRead(g,1000)
        }
        handler.postDelayed({if(running&&generation==g&&reading)halt("Ответ сайта не получен. Проверь корзину вручную.")},10000)
    }
    private fun decode(raw:String):JSONObject?=try{JSONObject(JSONTokener(raw).nextValue() as String)}catch(_:Exception){null}
    private fun halt(message:String){
        running=false;generation++;handler.removeCallbacksAndMessages(null);reading=false
        start.isEnabled=false;stop.isEnabled=false;status.text=message
        AlertDialog.Builder(this).setTitle("Перенос завершён или остановлен").setMessage("Проверено "+index+" из "+lines.size+" позиций. "+message+"\nНикакой оплаты не выполнено.").setPositiveButton("ОК",null).show()
    }
    private fun script(l:TransferLine,click:Boolean,expected:Int):String {
        val path=JSONObject.quote(Uri.parse(l.url).path.orEmpty().trimEnd('/'))
        val title=JSONObject.quote(l.name)
        return """
            (function(){
              const result=(s,q=-1)=>JSON.stringify({state:s,quantity:q});
              if(location.protocol!=='https:'||!['5ka.ru','www.5ka.ru'].includes(location.hostname)||location.pathname.replace(/\/$/,'')!==$path)return result('page');
              const norm=s=>(s||'').toLowerCase().replace(/ё/g,'е').replace(/\s+/g,' ').trim();
              const visible=e=>!!e&&e.getClientRects().length>0&&getComputedStyle(e).visibility==='visible'&&getComputedStyle(e).display!=='none';
              const hs=[...document.querySelectorAll('h1')].filter(visible);
              if(hs.length!==1)return result('heading');
              const h=norm(hs[0].innerText),name=norm($title);
              if(h.length<4||!(name.includes(h)||h.includes(name)))return result('title');
              const root=hs[0].closest('main')||document.querySelector('main');
              if(!root||!visible(root))return result('scope');
              const btns=e=>[...e.querySelectorAll('button,[role="button"]')].filter(b=>visible(b)&&!b.disabled&&b.getAttribute('aria-disabled')!=='true');
              const add=b=>norm(b.innerText)==='в корзину';
              const plus=b=>['увеличить количество','добавить одну упаковку','увеличить количество товара'].includes(norm(b.getAttribute('aria-label')));
              const minus=b=>['уменьшить количество','убрать одну упаковку','уменьшить количество товара'].includes(norm(b.getAttribute('aria-label')));
              let block=null,control=null,qty=-1;
              for(let p=hs[0].parentElement,i=0;p&&root.contains(p)&&i<8;p=p.parentElement,i++){
                const b=btns(p),a=b.filter(add),u=b.filter(plus),m=b.filter(minus);
                if(a.length===1&&u.length===0&&m.length===0){block=p;control=a[0];qty=0;break;}
                if(a.length===0&&u.length===1&&m.length===1){
                  let c=u[0].parentElement;
                  for(let j=0;c&&p.contains(c)&&j<4;j++,c=c.parentElement){
                    if(!c.contains(m[0]))continue;
                    const nums=[...c.querySelectorAll('[role="spinbutton"],input[type="number"]')].filter(visible);
                    if(nums.length===1){
                      const v=nums[0].getAttribute('aria-valuenow')||nums[0].value||nums[0].innerText;
                      if(/^\d+$/.test(String(v).trim())){qty=Number(v);block=p;control=u[0];}
                    }
                    break;
                  }
                  if(block)break;
                }
                if(p===root)break;
              }
              if(!block||!control||qty<0)return result('controls');
              // Read the one explicit current price in the same heading/control block.
              const t=(block.innerText||'').replace(/\s+/g,' ');
              if(t.length>2500||/по карте|с картой|от\s*\d+\s*шт|за\s*1\s*(кг|л)/i.test(t))return result('conditional');
              const prices=[...t.matchAll(/(старая\s+)?цена\s+(\d[\d ]*?)[,. ](\d{2})\s*₽/gi)].filter(m=>!m[1]);
              if(prices.length!==1)return result('price');
              const cost=Number(prices[0][2].replace(/ /g,''))*100+Number(prices[0][3]);
              if(cost!==${l.price})return result('changed-price');
              if($click){
                if(qty!==$expected||qty>=${l.quantity})return result('changed-quantity');
                control.click();return result('clicked',qty);
              }
              return result('ok',qty);
            })()
        """.trimIndent()
    }
    override fun onDestroy(){running=false;generation++;handler.removeCallbacksAndMessages(null);if(::web.isInitialized){web.stopLoading();web.destroy()};super.onDestroy()}
}
