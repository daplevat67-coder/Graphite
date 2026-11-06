package art.graphite.vpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransferRatesTest {
    @Test fun computesActualBytesPerSecondFromMonotonicElapsedTime() {
        val rates = TransferRates()
        assertNull(rates.sample(1_000, 2_000, 1_000_000_000).first)
        val actual = rates.sample(2_000, 2_500, 3_000_000_000)
        assertEquals(500.0, actual.first!!, 0.001)
        assertEquals(250.0, actual.second!!, 0.001)
    }
    @Test fun counterResetOrNonIncreasingClockDoesNotFabricateNegativeOrInfiniteSpeed() {
        val rates = TransferRates()
        rates.sample(9_000, 4_000, 1_000_000_000)
        val reset = rates.sample(1, 1, 2_000_000_000)
        assertNull(reset.first)
        assertNull(reset.second)
        assertNull(rates.sample(2, 2, 2_000_000_000).first)
        val stable = rates.sample(2, 2, 3_000_000_000)
        assertEquals(0.0, stable.first!!, 0.0)
        assertEquals(0.0, stable.second!!, 0.0)
    }
    @org.junit.Test fun resettingAfterBackgroundPauseDoesNotAverageAcrossIdleTime() {
        val rates=TransferRates()
        rates.sample(100,200,1_000_000_000)
        rates.reset()
        org.junit.Assert.assertEquals(null to null,rates.sample(1000100,2000200,101_000_000_000))
        org.junit.Assert.assertEquals(100.0 to 200.0,rates.sample(1000200,2000400,102_000_000_000))
    }
}
