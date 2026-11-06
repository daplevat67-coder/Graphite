package art.graphite.vpn.domain.xray

import java.io.File
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Public, synthetic config fixtures for the root's official Xray `run -test` check.
 * Producing these files does not connect to a server or assert native-core acceptance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class XrayAcceptanceFixturesTest {
    @Test fun renderPublicClientConfigsForOfficialCoreValidation() {
        val id="11111111-1111-4111-8111-111111111111"
        val publicKey=Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32){(it+1).toByte()})
        val fixtures=linkedMapOf<String,String>()
        val extras=linkedMapOf(
            "tcp" to "",
            "ws" to "&host=cdn.example.org&path=%2Fsynthetic-ws",
            "grpc" to "&serviceName=synthetic-grpc&mode=gun&authority=cdn.example.org",
            "xhttp" to "&host=cdn.example.org&path=%2Fsynthetic-xhttp&mode=auto",
            "httpupgrade" to "&host=cdn.example.org&path=%2Fsynthetic-upgrade",
        )
        for(protocol in listOf("vless","vmess"))for((transport,parameters) in extras) {
            val encryption=if(protocol=="vless")"none" else "auto"
            fixtures["$protocol-$transport-tls"]="$protocol://$id@127.0.0.1:12345?encryption=$encryption&security=tls&sni=example.org&type=$transport$parameters"
        }
        fixtures["vless-reality-tcp-vision"]="vless://$id@127.0.0.1:12345?encryption=none&security=reality&type=tcp&sni=example.org&fp=chrome&pbk=$publicKey&sid=abcd&flow=xtls-rprx-vision"
        fixtures["vless-reality-grpc"]="vless://$id@127.0.0.1:12345?security=reality&type=grpc&sni=example.org&fp=chrome&pbk=$publicKey&sid=abcd&serviceName=synthetic-grpc"
        fixtures["vless-reality-xhttp"]="vless://$id@127.0.0.1:12345?security=reality&type=xhttp&sni=example.org&fp=chrome&pbk=$publicKey&sid=abcd&path=%2Fsynthetic-xhttp&mode=auto"
        fixtures["vmess-tcp-aead-no-tls"]="vmess://$id@127.0.0.1:12345?encryption=auto&security=none&type=tcp"
        fixtures["vless-tcp-private-lab"]="vless://$id@127.0.0.1:12345?encryption=none&security=none&type=tcp"
        fixtures["vless-https-websocket"]="vless://$id@example.org:443?security=https&type=websocket&path=%2Fvpn"
        fixtures["vless-https-grpc-multi"]="vless://$id@example.org:443?security=https&type=gun&mode=multi&path=%2Fvpn"
        fixtures["vless-mkcp-tls"]="vless://$id@example.org:443?security=tls&type=mkcp&mtu=1350&tti=30"
        fixtures["vless-reality-xhttp-extra"]="vless://$id@example.org:443?security=reality&type=splithttp&pbk=$publicKey&path=%2Fvpn&mode=stream-up&extra=%7B%22xPaddingBytes%22%3A%22100-200%22%7D"
        val pqv=Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(1952){1})
        fixtures["vless-reality-grpc-pqv"]="vless://$id@example.org:443?security=reality&type=grpc&pbk=$publicKey&pqv=$pqv&serviceName=example"
        fixtures["vless-encrypted-raw"]="vless://$id@example.org:443?type=raw&encryption=mlkem768x25519plus.native.1rtt.$publicKey"
        val legacy=JSONObject().put("v","2").put("ps","Synthetic legacy VMess").put("add","127.0.0.1").put("port",12345)
            .put("id",id).put("aid",0).put("scy","auto").put("net","ws").put("type","none")
            .put("host","cdn.example.org").put("path","/synthetic-ws").put("tls","tls").put("sni","example.org")
        fixtures["vmess-legacy-base64-ws-tls"]="vmess://"+Base64.getEncoder().encodeToString(legacy.toString().toByteArray(Charsets.UTF_8))
        val output=File("build/reports/xray-configs")
        assertTrue("Fixture report directory",output.isDirectory || output.mkdirs())
        for((name,link) in fixtures) {
            val profile=XrayProfileParser.parse(link)
            val config=XrayConfigGenerator.generate(profile)
            val json=JSONObject(config)
            val outbound=json.getJSONArray("outbounds").getJSONObject(0)
            assertEquals(profile.host,outbound.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getString("address"))
            assertEquals("127.0.0.1",json.getJSONArray("inbounds").getJSONObject(0).getString("listen"))
            assertFalse("No private server key",config.contains("privateKey"))
            File(output,"$name.json").writeText(config,Charsets.UTF_8)
        }
        File(output,"README.txt").writeText("Synthetic public acceptance fixtures only. UUID is a constant documentation fixture, server 127.0.0.1:12345, SNI example.org; REALITY contains a synthetic public key, never a private key. Run an official pinned Xray core with run -test -config for each JSON. A parser test creating these files is not proof of core acceptance or a connection.\n",Charsets.UTF_8)
        assertEquals(22,fixtures.size)
    }
}
