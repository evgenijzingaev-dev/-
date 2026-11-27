
package ru.smartbasket.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

data class UpdateRelease(val version:String,val url:String,val size:Long,val digest:String?)

fun versionParts(value:String):List<Int>? {
    if(!value.matches(Regex("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}")))return null
    return value.split('.').map{it.toInt()}
}
fun newerVersion(candidate:String,current:String):Boolean{
    val a=versionParts(candidate)?:return false
    val b=versionParts(current)?:return false
    for(i in 0..2){if(a[i]!=b[i])return a[i]>b[i]}
    return false
}
fun parseUpdateRelease(body:String,current:String):UpdateRelease? {
    val j=JSONObject(body)
    if(j.optBoolean("draft")||j.optBoolean("prerelease"))return null
    val tag=j.getString("tag_name")
    if(!tag.startsWith("v"))return null
    val v=tag.removePrefix("v")
    if(!newerVersion(v,current))return null
    val a=j.getJSONArray("assets")
    val candidates=(0 until a.length()).map{a.getJSONObject(it)}.filter{it.optString("name")=="SmartBasket.apk"}
    val asset=candidates.singleOrNull()?:throw IllegalStateException("В релизе нет единственного APK")
    val expected="https://github.com/evgenijzingaev-dev/-/releases/download/"+tag+"/SmartBasket.apk"
    require(asset.optString("browser_download_url")==expected){"Недопустимый адрес APK"}
    val size=asset.optLong("size")
    require(size in 1..64_000_000){"Некорректный размер APK"}
    val digest=asset.optString("digest").removePrefix("sha256:").lowercase().takeIf{it.matches(Regex("[0-9a-f]{64}"))}
    return UpdateRelease(v,expected,size,digest)
}
class AppUpdater(private val a:Activity){
    private val pref=a.getSharedPreferences("app_updates",Activity.MODE_PRIVATE)
    private var busy=false
    private var resumePending=false
    private val target get()=File(a.cacheDir,"updates/SmartBasket.apk")
    private val pm get()=a.packageManager
    fun installedVersion()=pm.getPackageInfo(a.packageName,0).versionName.orEmpty()
    private fun alive()=!a.isFinishing&&!a.isDestroyed
    private fun toast(s:String){if(alive())Toast.makeText(a,s,Toast.LENGTH_LONG).show()}
    private fun open(url:String,download:Boolean):HttpsURLConnection{
        var address=url
        repeat(6){
            val u=URI(address)
            val allowed=if(download)setOf("github.com","release-assets.githubusercontent.com","objects.githubusercontent.com")else setOf("api.github.com")
            require(u.scheme=="https"&&u.host in allowed&&u.userInfo==null&&u.port in listOf(-1,443)){"Недоверенный адрес обновления"}
            val c=URL(address).openConnection() as HttpsURLConnection
            c.instanceFollowRedirects=false;c.connectTimeout=15000;c.readTimeout=25000
            c.setRequestProperty("Accept",if(download)"application/octet-stream" else "application/vnd.github+json")
            c.setRequestProperty("User-Agent","SmartBasket-Android-Updater")
            val code=c.responseCode
            if(code in 300..399){
                val next=c.getHeaderField("Location");c.disconnect()
                require(!next.isNullOrBlank()){"Некорректное перенаправление"}
                address=u.resolve(next).toString()
            }else{
                if(code!=200){c.disconnect();throw IllegalStateException("GitHub HTTP "+code)}
                return c
            }
        }
        throw IllegalStateException("Слишком много перенаправлений")
    }
    fun check(manual:Boolean){
        if(busy){if(manual)toast("Проверка или загрузка уже выполняется");return}
        val now=System.currentTimeMillis()
        if(!manual&&now-pref.getLong("checked",0L) in 0 until 6*60*60*1000L)return
        busy=true
        if(manual)toast("Проверяем наличие обновления…")
        Thread{
            try{
                val c=open("https://api.github.com/repos/evgenijzingaev-dev/-/releases/latest",false)
                val body=try{c.inputStream.bufferedReader(Charsets.UTF_8).use{r->
                    val b=CharArray(4096);val s=StringBuilder()
                    while(true){val n=r.read(b);if(n<0)break;s.append(b,0,n);require(s.length<=1000000)}
                    s.toString()
                }}finally{c.disconnect()}
                val release=parseUpdateRelease(body,installedVersion())
                pref.edit().putLong("checked",now).apply()
                a.runOnUiThread{
                    busy=false
                    if(!alive())return@runOnUiThread
                    if(release==null){if(manual)toast("Установлена последняя версия")}
                    else AlertDialog.Builder(a).setTitle("Доступно обновление "+release.version)
                        .setMessage("Скачать APK напрямую из GitHub и установить поверх приложения? Корзина сохранится при совпадении подписи.")
                        .setNegativeButton("Позже",null).setPositiveButton("Скачать обновление"){_,_->download(release)}.show()
                }
            }catch(e:Exception){a.runOnUiThread{busy=false;if(manual)toast("Проверка недоступна: "+(e.message?:"ошибка сети"))}}
        }.start()
    }
    private fun download(release:UpdateRelease){
        if(busy)return
        busy=true
        toast("Скачивание версии "+release.version)
        Thread{
            val tmp=File(target.parentFile,"download.apk")
            try{
                target.parentFile?.mkdirs()
                val hash=MessageDigest.getInstance("SHA-256")
                val c=open(release.url,true)
                var count=0L
                try{c.inputStream.use{input->tmp.outputStream().use{out->
                    val b=ByteArray(16384)
                    while(true){val n=input.read(b);if(n<0)break;count+=n;require(count<=release.size);hash.update(b,0,n);out.write(b,0,n)}
                }}}finally{c.disconnect()}
                require(count==release.size){"APK скачан не полностью"}
                val digest=hash.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
                require(release.digest==null||release.digest==digest){"Контрольная сумма не совпала"}
                verifyApk(tmp,release.version)
                require(tmp.renameTo(target)){"Не удалось сохранить APK"}
                a.runOnUiThread{busy=false;if(alive())installDownloaded()}
            }catch(e:Exception){tmp.delete();a.runOnUiThread{busy=false;toast("Обновление не установлено: "+(e.message?:"ошибка"))}}
        }.start()
    }
    private fun flags()=if(Build.VERSION.SDK_INT>=28)PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    @Suppress("DEPRECATION")
    private fun signers(p:PackageInfo):Set<String>{
        val signatures=if(Build.VERSION.SDK_INT>=28)p.signingInfo?.apkContentsSigners else p.signatures
        return signatures.orEmpty().map{sig->MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}}.toSet()
    }
    @Suppress("DEPRECATION")
    private fun code(p:PackageInfo)=if(Build.VERSION.SDK_INT>=28)p.longVersionCode else p.versionCode.toLong()
    @Suppress("DEPRECATION")
    private fun verifyApk(file:File,expected:String?=null){
        val archive=pm.getPackageArchiveInfo(file.absolutePath,flags())?:throw IllegalStateException("APK повреждён")
        val installed=pm.getPackageInfo(a.packageName,flags())
        require(archive.packageName==a.packageName&&code(archive)>code(installed)){"Неподходящая версия APK"}
        require(expected==null||archive.versionName==expected){"Версия APK не совпадает с релизом"}
        val actual=signers(archive)
        require(actual.isNotEmpty()&&actual==signers(installed)&&actual==setOf("3f6dae2efe6ddddd490a71185d5d8fce910e40a39edb8e549b15a6d4b518000f")){"Подпись не совпала. Для перехода со старой отладочной версии требуется разовая установка подписанного APK."}
    }
    fun installDownloaded(){
        if(busy)return
        try{
            require(target.isFile){"Скачанного обновления пока нет"}
            verifyApk(target)
            if(!pm.canRequestPackageInstalls()){
                resumePending=true
                a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+a.packageName)))
                toast("Разреши установку из этого источника и вернись")
                return
            }
            resumePending=false
            val uri=Uri.parse("content://"+a.packageName+".updates/SmartBasket.apk")
            val i=Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            i.clipData=ClipData.newRawUri("SmartBasket APK",uri)
            a.startActivity(i)
        }catch(e:Exception){toast(e.message?:"Не удалось открыть установщик")}
    }
    fun resumeInstall(){if(resumePending&&pm.canRequestPackageInstalls()){resumePending=false;installDownloaded()}}
}
