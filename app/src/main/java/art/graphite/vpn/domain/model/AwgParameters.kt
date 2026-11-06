package art.graphite.vpn.domain.model

import org.amnezia.awg.config.Config
import java.util.Base64
import java.util.Locale

/** Pinned AWG3 settings. Values belong only to the encrypted profile/in-memory secure editor. */
object AwgParameters {
    val names = listOf("Jc","Jmin","Jmax","S1","S2","S3","S4","H1","H2","H3","H4","I1","I2","I3","I4","I5","HeaderProtectionKey","ContentPaddingAddition","RekeyAfterTime","RekeyTimeout","RejectAfterTime","KeepaliveTimeout","MaxHandshakeAttempts","RandomTrailers","DisableCookies")
    const val MAX_JUNK_PACKETS = 4096L
    const val MAX_JUNK_BYTES = 16L * 1024 * 1024
    private val canonical = names.associateBy { it.lowercase(Locale.ROOT) }
    private val ranges = setOf("ContentPaddingAddition","RekeyAfterTime","RekeyTimeout","RejectAfterTime","KeepaliveTimeout","MaxHandshakeAttempts")
    private val booleans = setOf("on","off","true","false","1","0","t","f","yes","no")

    fun fromInterfaceText(text: String): Map<String,String> = buildMap {
        text.lineSequence().forEach { line ->
            val pair = line.split('=',limit=2)
            if(pair.size==2) canonical[pair[0].trim().lowercase(Locale.ROOT)]?.let { name ->
                pair[1].trim().takeIf { it.isNotEmpty() }?.let { put(name,it) }
            }
        }
    }
    fun fromConfig(config:Config):Map<String,String> = fromInterfaceText(config.`interface`.toAwgQuickString())
    fun isAwg(config:Config):Boolean = fromConfig(config).isNotEmpty() || config.peers.any { it.persistentKeepalive.orElse("").contains('-') }
    /** Match official parser's comments, case and last assignment wins, including empty I2-I5. */
    fun fromRawConfig(text:String):Map<String,String> = buildMap {
        var inInterface=false
        text.lineSequence().forEach { original ->
            val line=original.substringBefore('#').trim()
            if(line.startsWith('[')) inInterface=line.equals("[Interface]",ignoreCase=true)
            else if(inInterface) {
                val pair=line.split('=',limit=2)
                if(pair.size==2)canonical[pair[0].trim().lowercase(Locale.ROOT)]?.let { put(it,pair[1].trim()) }
            }
        }
    }
    fun validate(values:Map<String,String>) {
        if(values.keys.any { it !in names } || values.values.any { value -> value.any { it=='\n'||it=='\r'||it=='\u0000' } })invalid()
        val active=values.filterValues { it.isNotBlank() }.mapValues { it.value.trim() }
        fun number(name:String,max:Long):Long = active[name]?.let { raw ->
            if(!raw.matches(Regex("[+]?[0-9]+")))invalid()
            raw.toLongOrNull()?.takeIf { it in 0..max } ?: invalid()
        } ?: 0L
        val count=number("Jc",MAX_JUNK_PACKETS)
        val minimum=number("Jmin",65535)
        val maximum=number("Jmax",65535)
        if(minimum>maximum || count*maximum>MAX_JUNK_BYTES)invalid()
        val padding=(1..4).map { number("S$it",if(it==4)65519 else 65535) }
        val headers=(1..4).map { index -> active["H$index"]?.let(::range) ?: index.toLong()..index.toLong() }
        headers.forEachIndexed { i,left -> headers.drop(i+1).forEach { right -> if(left.first<=right.last && right.first<=left.last)invalid() } }
        ranges.forEach { name -> active[name]?.let(::range) }
        listOf("RandomTrailers","DisableCookies").forEach { name -> active[name]?.let { if(it.lowercase(Locale.ROOT) !in booleans)invalid() } }
        active["HeaderProtectionKey"]?.let { key ->
            val bytes=try { Base64.getDecoder().decode(key) } catch(_:IllegalArgumentException){invalid()}
            try { if(bytes.size!=32 || bytes.any { it!=0.toByte() } && padding.any { it<12 })invalid() } finally { bytes.fill(0) }
        }
        (1..5).forEach { i -> active["I$i"]?.let(::validateTemplate) }
    }
    fun validatePersistentKeepalive(value:String) { if(value.isNotBlank())range(value.trim()) }
    private fun range(value:String):LongRange {
        val m=Regex("([0-9]+)(?:-([0-9]+))?").matchEntire(value) ?: invalid()
        val low=m.groupValues[1].toLongOrNull()?.takeIf { it in 0..0xffffffffL } ?: invalid()
        val high=if(m.groupValues[2].isEmpty())low else m.groupValues[2].toLongOrNull() ?: invalid()
        if(high !in low..0xffffffffL)invalid()
        return low..high
    }
    private fun validateTemplate(value:String) {
        var position=0
        var length=0L
        var tags=0
        while(position<value.length) {
            if(value[position].isWhitespace()){position++;continue}
            if(value[position]!='<')invalid()
            val end=value.indexOf('>',position+1).takeIf { it>=0 } ?: invalid()
            val parts=value.substring(position+1,end).trim().split(Regex("\\s+"))
            if(parts.size !in 1..2)invalid()
            val arg=parts.getOrNull(1)
            length+=when(parts[0]) {
                "b" -> { val hex=arg?.removePrefix("0x") ?: invalid();if(hex.isEmpty()||hex.length%2!=0||!hex.matches(Regex("[0-9a-fA-F]+")))invalid();hex.length/2L }
                "r","rc","rd","dz" -> arg?.toLongOrNull()?.takeIf { it in 0..65535 } ?: invalid()
                "t" -> 4L
                "d","ds" -> 0L
                else -> invalid()
            }
            if(length>65535)invalid()
            tags++;position=end+1
        }
        if(tags==0)invalid()
    }
    private fun invalid():Nothing = throw AwgParametersException()
}
class AwgParametersException : Exception("INVALID_AWG_CONFIG")
