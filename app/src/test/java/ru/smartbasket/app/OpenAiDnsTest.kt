package ru.smartbasket.app

import okhttp3.Dns
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class OpenAiDnsTest {
    private val address=InetAddress.getByAddress(byteArrayOf(1,2,3,4))
    private fun dns(block:(String)->List<InetAddress>)=object:Dns{
        override fun lookup(hostname:String)=block(hostname)
    }
    private val broken=dns{throw UnknownHostException(it)}
    @Test fun systemDnsTakesPriority(){
        val resolver=OpenAiDns(dns{listOf(address)},dns{error("Unexpected fallback")}){true}
        assertEquals(listOf(address),resolver.lookup("auth.openai.com"))
    }
    @Test fun fallbackOnSystemFailure(){
        val resolver=OpenAiDns(broken,dns{listOf(address)}){true}
        assertEquals(listOf(address),resolver.lookup("auth.openai.com"))
        assertEquals(listOf(address),resolver.lookup("api.openai.com"))
    }
    @Test(expected=UnknownHostException::class) fun noFallbackWithoutConsent(){
        OpenAiDns(broken,dns{error("Unexpected fallback")}){false}.lookup("auth.openai.com")
    }
    @Test(expected=UnknownHostException::class) fun noFallbackForOtherHosts(){
        OpenAiDns(broken,dns{error("Unexpected fallback")}){true}.lookup("5ka.ru")
    }
    @Test(expected=UnknownHostException::class) fun rejectsEmptyAnswer(){
        OpenAiDns(broken,dns{emptyList()}){true}.lookup("auth.openai.com")
    }
}
