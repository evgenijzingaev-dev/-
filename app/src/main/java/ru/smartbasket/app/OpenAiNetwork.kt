package ru.smartbasket.app

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** DNS-only fallback for the two OpenAI service hosts. System DNS remains the first choice. */
internal class OpenAiDns(private val system:Dns,private val encrypted:Dns,private val enabled:()->Boolean):Dns {
    override fun lookup(hostname:String):List<InetAddress>{
        try{
            val addresses=system.lookup(hostname)
            if(addresses.isNotEmpty())return addresses
            throw UnknownHostException(hostname)
        }catch(e:UnknownHostException){
            if(!enabled() || hostname !in setOf("auth.openai.com","api.openai.com"))throw e
            return try{
                encrypted.lookup(hostname).takeIf{it.isNotEmpty()}?:throw UnknownHostException(hostname)
            }catch(f:UnknownHostException){f.addSuppressed(e);throw f}
        }
    }
}

internal object OpenAiNetwork {
    private fun base()=OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(15,TimeUnit.SECONDS).readTimeout(90,TimeUnit.SECONDS)
    private val systemClient by lazy{base().build()}
    private val encryptedClient by lazy{
        val bootstrap=base().readTimeout(12,TimeUnit.SECONDS).callTimeout(20,TimeUnit.SECONDS).build()
        val doh=DnsOverHttps.Builder().client(bootstrap)
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(InetAddress.getByAddress(byteArrayOf(1,1,1,1)),InetAddress.getByAddress(byteArrayOf(1,0,0,1)))
            .includeIPv6(true).build()
        base().dns(OpenAiDns(Dns.SYSTEM,doh){true}).build()
    }
    fun client(fallback:Boolean)=if(fallback)encryptedClient else systemClient
}
