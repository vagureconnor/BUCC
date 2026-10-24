//go:build darwin && !ios

package physicalproxy

import "testing"

func TestPhysicalDHCPDNSParsingIgnoresOtherLeaseFields(t *testing.T) {
	packet := `yiaddr = 10.0.0.3
server_identifier (ip): 10.0.0.1
domain_name_server (ip_mult): {202.112.128.50, 202.112.128.51}
router (ip_mult): {10.0.0.1}`
	servers := parsePhysicalDHCPDNS(packet)
	if len(servers) != 2 || servers[0].String() != "202.112.128.50" || servers[1].String() != "202.112.128.51" {
		t.Fatal("DHCP DNS extraction failed")
	}
	for _, packet := range []string{"", `router (ip_mult): {10.0.0.1}`, `domain_name_server (ip_mult): {127.0.0.1, 0.0.0.0, 224.0.0.1, invalid}`} {
		if len(parsePhysicalDHCPDNS(packet)) != 0 {
			t.Fatal("invalid DNS server accepted")
		}
	}
}
