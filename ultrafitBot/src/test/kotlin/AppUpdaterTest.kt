import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppUpdaterTest {
    @Test
    fun detectsNewerVersions() {
        assertTrue(isNewerVersion("1.0.1", "1.0.0"))
        assertTrue(isNewerVersion("v2.0.0", "1.9.9"))
        assertTrue(isNewerVersion("1.1", "1.0.9"))
    }

    @Test
    fun rejectsSameOrOlderVersions() {
        assertFalse(isNewerVersion("1.0.0", "1.0.0"))
        assertFalse(isNewerVersion("1.0.0", "1.0.1"))
        assertFalse(isNewerVersion("2.0.0", "dev"))
    }
}
