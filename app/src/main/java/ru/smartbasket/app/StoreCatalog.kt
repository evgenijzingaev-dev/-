package ru.smartbasket.app

import android.net.Uri
import org.json.JSONArray
import org.json.JSONTokener

fun officialStoreUrl(u:Uri)=u.scheme=="https"&&u.port in listOf(-1,443)&&u.userInfo==null&&u.host in setOf("5ka.ru","www.5ka.ru","id.x5.ru","id-prev.x5.ru")
fun storeProductUrl(u:Uri)=officialStoreUrl(u)&&u.host in setOf("5ka.ru","www.5ka.ru")&&u.path.orEmpty().startsWith("/product/")
fun storeCategory(name:String):String{
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
val catalogScript = """
            (function(){
              if(location.protocol!=='https:'||!['5ka.ru','www.5ka.ru'].includes(location.hostname)||!(location.pathname.startsWith("/catalog/")||location.pathname.startsWith("/product/")))return '[]';
              var found=[], seen=new Set();
              for(var a of document.querySelectorAll('a[href]')){
                var u=new URL(a.href,location.href);
                if(u.origin!==location.origin||!u.pathname.startsWith('/product/')||seen.has(u.pathname)||a.getClientRects().length===0)continue;
                var rect=a.getBoundingClientRect();
                if(rect.bottom < -window.innerHeight || rect.top > window.innerHeight*2)continue;
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
                seen.add(u.pathname);found.push({url:u.origin+u.pathname,text:t});
              }
              return JSON.stringify(found);
            })()
        """.trimIndent()
data class VisibleOffer(val url:String,val offer:StoreOffer)
fun visibleOffers(raw:String,ingredient:String,unit:String,need:Long):List<VisibleOffer> = try {
    val a=JSONArray(JSONTokener(raw).nextValue() as String)
    (0 until a.length()).mapNotNull{n->
        val o=a.getJSONObject(n)
        val url=o.getString("url")
        if(!storeProductUrl(Uri.parse(url)))null
        else parseStoreOffer(o.getString("text"),unit)?.takeIf{offerMatches(ingredient,it.description)}?.let{VisibleOffer(url,it)}
    }.filter{!it.offer.conditional}.sortedBy{it.offer.total(need)}
}catch(_:Exception){emptyList()}
