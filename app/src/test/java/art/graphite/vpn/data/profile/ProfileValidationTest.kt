package art.graphite.vpn.data.profile

import org.junit.Assert.*
import org.junit.Test

class ProfileValidationTest {
    private val privateKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEE="
    private val publicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAE="
    private fun config(address: String = "Address = 10.0.0.2/32", endpoint: String = "Endpoint = 192.0.2.1:51820", routes: String = "AllowedIPs = 0.0.0.0/0", peer: Boolean = true, extra: String = "") =
        ("[Interface]\nPrivateKey = $privateKey\n$address\n$extra\n" + if(peer) "[Peer]\nPublicKey = $publicKey\n$endpoint\n$routes\n" else "").toByteArray()
    private fun expect(code:String,action:()->Unit) {
        try {action();fail("Expected safe validation failure")}
        catch(e:ProfileException) {assertEquals(code,e.safeCode);assertNull(e.cause);assertFalse(e.message.orEmpty().contains(privateKey))}
    }
    @Test fun validClientProfileParsed() {
        val parsed=ProfileValidation.parse(config())
        assertEquals(1,parsed.peers.size)
        assertEquals("10.0.0.2/32",parsed.`interface`.addresses.first().toString())
    }
    @Test fun missingAddressRejected() = expect("MISSING_ADDRESS") {ProfileValidation.parse(config(address=""))}
    @Test fun missingPeerRejected() = expect("MISSING_PEER") {ProfileValidation.parse(config(peer=false))}
    @Test fun missingEndpointRejected() = expect("MISSING_ENDPOINT") {ProfileValidation.parse(config(endpoint=""))}
    @Test fun missingRoutesRejected() = expect("MISSING_ROUTES") {ProfileValidation.parse(config(routes=""))}
    @Test fun unknownFieldsRejected() = expect("INVALID_CONFIG") {ProfileValidation.parse(config(extra="UnknownFutureField = 5"))}
    @Test fun awgFieldsAreRetained() {
        val parsed=ProfileValidation.parse(config(extra="Jc = 3\nJmin = 64\nJmax = 128\nH1 = 10001-10010"))
        assertTrue(art.graphite.vpn.domain.model.AwgParameters.isAwg(parsed))
        assertTrue(parsed.toAwgQuickString().contains("H1 = 10001-10010"))
    }
    @Test fun badBooleanRejectedBeforeNativeStart() = expect("INVALID_AWG_CONFIG") {ProfileValidation.parse(config(extra="RandomTrailers = garbage"))}
    @Test fun commentedBooleanValid() {ProfileValidation.parse(config(extra="RandomTrailers = on # comment"))}
    @Test fun incompatibleHeaderRangesRejected() = expect("INVALID_AWG_CONFIG") {ProfileValidation.parse(config(extra="H1 = 1-3"))}
    @Test fun excessiveJunkBudgetRejected() = expect("INVALID_AWG_CONFIG") {ProfileValidation.parse(config(extra="Jc = 4097"))}
    @Test fun invalidPeerIntervalRejected() = expect("INVALID_AWG_CONFIG") {ProfileValidation.parse((config().toString(Charsets.UTF_8)+"PersistentKeepalive = wrong\n").toByteArray())}
    @Test fun invalidUtf8Rejected() = expect("INVALID_CONFIG") {ProfileValidation.parse(byteArrayOf(0xc3.toByte(),0x28))}
    @Test fun largeInputRejected() = expect("PROFILE_TOO_LARGE") {ProfileValidation.parse(ByteArray(65537))}
    @Test fun controlCharactersInNameRejected() = expect("INVALID_NAME") {ProfileValidation.name("name\nsecret")}
    @Test fun displayNameIsTrimmedAndIdempotent() {
        assertEquals("home.conf",ProfileValidation.name(" home.conf "))
        assertEquals("home.conf",ProfileValidation.name(ProfileValidation.name("home.conf")))
    }
}
