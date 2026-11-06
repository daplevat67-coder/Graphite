package art.graphite.vpn.data.importing

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class QrAndHttpsTest {
    @Test fun realQrPixelsDecodeUtf8PayloadWithoutNetwork() {
        val payload="https://example.invalid/profile.conf?token=synthetic-test-only"
        val matrix=QRCodeWriter().encode(payload,BarcodeFormat.QR_CODE,640,640)
        val pixels=IntArray(640*640){i->if(matrix[i%640,i/640])0xff000000.toInt() else 0xffffffff.toInt()}
        assertEquals(payload,QrImageReader.decode(640,640,pixels))
    }
    @Test fun plainImageDoesNotInventQrData() {
        try {QrImageReader.decode(128,128,IntArray(128*128){0xffffffff.toInt()});fail("Blank image accepted")}catch(_:com.google.zxing.NotFoundException){}
    }
    @Test fun fetcherRequiresHttpsWithoutUserInfoAndSameOriginRedirects() {
        val source=URI("https://example.invalid/profile?token=test")
        assertTrue(HttpsConfigFetcher.valid(source))
        assertFalse(HttpsConfigFetcher.valid(URI("http://example.invalid/profile")))
        assertFalse(HttpsConfigFetcher.valid(URI("https://user:password@example.invalid/profile")))
        assertTrue(HttpsConfigFetcher.sameOrigin(source,URI("https://example.invalid:443/new")))
        assertFalse(HttpsConfigFetcher.sameOrigin(source,URI("https://other.invalid/new")))
        assertFalse(HttpsConfigFetcher.sameOrigin(source,URI("https://example.invalid:8443/new")))
    }
}
