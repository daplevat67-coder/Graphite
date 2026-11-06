package art.graphite.vpn.domain.model

import org.amnezia.awg.config.Config
import org.junit.Assert.*
import org.junit.Test

class AwgParametersTest {
    private val key="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEE="
    private val publicKey="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAE="
    private val hpk="AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
    private val settings=linkedMapOf(
        "Jc" to "3","Jmin" to "64","Jmax" to "128",
        "S1" to "32","S2" to "32","S3" to "32","S4" to "32",
        "H1" to "10001-10010","H2" to "20001-20010","H3" to "30001-30010","H4" to "40001-40010",
        "I1" to "<b 0x1122><r 16>","I2" to "<rc 8>","I3" to "<rd 5>","I4" to "<t>","I5" to "<b 0x3344>",
        "HeaderProtectionKey" to hpk,"ContentPaddingAddition" to "10-20","RekeyAfterTime" to "120-180",
        "RekeyTimeout" to "5-10","RejectAfterTime" to "180-240","KeepaliveTimeout" to "10-15",
        "MaxHandshakeAttempts" to "8-10","RandomTrailers" to "on","DisableCookies" to "true")
    private fun config(parameters:Map<String,String> = emptyMap(),pka:String="25",dns:String="1.1.1.1") = buildString {
        append("[Interface]\nPrivateKey = $key\nAddress = 10.0.0.2/32\nDNS = $dns\n")
        parameters.forEach { (name,value)->append("$name = $value\n") }
        append("[Peer]\nPublicKey = $publicKey\nEndpoint = 192.0.2.1:51820\nAllowedIPs = 0.0.0.0/0\nPersistentKeepalive = $pka\n")
    }
    private fun failure(action:()->Unit) {
        try {action();fail("Expected safe AWG failure")}
        catch(e:AwgParametersException){assertEquals("INVALID_AWG_CONFIG",e.message);assertNull(e.cause);assertFalse(e.toString().contains(hpk))}
    }
    @Test fun all25FieldsSurviveDraftRoundTrip() {
        val raw=config(settings)
        val original=Config.parse(raw.byteInputStream())
        val draft=WireGuardDraft.fromConfig(raw.toByteArray(),"AWG")
        assertEquals(25,draft.awgParameters.size)
        assertEquals(hpk,draft.awgParameters["HeaderProtectionKey"])
        assertEquals(original.toAwgQuickString(),Config.parse(draft.toConfigBytes().inputStream()).toAwgQuickString())
        assertEquals(draft,WireGuardDraft.fromConfig(draft.toConfigBytes(),draft.profileName))
        assertFalse(draft.toString().contains(hpk))
    }
    @Test fun plainWireGuardRemainsPlain() {
        val parsed=Config.parse(config().byteInputStream())
        assertFalse(AwgParameters.isAwg(parsed));assertTrue(AwgParameters.fromConfig(parsed).isEmpty())
    }
    @Test fun rangeKeepsFullUnsigned32BitDomain() {AwgParameters.validate(mapOf("H1" to "4294967290-4294967295","ContentPaddingAddition" to "65536-70000"))}
    @Test fun overlappingHeadersIncludingDefaultsRejected() = failure {AwgParameters.validate(mapOf("H1" to "1-3"))}
    @Test fun descendingRangeRejected() = failure {AwgParameters.validate(mapOf("H1" to "100-50"))}
    @Test fun malformedBooleanRejected() = failure {AwgParameters.validate(mapOf("RandomTrailers" to "garbage"))}
    @Test fun junkCountAndMemoryBudgetBoundaries() {
        AwgParameters.validate(mapOf("Jc" to "4096","Jmax" to "4096"))
        failure {AwgParameters.validate(mapOf("Jc" to "4097"))}
        failure {AwgParameters.validate(mapOf("Jc" to "4096","Jmax" to "4097"))}
    }
    @Test fun unsignedPaddingAndS4BufferBound() {
        AwgParameters.validate(mapOf("S4" to "65519"))
        failure {AwgParameters.validate(mapOf("S1" to "65536"))}
        failure {AwgParameters.validate(mapOf("S4" to "65520"))}
    }
    @Test fun headerKeyRequires32BytesAndAllNonces() {
        AwgParameters.validate(mapOf("HeaderProtectionKey" to hpk,"S1" to "12","S2" to "12","S3" to "12","S4" to "12"))
        failure {AwgParameters.validate(mapOf("HeaderProtectionKey" to hpk))}
        failure {AwgParameters.validate(mapOf("HeaderProtectionKey" to "bad"))}
    }
    @Test fun rawCommentsCaseAndLastBlankFieldPreserved() {
        val map=AwgParameters.fromRawConfig("[interface]\nI2 = <r 16>\ni2 = # disabled\nRandomTrailers = on # comment\n[peer]\nJc = 9")
        assertEquals("",map["I2"]);assertEquals("on",map["RandomTrailers"]);assertNull(map["Jc"]);AwgParameters.validate(map)
    }
    @Test fun templateGrammarAndLengthAreValidated() {
        AwgParameters.validate(mapOf("I1" to "<b 0x1122><r 4><rc 8><rd 5><t><d><ds><dz 2>"))
        failure {AwgParameters.validate(mapOf("I1" to "<unknown 8>"))}
        failure {AwgParameters.validate(mapOf("I1" to "<b 0x123>"))}
        failure {AwgParameters.validate(mapOf("I1" to "<r 65535><r 1>"))}
    }
    @Test fun invalidUnfinishedRangeCanOpenRawForCorrection() {
        val draft=WireGuardDraft(awgParameters=mapOf("H1" to "10-"))
        assertTrue(draft.toConfigText().contains("H1 = 10-"))
        try {draft.toConfigBytes();fail()}catch(_:WireGuardDraftException){}
    }
    @Test fun peerKeepaliveRangeLabelsAwgAndRoundTrips() {
        val draft=WireGuardDraft.fromConfig(config(pka="20-30").toByteArray(),"AWG")
        assertEquals("20-30",draft.peers.single().persistentKeepalive)
        assertTrue(AwgParameters.isAwg(Config.parse(draft.toConfigBytes().inputStream())))
    }
    @Test fun invalidPeerKeepaliveRejected() {
        failure {AwgParameters.validatePersistentKeepalive("30-20")}
        failure {AwgParameters.validatePersistentKeepalive("4294967296")}
        AwgParameters.validatePersistentKeepalive("0")
    }
    @Test fun plusIntegerCanonicalizesWithoutLosingAwg() {
        val draft=WireGuardDraft.fromConfig(config(mapOf("Jc" to "+4","Jmin" to "064","Jmax" to "0128")).toByteArray(),"AWG")
        assertEquals("4",draft.awgParameters["Jc"])
    }
    @Test fun dnsSearchOnlyProfileRetainsDomain() {
        val parsed=Config.parse(config(dns="internal.example").byteInputStream())
        assertTrue(parsed.toAwgQuickString().contains("internal.example"))
    }
}
