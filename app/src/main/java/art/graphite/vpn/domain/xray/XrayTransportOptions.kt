package art.graphite.vpn.domain.xray

import org.json.JSONObject
import org.json.JSONTokener

/** Only client transport settings go into the generated outbound. */
internal object XrayTransportOptions {
    private val xhttp = setOf(
        "headers", "xPaddingBytes", "xPaddingObfsMode", "xPaddingKey", "xPaddingHeader",
        "xPaddingPlacement", "xPaddingMethod", "uplinkHTTPMethod", "sessionIDPlacement",
        "sessionIDKey", "sessionIDTable", "sessionIDLength", "seqPlacement", "seqKey",
        "uplinkDataPlacement", "uplinkDataKey", "uplinkChunkSize", "noGRPCHeader", "noSSEHeader",
        "scMaxEachPostBytes", "scMinPostsIntervalMs", "scMaxBufferedPosts", "scStreamUpServerSecs",
        "serverMaxHeaderBytes", "xmux",
    )
    private val grpc = setOf("idle_timeout", "health_check_timeout", "permit_without_stream", "initial_windows_size", "user_agent")
    private val kcp = setOf("mtu", "tti", "uplinkCapacity", "downlinkCapacity", "cwndMultiplier", "maxSendingWindow")

    fun fromLink(network: String, query: Map<String, String>): String {
        require(!query.containsKey("extra") || network == "xhttp")
        require(kcp.none(query::containsKey) || network == "kcp")
        val options = JSONObject()
        if (network == "kcp") {
            for (key in kcp) query[key]?.let { options.put(key, it.toLong()) }
        }
        if (network == "xhttp") query["extra"]?.let {
            val parsed = parse(it)
            require(parsed.keys().asSequence().all { key -> key in xhttp })
            return fromJson(network, parsed)
        }
        return fromJson(network, options)
    }

    fun fromJson(network: String, source: JSONObject): String {
        val keys = when (network) {
            "xhttp" -> xhttp
            "grpc" -> grpc
            "kcp" -> kcp
            else -> return ""
        }
        val input = if (network == "xhttp" && source.has("extra")) source.getJSONObject("extra") else source
        if (network == "xhttp") {
            require(!input.has("downloadSettings") && !source.has("downloadSettings"))
            if (input !== source) require(input.keys().asSequence().all { it in keys })
        }
        if (network == "kcp") {
            require(!source.has("seed") && (!source.has("header") || source.getJSONObject("header").optString("type", "none") == "none"))
        }
        val result = JSONObject()
        for (key in keys) {
            if (!input.has(key)) continue
            val value = input.get(key)
            if (network == "kcp") {
                val number = value.toString().toLong()
                require(number in 0..Int.MAX_VALUE.toLong())
                require(key != "mtu" || number in 21..65535)
                require(key != "tti" || number in 10..1000)
                require(key != "cwndMultiplier" || number >= 1)
                result.put(key, number)
            } else result.put(key, value)
        }
        return if (result.length() == 0) "" else result.toString()
    }

    fun apply(network: String, options: String, target: JSONObject): JSONObject {
        if (options.isEmpty()) return target
        val parsed = parse(fromJson(network, parse(options)))
        for (key in parsed.keys()) target.put(key, parsed.get(key))
        return target
    }

    private fun parse(text: String): JSONObject {
        XrayProfileParser.boundDepth(text, 16)
        val reader = JSONTokener(text)
        val json = reader.nextValue() as JSONObject
        require(reader.nextClean() == '\u0000')
        return json
    }
}
