
package ru.smartbasket.app
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
class CatalogApiTest{
    private fun product(plu:Int)=JSONObject().put("plu",plu).put("name","Молоко 900 мл").put("uom","шт").put("is_available",true).put("prices",JSONObject().put("regular","99.99"))
    @Test fun parsesTree(){assertEquals(listOf("A","B"),leafCategories(JSONArray("""[{"id":"root","categories":[{"id":"A"},{"id":"B"}]}]""")))}
    @Test fun productAndPrice(){val p=parseApiProduct(product(123))!!;assertEquals("123",p.plu);assertEquals(9999L,p.regular);assertEquals(900L,apiOffer(p,"мл")!!.pack);assertEquals(19998L,apiOffer(p,"мл")!!.total(1000))}
    @Test fun noUnknownOrWeight(){val p=parseApiProduct(product(123).put("is_available",false))!!;assertNull(apiOffer(p,"мл"));assertNull(apiOffer(p.copy(available=true,uom="кг"),"г"))}
    @Test fun pages(){val c=CatalogApi({false}){u->when{u.contains("include_subcategories")->"""[{"id":"A"}]""";u.endsWith("offset=0")->JSONObject().put("products",JSONArray().put(product(123))).toString();else->"""{"products":[]}"""}};assertEquals(1,c.download("SHOP"){_,_,_->}.entries.size)}
    @Test(expected=IllegalStateException::class) fun repeatedPage(){CatalogApi({false}){u->if(u.contains("include_subcategories"))"""[{"id":"A"}]"""else JSONObject().put("products",JSONArray().put(product(123))).toString()}.download("SHOP"){_,_,_->}}
}
