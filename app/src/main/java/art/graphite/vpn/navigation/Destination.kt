package art.graphite.vpn.navigation

import art.graphite.vpn.R

enum class Destination(val label: Int, val icon: String) {
    HOME(R.string.home,"home"), PROTOCOLS(R.string.protocols,"layers"),
    SERVERS(R.string.servers,"server"), PROFILE(R.string.profile,"user"), MENU(R.string.menu,"menu"),
    PING(R.string.servers,"server");
    companion object { val visible = listOf(HOME,PROTOCOLS,SERVERS,PROFILE,MENU) }
}
