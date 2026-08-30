package cn.tsh520.cocjson.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class JsonMatcherTest {

    private val sample =
        """{"tag":"#GJJ892V8P","timestamp":1788083611,"helpers":[{"data":93000001,"lvl":1,"helper_cooldown":62704}],"buildings":[{"data":1000008,"lvl":12,"gear_up":1},{"data":1000000,"lvl":8,"cnt":4}],"traps":[],"decos":[]}"""

    @Test fun `标准村庄JSON识别成功`() {
        val snap = JsonMatcher.match(sample)
        assertNotNull(snap)
        assertEquals("#GJJ892V8P", snap!!.tag)
        assertEquals(2, snap.buildingsCount)
    }

    @Test fun `带前后空白与换行仍识别`() {
        assertNotNull(JsonMatcher.match("\n  $sample  \r\n"))
    }

    @Test fun `普通文本不匹配`() { assertNull(JsonMatcher.match("你好世界")) }

    @Test fun `无buildings字段不匹配`() {
        assertNull(JsonMatcher.match("""{"tag":"#ABC123","timestamp":1}"""))
    }

    @Test fun `坏JSON不匹配`() {
        assertNull(JsonMatcher.match("""{"tag":"#ABC123","buildings":[{"data":"""))
    }

    @Test fun `tag小写不匹配`() { assertNull(JsonMatcher.match(sample.replace("#GJJ892V8P", "#gjj892v8p"))) }

    @Test fun `null与空白不匹配`() {
        assertNull(JsonMatcher.match(null)); assertNull(JsonMatcher.match("   "))
    }

    @Test fun `指纹稳定且区分内容`() {
        assertEquals(JsonMatcher.fingerprint(sample), JsonMatcher.fingerprint(sample))
        assertNotEquals(JsonMatcher.fingerprint(sample), JsonMatcher.fingerprint("$sample "))
    }
}
