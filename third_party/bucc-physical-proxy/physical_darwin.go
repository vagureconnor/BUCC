//go:build darwin && !ios

// SPDX-License-Identifier: AGPL-3.0-only
package physicalproxy

import (
	"context"
	"errors"
	"net"
	"os/exec"
	"regexp"
	"strings"
	"syscall"
	"time"

	"golang.org/x/sys/unix"
)

type physicalNetwork struct {
	index int
	dns   []net.IP
}

var dhcpDNSLine = regexp.MustCompile(`(?m)domain_name_server[^:\n]*:\s*\{([^}]*)\}`)

func parsePhysicalDHCPDNS(packet string) []net.IP {
	match := dhcpDNSLine.FindStringSubmatch(packet)
	if len(match) != 2 {
		return nil
	}
	var result []net.IP
	for _, value := range strings.FieldsFunc(match[1], func(r rune) bool { return r == ',' || r == ' ' || r == '\t' || r == '\n' }) {
		ip := net.ParseIP(value)
		if ip.To4() != nil && physicalUsableIP(ip) {
			result = append(result, ip)
		}
	}
	return result
}

func availablePhysicalNetworks(ctx context.Context) []physicalNetwork {
	interfaces, err := net.Interfaces()
	if err != nil {
		return nil
	}
	var result []physicalNetwork
	for _, iface := range interfaces {
		if !strings.HasPrefix(iface.Name, "en") || iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addresses, _ := iface.Addrs()
		active := false
		for _, address := range addresses {
			ip, _, _ := net.ParseCIDR(address.String())
			if ip.To4() != nil && ip.IsGlobalUnicast() && !ip.IsLinkLocalUnicast() {
				active = true
			}
		}
		if !active {
			continue
		}
		commandCtx, cancel := context.WithTimeout(ctx, time.Second)
		packet, err := exec.CommandContext(commandCtx, "/usr/sbin/ipconfig", "getpacket", iface.Name).Output()
		cancel()
		if err != nil {
			continue
		}
		if dns := parsePhysicalDHCPDNS(string(packet)); len(dns) != 0 {
			result = append(result, physicalNetwork{iface.Index, dns})
		}
	}
	return result
}

func (n physicalNetwork) dialer() *net.Dialer {
	return &net.Dialer{Timeout: 8 * time.Second, KeepAlive: 30 * time.Second, Control: func(network, address string, raw syscall.RawConn) error {
		var setError error
		err := raw.Control(func(fd uintptr) {
			if strings.HasSuffix(network, "6") {
				setError = unix.SetsockoptInt(int(fd), unix.IPPROTO_IPV6, unix.IPV6_BOUND_IF, n.index)
			} else {
				setError = unix.SetsockoptInt(int(fd), unix.IPPROTO_IP, unix.IP_BOUND_IF, n.index)
			}
		})
		if err != nil {
			return err
		}
		return setError
	}}
}

func (n physicalNetwork) resolve(ctx context.Context, host string) ([]net.IP, error) {
	var last error
	for _, server := range n.dns {
		resolver := &net.Resolver{PreferGo: true, StrictErrors: true, Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			// Go chooses UDP or TCP for DNS; both use the physical interface and DHCP server.
			return n.dialer().DialContext(ctx, network, net.JoinHostPort(server.String(), "53"))
		}}
		ips, err := resolver.LookupIP(ctx, "ip4", strings.TrimSuffix(host, ".")+".")
		if err == nil && len(ips) != 0 {
			return ips, nil
		}
		last = err
	}
	if last == nil {
		last = errors.New("physical_dns_unavailable")
	}
	return nil, last
}

func findPhysicalCampusNetwork(ctx context.Context) (physicalNetwork, error) {
	for _, network := range availablePhysicalNetworks(ctx) {
		ips, err := network.resolve(ctx, "sso.buaa.edu.cn")
		if err != nil {
			continue
		}
		for _, ip := range ips {
			if ip.IsPrivate() && !ip.IsLoopback() && !ip.IsLinkLocalUnicast() {
				return network, nil
			}
		}
	}
	return physicalNetwork{}, errors.New("physical_campus_unavailable")
}

func physicalCampusReady(ctx context.Context) error {
	_, err := findPhysicalCampusNetwork(ctx)
	return err
}

func physicalCampusDial(ctx context.Context, network, address string) (net.Conn, error) {
	ctx, cancel := context.WithTimeout(ctx, 8*time.Second)
	defer cancel()
	host, port, err := physicalCampusAddress(address)
	if err != nil {
		return nil, err
	}
	route, err := findPhysicalCampusNetwork(ctx)
	if err != nil {
		return nil, err
	}
	ips, err := route.resolve(ctx, host)
	if err != nil {
		return nil, err
	}
	for _, ip := range ips {
		// A campus DNS reply must never turn this loopback proxy into a local service tunnel.
		if !physicalUsableIP(ip) {
			continue
		}
		conn, dialErr := route.dialer().DialContext(ctx, network, net.JoinHostPort(ip.String(), port))
		if dialErr == nil {
			return conn, nil
		}
		err = dialErr
	}
	if err == nil {
		err = errors.New("physical_destination_unavailable")
	}
	return nil, err
}
