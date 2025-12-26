package top.stevezmt.calsync

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.mock

class MLKitLogicTest {

    @Test
    fun testMLKitStrategy_Instantiation() {
        val context = mock<Context>()
        val strategy = MLKitStrategy(context)
        assertNotNull(strategy)
        assertEquals("ML Kit", strategy.name())
    }

    @Test
    fun testEngineEnums() {
        assertEquals("ML_KIT", ParseEngine.ML_KIT.name)
        assertEquals("ML_KIT", EventParseEngine.ML_KIT.name)
    }
}
