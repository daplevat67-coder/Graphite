package art.graphite.vpn.data.demo

import art.graphite.vpn.R
import art.graphite.vpn.domain.model.Protocol
val protocols = listOf(
    Protocol("awg","AmneziaWG",R.string.awg_desc,".conf"),
    Protocol("wg","WireGuard",R.string.wg_desc,".conf"),
    Protocol("openvpn","OpenVPN",R.string.ovpn_desc,".ovpn"),
    Protocol("ikev2","IPsec / IKEv2",R.string.ipsec_desc,"IKEv2"),
    Protocol("vless","VLESS",R.string.vless_desc,"vless://…","link"),
    Protocol("vmess","VMess",R.string.vmess_desc,"vmess://…","layers"),
    Protocol("mtproto","MTProto Proxy",R.string.mt_desc,"tg://proxy?…","link"),
    Protocol("socks5","SOCKS5",R.string.socks_desc,"socks5://…","server"),
    Protocol("other","Другие",R.string.others_desc,"—","menu")
)
