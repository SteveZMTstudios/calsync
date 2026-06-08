package top.stevezmt.calsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyTimeInjectorTest {

    @Test
    fun injectsDefaultTimeForDateAnchorAndFuzzyWord() {
        val context = TestContext()

        val result = FuzzyTimeInjector.inject(context, "@全体成员 周三下午参加宣讲会 ，地点在35B4")

        assertNotNull(result)
        assertEquals("@全体成员 周三下午13:00参加宣讲会 ，地点在35B4", result!!.text)
        assertEquals("下午", result.word)
        assertEquals("13:00", result.timeLabel)
        assertEquals("已按\"下午=13:00\"补全含糊时间", result.logMessage)
    }

    @Test
    fun honorsUserEditedAndAddedPairs() {
        val context = TestContext()
        SettingsStore.setFuzzyTimePairs(
            context,
            listOf(
                SettingsStore.FuzzyTimePair("下午", 14 * 60),
                SettingsStore.FuzzyTimePair("午休后", 13 * 60 + 30)
            )
        )

        assertEquals(
            "周三下午14:00参加宣讲会",
            FuzzyTimeInjector.inject(context, "周三下午参加宣讲会")!!.text
        )
        assertEquals(
            "周三午休后13:30参加宣讲会",
            FuzzyTimeInjector.inject(context, "周三午休后参加宣讲会")!!.text
        )
    }

    @Test
    fun doesNotInjectWithoutDateAnchorOrWhenExplicitClockExists() {
        val context = TestContext()

        assertNull(FuzzyTimeInjector.inject(context, "下午104的课挪至207进行，请留意开关机房"))
        assertNull(FuzzyTimeInjector.inject(context, "周三下午两点半参加宣讲会"))
        assertFalse(FuzzyTimeInjector.hasExplicitClock("下午104的课挪至207进行"))
        assertTrue(FuzzyTimeInjector.hasExplicitClock("周三下午两点半参加宣讲会"))
    }

    @Test
    fun deletingBuiltInPairDisablesThatFuzzyWord() {
        val context = TestContext()
        SettingsStore.setFuzzyTimePairs(context, SettingsStore.defaultFuzzyTimePairs().filterNot { it.word == "下午" })

        assertNull(FuzzyTimeInjector.inject(context, "周三下午参加宣讲会"))
    }
}
