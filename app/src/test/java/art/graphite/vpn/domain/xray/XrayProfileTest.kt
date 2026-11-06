package art.graphite.vpn.domain.xray

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class XrayProfileTest {
    private val id="11111111-1111-4111-8111-111111111111"
    private val key=Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32){(it+1).toByte()})
    private fun reject(link:String,code:String?=null) {
        try {XrayProfileParser.parse(link);fail("Invalid link accepted")}catch(e:XrayProfileException){if(code!=null)assertEquals(code,e.safeCode);assertFalse(e.toString().contains(id))}
    }
    @Test fun vlessRealityPreservesTlsIdentityAndVisionAndDoesNotAddDirectFallback() {
        val link="vless://$id@192.0.2.1:443?encryption=none&security=reality&sni=example.com&fp=chrome&pbk=$key&sid=abcd&flow=xtls-rprx-vision#My%20node"
        val p=XrayProfileParser.parse(link)
        assertEquals("My node",p.name);assertEquals("VLESS",p.protocol.uppercase());assertEquals(link,p.originalShareLink)
        assertFalse(p.toString().contains(id));assertFalse(p.toString().contains(key))
        val json=JSONObject(XrayConfigGenerator.generate(p,serverAddress="192.0.2.2"))
        assertEquals("none",json.getJSONObject("log").getString("loglevel"))
        val out=json.getJSONArray("outbounds");assertEquals(1,out.length());val proxy=out.getJSONObject(0)
        assertEquals("vless",proxy.getString("protocol"));assertEquals("192.0.2.2",proxy.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getString("address"))
        assertEquals("example.com",proxy.getJSONObject("streamSettings").getJSONObject("realitySettings").getString("serverName"))
        assertEquals(key,proxy.getJSONObject("streamSettings").getJSONObject("realitySettings").getString("password"))
        assertEquals("xtls-rprx-vision",proxy.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getJSONArray("users").getJSONObject(0).getString("flow"))
    }
    @Test fun tlsWebsocketAndGrpcGenerateCorrectTransportWithoutInsecureCertificates() {
        val ws=XrayProfileParser.parse("vless://$id@example.org:443?security=tls&type=ws&host=cdn.example.org&path=%2Fv%3Fed%3D2048&sni=example.org")
        val stream=JSONObject(XrayConfigGenerator.generate(ws)).getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
        assertEquals("/v?ed=2048",stream.getJSONObject("wsSettings").getString("path"))
        assertEquals("cdn.example.org",stream.getJSONObject("wsSettings").getJSONObject("headers").getString("Host"))
        assertFalse(stream.getJSONObject("tlsSettings").getBoolean("allowInsecure"))
        val grpc=XrayProfileParser.parse("vless://$id@example.org:443?security=tls&type=grpc&serviceName=service&mode=multi&authority=authority.example")
        val g=JSONObject(XrayConfigGenerator.generate(grpc)).getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
        assertTrue(g.getJSONObject("grpcSettings").getBoolean("multiMode"));assertEquals("service",g.getJSONObject("grpcSettings").getString("serviceName"))
        assertEquals("h2",g.getJSONObject("tlsSettings").getJSONArray("alpn").getString(0))
    }
    @Test fun vmessLegacyAeadAndNewAuthorityUriGenerateEncryptedUser() {
        val legacy=JSONObject().put("v","2").put("ps","Synthetic node").put("add","example.org").put("port",443).put("id",id).put("aid",0).put("net","ws").put("type","none").put("host","cdn.example.org").put("path","/vmess").put("tls","tls").put("scy","auto")
        val link="vmess://"+Base64.getEncoder().encodeToString(legacy.toString().toByteArray())
        val p=XrayProfileParser.parse(link);assertEquals("vmess",p.protocol);assertEquals(443,p.port);assertEquals("Synthetic node",p.name)
        val user=JSONObject(XrayConfigGenerator.generate(p)).getJSONArray("outbounds").getJSONObject(0).getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getJSONArray("users").getJSONObject(0)
        assertEquals(0,user.getInt("alterId"));assertEquals("auto",user.getString("security"))
        val modern=XrayProfileParser.parse("vmess://$id@example.org:443?encryption=chacha20-poly1305&security=tls&type=tcp")
        assertEquals("chacha20-poly1305",modern.cipher)
        legacy.put("aid",64)
        reject("vmess://"+Base64.getEncoder().encodeToString(legacy.toString().toByteArray()),"XRAY_VM_LEGACY_AID")
    }
    @Test fun plainVlessIsOnlyAllowedForPrivateControlledLab() {
        assertEquals("127.0.0.1",XrayProfileParser.parse("vless://$id@127.0.0.1:12001?encryption=none&security=none").host)
        assertEquals("::1",XrayProfileParser.parse("vless://$id@[::1]:12001?security=none").host)
        reject("vless://$id@192.0.2.1:443?security=none","XRAY_VLESS_REQUIRES_TLS")
        reject("vless://$id@example.org:443?security=none","XRAY_VLESS_REQUIRES_TLS")
    }
    @Test fun invalidCredentialsTransportsTlsAndAmbiguousParametersAreRejected() {
        listOf("vless://bad@example.org:443?security=tls",
            "vless://$id@example.org:0?security=tls",
            "vless://$id@example.org:443?security=tls&allowInsecure=1",
            "vless://$id@example.org:443?security=tls&type=quic",
            "vless://$id@example.org:443?security=tls&sni=one.example&sni=two.example",
            "vless://$id@example.org:443?security=tls&extra=%7B%7D",
            "vless://$id@example.org:443?security=reality&type=ws&pbk=$key&sni=example.org",
            "vless://$id@example.org:443?security=tls&type=ws&flow=xtls-rprx-vision").forEach{reject(it)}
        reject("vmess://%%%")
        reject("vmess://"+Base64.getEncoder().encodeToString("{".repeat(40).toByteArray()))
    }
    @Test fun xhttpAndIpv6AndLocalSocksCredentialsHaveExplicitSettings() {
        val p=XrayProfileParser.parse("vless://$id@[2001:db8::1]:443?security=tls&type=xhttp&host=cdn.example&path=%2Fx&mode=stream-one")
        assertEquals("[2001:db8::1]:443",p.endpoint)
        val json=JSONObject(XrayConfigGenerator.generate(p,socksUsername="lab-user",socksPassword="lab-pass"))
        val inbound=json.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("127.0.0.1",inbound.getString("listen"));assertEquals(10808,inbound.getInt("port"))
        assertEquals("password",inbound.getJSONObject("settings").getString("auth"))
        assertTrue(inbound.getJSONObject("settings").getBoolean("udp"))
        val stream=json.getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
        assertEquals("stream-one",stream.getJSONObject("xhttpSettings").getString("mode"))
    }
    @Test fun jsonPreservesGrpcSettingsAndDoesNotImportArbitraryDialers() {
        val raw = JSONObject()
            .put("protocol", "vless")
            .put("settings", JSONObject().put("address", "example.org").put("port", 443).put("id", id))
        val grpc = JSONObject().put("serviceName", "example").put("multiMode", true).put("idle_timeout", 30)
        val stream = JSONObject().put("network", "grpc").put("security", "tls").put("grpcSettings", grpc)
        raw.put("streamSettings", stream)
        val profile = requireNotNull(XrayJsonProfile.parseOrNull(raw.toString()))
        val config = JSONObject(XrayConfigGenerator.generate(profile))
        val settings = config.getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings").getJSONObject("grpcSettings")
        assertEquals(30, settings.getInt("idle_timeout"))
        assertTrue(settings.getBoolean("multiMode"))
        stream.put("sockopt", JSONObject().put("dialerProxy", "untrusted"))
        try {
            XrayJsonProfile.parseOrNull(raw.toString())
            fail("Imported a dialer chain")
        } catch (_: IllegalArgumentException) {}
    }
}
