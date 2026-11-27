package ru.smartbasket.app
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject

class ChatGptSessionPolicyTest{
    @Test fun rotatesBothTokensAndRetainsGrant(){
        val old=JSONObject().put("scope","openid chatgpt.tokens.use.direct").put("access","old").put("refresh","old-r")
        val response=JSONObject().put("access_token","new").put("refresh_token","new-r").put("token_type","Bearer").put("expires_in",3600)
        val next=ChatGptSessionPolicy.replacement(response,old,1000)
        assertEquals("new",next.getString("access"))
        assertEquals("new-r",next.getString("refresh"))
        assertTrue(ChatGptSessionPolicy.usable(next,1000))
        assertFalse(ChatGptSessionPolicy.usable(next,3600000))
        assertTrue(ChatGptSessionPolicy.renewable(next))
    }
    @Test(expected=IllegalArgumentException::class) fun deniesMissingPermission(){
        ChatGptSessionPolicy.replacement(JSONObject("""{"access_token":"a","refresh_token":"b","token_type":"Bearer","expires_in":3600,"scope":"openid"}"""),null,1000)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsMissingRotatingToken(){
        ChatGptSessionPolicy.replacement(JSONObject("""{"access_token":"a","token_type":"Bearer","expires_in":3600,"scope":"chatgpt.tokens.use.direct"}"""),null,1000)
    }
}
