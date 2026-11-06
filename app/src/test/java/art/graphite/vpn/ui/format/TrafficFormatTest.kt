package art.graphite.vpn.ui.format
import org.junit.Assert.*
import org.junit.Test
class TrafficFormatTest {
    @Test fun megabitsAndKibibytesUseDifferentExactScales() {
        assertEquals("1.00 Мбит/с",TrafficFormat.rate(125000.0,true))
        assertEquals("122.07 КиБ/с",TrafficFormat.rate(125000.0,false))
        assertEquals("0.00 Мбит/с",TrafficFormat.rate(0.0,true))
    }
    @Test fun unknownAndInvalidCountersNeverLookLikeZeroTraffic() {
        listOf(null,Double.NaN,Double.POSITIVE_INFINITY,-1.0).forEach{assertNull(TrafficFormat.rate(it,true))}
    }
}
