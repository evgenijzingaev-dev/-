package ru.smartbasket.app

import org.json.JSONObject

internal object ChatGptSessionPolicy{
    const val permission="chatgpt.tokens.use.direct"
    fun permitted(s:JSONObject)=s.optString("scope").split(' ').contains(permission)
    fun usable(s:JSONObject,now:Long)=permitted(s)&&s.optString("access").isNotBlank()&&s.optLong("expires")>now+60000
    fun renewable(s:JSONObject)=permitted(s)&&s.optString("refresh").isNotBlank()
    fun replacement(response:JSONObject,previous:JSONObject?,now:Long):JSONObject{
        val scope=response.optString("scope",previous?.optString("scope").orEmpty())
        require(scope.split(' ').contains(permission)){"OpenAI не разрешил использование плана ChatGPT"}
        val access=response.getString("access_token")
        val refresh=response.optString("refresh_token")
        val lifetime=response.optLong("expires_in")
        require(access.isNotBlank()&&refresh.isNotBlank()&&lifetime in 1..86400&&response.optString("token_type").equals("Bearer",true)){"Неполный ответ авторизации OpenAI"}
        return JSONObject().put("access",access).put("refresh",refresh).put("scope",scope)
            .put("expires",now+lifetime*1000)
    }
}
