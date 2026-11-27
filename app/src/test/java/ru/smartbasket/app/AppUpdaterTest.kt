
package ru.smartbasket.app
import org.junit.Test
import org.junit.Assert.*

class AppUpdaterTest{
    @Test fun comparesVersions(){assertTrue(newerVersion("0.10.0","0.9.9"));assertFalse(newerVersion("0.8.0","0.8.0"));assertFalse(newerVersion("0.7.1","0.8.0"));assertFalse(newerVersion("0.8.1-dev","0.8.0"))}
    @Test fun correctRelease(){
        val json="""{"tag_name":"v0.9.0","assets":[{"name":"SmartBasket.apk","size":123,"browser_download_url":"https://github.com/evgenijzingaev-dev/-/releases/download/v0.9.0/SmartBasket.apk"}]}"""
        assertEquals("0.9.0",parseUpdateRelease(json,"0.8.0")!!.version)
        assertNull(parseUpdateRelease(json,"0.9.0"))
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsExternal(){
        parseUpdateRelease("""{"tag_name":"v0.9.0","assets":[{"name":"SmartBasket.apk","size":123,"browser_download_url":"https://example.com/SmartBasket.apk"}]}""","0.8.0")
    }
}
