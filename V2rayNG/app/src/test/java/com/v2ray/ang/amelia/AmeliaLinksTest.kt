package com.v2ray.ang.amelia

import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreOutboundBuilder
import com.v2ray.ang.dto.V2rayConfig.OutboundBean
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import com.v2ray.ang.fmt.Hysteria2Fmt
import com.v2ray.ang.fmt.VlessFmt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Link shapes Amelia.Net subscriptions use, parsed by the upstream code and turned into
 * Xray stream settings. Device testing is still required for the actual connections.
 */
class AmeliaLinksTest {

    @Test
    fun hysteria2AntiblockLink() {
        // Same shape as build_hy2_msk1_link() in sub_server.py
        val link = "hy2://p4ssw0rd@79.137.192.178:8444/?sni=msk1.ameliajustamelia.com" +
            "&obfs=salamander&obfs-password=obfsSecret#%F0%9F%9B%A1%20Antiblock"
        val p = Hysteria2Fmt.parse(link)
        assertEquals(EConfigType.HYSTERIA2, p.configType)
        assertEquals("p4ssw0rd", p.password)
        assertEquals("8444", p.serverPort)
        assertEquals("msk1.ameliajustamelia.com", p.sni)
        assertEquals("obfsSecret", p.obfsPassword)
        assertEquals(AppConfig.TLS, p.security)

        p.network = NetworkType.HYSTERIA.type // what toOutboundHysteria2 does before this call
        val stream = OutboundBean.StreamSettingsBean()
        CoreOutboundBuilder.populateTransportSettings(stream, p)
        assertEquals(2, stream.hysteriaSettings?.version)
        assertEquals("p4ssw0rd", stream.hysteriaSettings?.auth)
        // finalmask is typed Any because a raw JSON override may replace it
        val mask = stream.finalmask as OutboundBean.StreamSettingsBean.FinalMaskBean
        assertEquals("salamander", mask.udp?.firstOrNull()?.type)
        assertEquals("obfsSecret", mask.udp?.firstOrNull()?.settings?.password)
    }

    @Test
    fun vlessRealityXhttpWithMlkemEncryption() {
        val enc = "mlkem768x25519plus.native.0rtt.dGVzdEtleQ"
        val link = "vless://11111111-2222-3333-4444-555555555555@1.2.3.4:443" +
            "?encryption=$enc&security=reality&sni=www.example.com&fp=chrome" +
            "&pbk=PUBLICKEY&sid=ab12&spx=%2F&type=xhttp&mode=auto&path=%2Fx&host=www.example.com#Test"
        val p = VlessFmt.parse(link)
        assertNotNull(p)
        p!!
        assertEquals(enc, p.method)
        assertEquals(AppConfig.REALITY, p.security)
        assertEquals("PUBLICKEY", p.publicKey)
        assertEquals("ab12", p.shortId)
        assertEquals(NetworkType.XHTTP.type, p.network)

        val stream = OutboundBean.StreamSettingsBean()
        CoreOutboundBuilder.populateTransportSettings(stream, p)
        assertEquals("xhttp", stream.network)
        assertEquals("auto", stream.xhttpSettings?.mode)
        assertEquals("/x", stream.xhttpSettings?.path)
    }
}
