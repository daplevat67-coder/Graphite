package art.graphite.vpn.data.subscription

import art.graphite.vpn.data.profile.ProfileStore
import android.content.Context
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.*
import java.security.cert.Certificate
import java.util.*
import javax.crypto.KeyGenerator

/** Only replaces Android's hardware key provider; tests the real encrypted files and AtomicFile index. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SubscriptionStoreTest {
    class TestKeyStore: KeyStoreSpi() {
        override fun engineGetKey(alias:String?,password:CharArray?)=secret
        override fun engineLoad(input:java.io.InputStream?,password:CharArray?) {}
        override fun engineStore(out:java.io.OutputStream?,password:CharArray?) {}
        override fun engineGetCertificate(alias:String?):Certificate?=null
        override fun engineGetCertificateChain(alias:String?):Array<Certificate>?=null
        override fun engineGetCreationDate(alias:String?)=Date(0)
        override fun engineSetKeyEntry(alias:String?,key:Key?,password:CharArray?,chain:Array<out Certificate>?) {}
        override fun engineSetKeyEntry(alias:String?,key:ByteArray?,chain:Array<out Certificate>?) {}
        override fun engineSetCertificateEntry(alias:String?,cert:Certificate?) {}
        override fun engineDeleteEntry(alias:String?) {}
        override fun engineAliases():Enumeration<String> = Collections.enumeration(listOf("test"))
        override fun engineContainsAlias(alias:String?)=true
        override fun engineSize()=1
        override fun engineIsKeyEntry(alias:String?)=true
        override fun engineIsCertificateEntry(alias:String?)=false
        override fun engineGetCertificateAlias(cert:Certificate?):String?=null
    }
    companion object { val secret=KeyGenerator.getInstance("AES").apply{init(256)}.generateKey() }
    private lateinit var context:Context
    private lateinit var store:ProfileStore
    private val id=UUID.randomUUID().toString()
    private val url="https://example.org/sub?token=synthetic-secret"
    private val first="vless://00000000-0000-4000-8000-000000000001@192.0.2.1:443?security=tls&type=tcp&sni=example.org#One"
    private val second="socks5://192.0.2.2:1080#Two"
    @Before fun setup() {
        Security.insertProviderAt(object:Provider("SubscriptionTestKeys",1.0,"test only") {
            init {put("KeyStore.AndroidKeyStore",TestKeyStore::class.java.name)}
        },1)
        context=RuntimeEnvironment.getApplication()
        java.io.File(context.noBackupFilesDir,"wireguard-profiles").deleteRecursively()
        context.getSharedPreferences("selected-wireguard-profile",0).edit().clear().commit()
        store=ProfileStore(context)
    }
    @After fun cleanup(){ Security.removeProvider("SubscriptionTestKeys") }
    @Test fun refreshKeepsSelectionAndDeletionPreservesStandalone() {
        val standalone=store.`import`("Local",second.toByteArray(),false)
        store.replaceSubscription(id,"Test",url,SubscriptionDecoder.decode(first))
        val managed=store.subscriptions().single().profileIds.single()
        store.select(managed)
        store.replaceSubscription(id,"Test",url,SubscriptionDecoder.decode("$first\n$second"))
        assertEquals(managed,store.selected()!!.id)
        assertEquals(3,store.list().size)
        store.deleteSubscription(id)
        assertEquals(listOf(standalone.id),store.list().map{it.id})
        assertNull(store.selected())
    }
    @Test fun badRefreshAndUncommittedFilesDoNotReplaceGoodSnapshot() {
        store.replaceSubscription(id,"Test",url,SubscriptionDecoder.decode(first))
        val old=store.list().single().id
        val failure=runCatching {store.replaceSubscription(id,"Test",url,listOf(SubscriptionEntry("Two",second.toByteArray()),SubscriptionEntry("Bad","invalid".toByteArray())))}
        assertTrue(failure.isFailure)
        assertEquals(listOf(old),store.list().map{it.id})
        val directory=java.io.File(context.noBackupFilesDir,"wireguard-profiles")
        java.io.File(directory,UUID.randomUUID().toString()+".gws").writeText("uncommitted")
        assertEquals(listOf(old),ProfileStore(context).inspect().profiles.map{it.id})
        directory.listFiles()!!.forEach {file ->
            val raw=file.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(raw.contains("synthetic-secret"));assertFalse(raw.contains("vless://"))
        }
    }
    @Test fun individualDeleteCanBeRestoredByNextRefresh() {
        store.replaceSubscription(id,"Test",url,SubscriptionDecoder.decode(first))
        store.delete(store.list().single().id)
        assertTrue(store.list().isEmpty())
        store.replaceSubscription(id,"Test",url,SubscriptionDecoder.decode(first))
        assertEquals(1,store.list().size)
    }
}
