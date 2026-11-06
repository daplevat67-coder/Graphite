package art.graphite.vpn.data.importing
/** Deliberately not saveable; never log incoming keys or URL tokens. */
data class IncomingImportRequest(val id:Long,val text:String) {override fun toString()="IncomingImportRequest(redacted)"}
