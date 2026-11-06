package art.graphite.vpn.data.importing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext

/** Used only after the user confirms a decoded HTTPS address. No background subscription updates. */
object HttpsConfigFetcher {
    internal fun valid(uri:URI)=uri.scheme.equals("https",true) && !uri.host.isNullOrBlank() && uri.rawUserInfo==null && (uri.port==-1 || uri.port in 1..65535)
    internal fun sameOrigin(a:URI,b:URI)=a.host.equals(b.host,true) && (if(a.port==-1)443 else a.port)==(if(b.port==-1)443 else b.port) && valid(b)
    suspend fun fetch(uri:URI):String=withContext(Dispatchers.IO) {
        require(valid(uri)){"INVALID_HTTPS"}
        var current=uri
        repeat(4) {attempt->
            coroutineContext.ensureActive()
            val connection=current.toURL().openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects=false;connection.connectTimeout=5000;connection.readTimeout=10000
            connection.setRequestProperty("Accept-Encoding","identity")
            try {
                val code=connection.responseCode
                if(code in setOf(301,302,303,307,308)) {
                    require(attempt<3){"REDIRECT_LIMIT"}
                    val next=current.resolve(connection.getHeaderField("Location") ?: error("NO_REDIRECT"))
                    require(sameOrigin(uri,next)){"CROSS_ORIGIN_REDIRECT"};current=next
                } else {
                    require(code in 200..299){"HTTP_FAILURE"}
                    require(connection.contentLengthLong<=262144){"RESPONSE_TOO_LARGE"}
                    val bytes=connection.inputStream.use {input->
                        val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
                        try {
                            while(true){coroutineContext.ensureActive();val n=input.read(buffer);if(n<0)break;require(out.size()+n<=262144){"RESPONSE_TOO_LARGE"};out.write(buffer,0,n)}
                            out.toByteArray()
                        }finally{buffer.fill(0)}
                    }
                    return@withContext try{Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()}finally{bytes.fill(0)}
                }
            }finally{connection.disconnect()}
        }
        error("REDIRECT_LIMIT")
    }
}
