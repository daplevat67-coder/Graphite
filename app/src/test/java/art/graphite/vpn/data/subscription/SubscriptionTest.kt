package art.graphite.vpn.data.subscription

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64
import org.json.JSONArray

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SubscriptionTest {
    private val first="vless://00000000-0000-4000-8000-000000000001@192.0.2.1:443?security=tls&type=tcp&sni=example.org#One"
    private val second="socks5://192.0.2.2:1080#Two"
    private fun rejected(action:()->Unit) { assertTrue(runCatching(action).isFailure) }
    @Test fun textBase64AndJsonGiveSameServers() {
        val lines="$first\n$second"
        val forms=listOf(lines,Base64.getEncoder().encodeToString(lines.toByteArray()),Base64.getUrlEncoder().withoutPadding().encodeToString(lines.toByteArray()),JSONArray(listOf(first,second)).toString())
        forms.forEach { body ->
            val parsed=SubscriptionDecoder.decode(body)
            assertEquals(listOf("One","Two"),parsed.map{it.name})
            assertEquals(listOf(first,second),parsed.map{it.bytes.toString(Charsets.UTF_8)})
        }
    }
    @Test fun duplicatesRemovedAndInvalidEntryRejectsWholeResponse() {
        assertEquals(1,SubscriptionDecoder.decode("$first\n$first").size)
        rejected{SubscriptionDecoder.decode("$first\nnot-a-profile")}
        rejected{SubscriptionDecoder.decode("$first\nhttps://example.org/another-subscription")}
        rejected{SubscriptionDecoder.decode("graphite://"+"A".repeat(43)+"@192.0.2.1:8443")}
    }
    @Test fun emptyOversizedAndDeepJsonRejected() {
        listOf("", "[]", "<html>error</html>","x".repeat(262145),"[".repeat(40)+"0"+"]".repeat(40)).forEach { rejected{SubscriptionDecoder.decode(it)} }
        rejected{SubscriptionDecoder.decode((1..201).joinToString("\n"){first.substringBefore('#')+"#server$it"})}
    }
    @Test fun onlyExplicitHttpsWithoutUserInfoOrFragment() {
        listOf("http://example.org/sub","https://user:pass@example.org/sub","https://example.org/sub#x","https://example.org:0/sub","https://example.org/sub\n").forEach { rejected{SubscriptionDecoder.url(it)} }
        assertEquals("example.org",SubscriptionDecoder.url("https://example.org/sub?token=synthetic").host)
    }
}
