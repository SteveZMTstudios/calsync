package top.stevezmt.calsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JiebaWrapperFunctionalTest {

    @Test
    fun testNounCandidatesAndCombinedTopTokens() {
        val sentence = "明天下午3点我们在教学楼召开学术讲座，请各位同学务必参加"
        val candidates = JiebaWrapper.nounCandidates(sentence)
        assertNotNull(candidates)
        assertTrue(candidates.isNotEmpty())
        
        val title = JiebaWrapper.combinedTopTokens(sentence)
        assertNotNull(title)
    }
}
