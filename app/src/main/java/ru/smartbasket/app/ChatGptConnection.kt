package ru.smartbasket.app

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import org.json.JSONArray
import java.net.URL
import java.net.URLEncoder
import java.net.ServerSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.MessageDigest
import java.security.KeyFactory
import java.security.Signature
import java.security.KeyStore
import java.security.spec.RSAPublicKeySpec
import java.math.BigInteger
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection

/** First-stage local personal-project integration using the documented Sign in with ChatGPT flow. */
class ChatGptConnection(context:Context){
    private val prefs=context.applicationContext.getSharedPreferences("chatgpt_connection",Context.MODE_PRIVATE)
    private val random=SecureRandom()
    private val auth="https://auth.openai.com"
    private val api="https://api.openai.com/v1"
    private val scope="openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
    private fun encode(b:ByteArray)=Base64.encodeToString(b,Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun decode(s:String)=Base64.decode(s,Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun randomValue()=encode(ByteArray(32).also{random.nextBytes(it)})
    private fun form(m:Map<String,String>)=m.entries.joinToString("&"){URLEncoder.encode(it.key,"UTF-8")+"="+URLEncoder.encode(it.value,"UTF-8")}
    private fun secret():SecretKey{
        val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        val alias="smartbasket_chatgpt_v1"
        (ks.getKey(alias,null) as? SecretKey)?.let{return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun seal(s:String):String{
        val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,secret())
        return encode(c.iv)+"."+encode(c.doFinal(s.toByteArray(Charsets.UTF_8)))
    }
    private fun unseal(s:String):String{
        val p=s.split('.');require(p.size==2)
        val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,secret(),GCMParameterSpec(128,decode(p[0])))
        return c.doFinal(decode(p[1])).toString(Charsets.UTF_8)
    }
    private fun session():JSONObject?=try{JSONObject(unseal(prefs.getString("session","").orEmpty()))}catch(_:Exception){null}
    fun connected():Boolean=session()?.let{it.optLong("expires")>System.currentTimeMillis()+30000&&it.optString("scope").split(' ').contains("chatgpt.tokens.use.direct")}==true
    fun disconnect(){prefs.edit().remove("session").remove("client").remove("subject").apply()}
    private fun request(url:String,body:String?=null,contentType:String="application/json",token:String?=null):HttpsURLConnection{
        require(url in setOf(auth+"/api/accounts/oauth/token",auth+"/.well-known/jwks.json",api+"/models",api+"/responses"))
        val c=URL(url).openConnection() as HttpsURLConnection
        c.instanceFollowRedirects=false;c.connectTimeout=15000;c.readTimeout=90000
        c.setRequestProperty("Accept",if(url.endsWith("/responses"))"text/event-stream" else "application/json")
        if(token!=null)c.setRequestProperty("Authorization","Bearer "+token)
        if(body!=null){
            c.requestMethod="POST";c.doOutput=true;c.setRequestProperty("Content-Type",contentType)
            c.outputStream.use{it.write(body.toByteArray(Charsets.UTF_8))}
        }
        val code=c.responseCode
        if(code !in 200..299){c.disconnect();throw IllegalStateException("OpenAI HTTP "+code)}
        return c
    }
    private fun json(url:String,body:String?=null,contentType:String="application/json",token:String?=null):JSONObject{
        val c=request(url,body,contentType,token)
        try{
            val s=c.inputStream.bufferedReader().use{r->
                val out=StringBuilder();val buf=CharArray(4096)
                while(true){val n=r.read(buf);if(n<0)break;out.append(buf,0,n);require(out.length<=2_000_000)}
                out.toString()
            }
            return JSONObject(s)
        }finally{c.disconnect()}
    }
    private fun verifyIdentity(id:String,client:String,nonce:String):JSONObject{
        val p=id.split('.');require(p.size==3&&id.length<64000){"Некорректный ID token"}
        val header=JSONObject(decode(p[0]).toString(Charsets.UTF_8))
        require(header.optString("alg")=="RS256"){"Неподдерживаемая подпись"}
        val kid=header.getString("kid")
        val keys=json(auth+"/.well-known/jwks.json").getJSONArray("keys")
        val key=(0 until keys.length()).map{keys.getJSONObject(it)}.singleOrNull{it.optString("kid")==kid&&it.optString("kty")=="RSA"}
            ?:error("Не найден ключ подписи OpenAI")
        val publicKey=KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(BigInteger(1,decode(key.getString("n"))),BigInteger(1,decode(key.getString("e")))))
        val sig=Signature.getInstance("SHA256withRSA")
        sig.initVerify(publicKey);sig.update((p[0]+"."+p[1]).toByteArray(Charsets.US_ASCII))
        require(sig.verify(decode(p[2]))){"Подпись OpenAI не подтверждена"}
        val claims=JSONObject(decode(p[1]).toString(Charsets.UTF_8))
        val audience=claims.opt("aud")
        val allowed=if(audience is JSONArray)(0 until audience.length()).map{audience.getString(it)} else listOf(audience as? String)
        val now=System.currentTimeMillis()/1000
        require(claims.optString("iss")==auth&&client in allowed&&claims.optLong("exp")>now&&claims.optLong("iat",Long.MAX_VALUE)<=now+60&&claims.optString("nonce")==nonce&&claims.optString("sub").isNotBlank()){"Личность OpenAI не подтверждена"}
        require(allowed.size==1||claims.optString("azp")==client){"Не совпадает получатель токена"}
        return claims
    }
    /** Blocking; invoke on worker thread. Browser callback is restricted to IPv4 loopback. */
    fun signIn(openBrowser:(String)->Unit){
        val verifier=randomValue();val state=randomValue();val nonce=randomValue()
        val host=prefs.getString("host",null)?:("urn:uuid:"+UUID.randomUUID()).also{prefs.edit().putString("host",it).commit()}
        val existing=prefs.getString("client",null)
        val previousSubject=prefs.getString("subject",null)
        ServerSocket().use{server->
            server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"),0));server.soTimeout=1000
            val redirect="http://127.0.0.1:"+server.localPort+"/auth/callback"
            val fields=linkedMapOf("client_id" to (existing?:"dynamic_agent_client"),"ext_agent_host_id" to host,
                "response_type" to "code","redirect_uri" to redirect,"scope" to scope,"resource" to api,
                "state" to state,"nonce" to nonce,"code_challenge_method" to "S256",
                "code_challenge" to encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))))
            if(existing==null)fields["agent_name_hint"]="SmartBasket"
            openBrowser(auth+"/api/accounts/authorize?"+form(fields))
            val deadline=android.os.SystemClock.elapsedRealtime()+180000
            var callback:Uri?=null
            while(callback==null&&android.os.SystemClock.elapsedRealtime()<deadline){
                val socket=try{server.accept()}catch(_:java.net.SocketTimeoutException){continue}
                socket.use{s->
                    s.soTimeout=3000
                    try{
                        val input=s.getInputStream()
                        val line=StringBuilder()
                        while(line.length<16384){val n=input.read();if(n<0||n==10)break;if(n!=13)line.append(n.toChar())}
                        val parts=line.toString().split(' ')
                        val u=if(parts.size==3&&parts[0]=="GET"&&parts[1].startsWith("/auth/callback?"))Uri.parse("http://127.0.0.1"+parts[1])else null
                        val valid=u!=null&&u.path=="/auth/callback"&&u.getQueryParameters("state")==listOf(state)
                        val msg=if(valid)"Return to SmartBasket to finish connecting." else "Invalid callback."
                        val bytes=msg.toByteArray(Charsets.UTF_8)
                        s.getOutputStream().apply{write(("HTTP/1.1 "+(if(valid)"200 OK" else "400 Bad Request")+"\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\nContent-Length: "+bytes.size+"\r\n\r\n").toByteArray(Charsets.US_ASCII));write(bytes);flush()}
                        if(valid)callback=u
                    }catch(_:java.io.IOException){}
                }
            }
            val cb=callback?:error("Время входа истекло")
            require(cb.getQueryParameter("error")==null){"Вход не разрешён"}
            val issued=cb.getQueryParameter("client_id")?:existing?:error("OpenAI не вернул client_id")
            require(issued.startsWith("oaiapp_")&&issued.length<=256&&(existing==null||existing==issued)){"Некорректная регистрация OpenAI"}
            val code=cb.getQueryParameters("code").singleOrNull()?.takeIf{it.isNotBlank()}?:error("Не получен код входа")
            val tok=json(auth+"/api/accounts/oauth/token",form(mapOf("grant_type" to "authorization_code","client_id" to issued,"code" to code,
                "code_verifier" to verifier,"redirect_uri" to redirect,"resource" to api)),"application/x-www-form-urlencoded")
            val identity=verifyIdentity(tok.getString("id_token"),issued,nonce)
            require(previousSubject==null||previousSubject==identity.getString("sub")){"Выбран другой аккаунт. Сначала отключи предыдущий."}
            require(tok.optString("scope").split(' ').contains("chatgpt.tokens.use.direct")){"Не разрешено использование плана ChatGPT"}
            require(tok.optString("token_type").equals("Bearer",true)&&tok.optLong("expires_in") in 1..86400)
            val saved=JSONObject().put("access",tok.getString("access_token")).put("scope",tok.getString("scope"))
                .put("expires",System.currentTimeMillis()+tok.getLong("expires_in")*1000)
            require(prefs.edit().putString("client",issued).putString("subject",identity.getString("sub")).putString("session",seal(saved.toString())).commit()){"Не удалось сохранить подключение"}
        }
    }
    fun models():List<Pair<String,String>>{
        val token=access()
        val a=json(api+"/models",token=token).getJSONArray("models")
        return (0 until a.length()).map{a.getJSONObject(it)}.filter{it.optString("visibility")=="list"}.map{it.getString("slug") to it.optString("display_name",it.getString("slug"))}
    }
    private fun access():String{
        require(connected()){"Срок входа истёк. Подключи ChatGPT снова."}
        return session()!!.getString("access")
    }
    fun test(model:String):String{
        val token=access()
        val body=JSONObject().put("model",model).put("store",false).put("stream",true)
            .put("input",JSONArray().put(JSONObject().put("role","user").put("content","Ответь ровно одной фразой: SmartBasket подключён.")))
        val c=request(api+"/responses",body.toString(),token=token)
        val out=StringBuilder();var completed=false
        try{
            c.inputStream.bufferedReader().useLines{lines->
                for(line in lines){
                    if(!line.startsWith("data:"))continue
                    val data=line.removePrefix("data:").trim()
                    if(data=="[DONE]")break
                    val event=JSONObject(data)
                    when(event.optString("type")){
                        "response.output_text.delta"->{out.append(event.optString("delta"));require(out.length<16000)}
                        "response.completed"->{completed=true;break}
                        "response.failed","response.incomplete","error"->error("Запрос ChatGPT не завершён. Проверь лимиты и доступ.")
                    }
                }
            }
        }finally{c.disconnect()}
        require(completed&&out.isNotBlank()){"Ответ ChatGPT не завершён"}
        return out.toString()
    }
}
