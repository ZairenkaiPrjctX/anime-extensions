package aniext.core.parsing

import aniext.core.network.InvalidResponseException
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JsonTest {

    @Serializable
    class Item(
        val id: String,
        val count: Int = 0,
        val note: String? = null,
        @Serializable(with = StringOrListSerializer::class)
        val tags: List<String> = emptyList(),
    )

    @Test
    fun `ignores unknown keys and keeps optional defaults`() {
        val item = """{"id":"a","extra":{"nested":true}}""".parseAs<Item>()
        assertEquals("a", item.id)
        assertEquals(0, item.count)
        assertNull(item.note)
        assertTrue(item.tags.isEmpty())
    }

    @Test
    fun `null for a non-nullable field with default is coerced`() {
        assertEquals(0, """{"id":"a","count":null}""".parseAs<Item>().count)
    }

    @Test
    fun `string or list accepts both shapes and drops blanks`() {
        assertEquals(listOf("one"), """{"id":"a","tags":" one "}""".parseAs<Item>().tags)
        assertEquals(listOf("one", "2"), """{"id":"a","tags":["one","",2,null]}""".parseAs<Item>().tags)
        assertEquals(emptyList<String>(), """{"id":"a","tags":null}""".parseAs<Item>().tags)
    }

    @Test
    fun `structural errors become InvalidResponseException with context`() {
        for (bad in listOf("""{"count":1}""", "not json", """{"id":"a","tags":{"k":"v"}}""")) {
            try {
                bad.parseAs<Item>(context = "catalog page")
                fail("expected failure for $bad")
            } catch (e: InvalidResponseException) {
                assertTrue(e.message!!, e.message!!.startsWith("Unexpected data in catalog page"))
            }
        }
    }
}
