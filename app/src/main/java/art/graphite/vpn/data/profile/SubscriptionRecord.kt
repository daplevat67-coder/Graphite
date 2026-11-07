package art.graphite.vpn.data.profile

/** URLs may contain credentials. Never render or log this object. */
class SubscriptionRecord(val id: String, val name: String, val url: String, val profileIds: List<String>, val updatedAt: Long) {
    override fun toString() = "SubscriptionRecord(redacted)"
}
