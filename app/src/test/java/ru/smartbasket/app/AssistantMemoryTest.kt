package ru.smartbasket.app
import org.junit.Test
import org.junit.Assert.*

class AssistantMemoryTest{
    @Test fun retainsRecentMessages(){
        val source=(1..30).map{AssistantMessage("user","message "+it)}
        val restored=decodeHistory(encodeHistory(source))
        assertEquals(24,restored.size)
        assertEquals("message 7",restored.first().text)
        assertEquals("message 30",restored.last().text)
    }
    @Test fun rejectsUnknownRolesAndBrokenJson(){
        assertTrue(decodeHistory("not json").isEmpty())
        assertTrue(decodeHistory("""[{"role":"system","text":"bad"}]""").isEmpty())
    }
    @Test fun capsUserText(){
        assertEquals(4000,decodeHistory(encodeHistory(listOf(AssistantMessage("user","a".repeat(5000))))).single().text.length)
    }
}
