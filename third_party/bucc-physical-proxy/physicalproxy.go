// SPDX-License-Identifier: AGPL-3.0-only
package physicalproxy

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

// PhysicalProxySession is a separate, credential-free desktop helper mode. It
// never creates a campus VPN session or changes system network preferences.
type PhysicalProxySession struct {
	mu    sync.Mutex
	proxy *proxy
	ready func(context.Context) error
	dial  func(context.Context, string, string) (net.Conn, error)
}

func NewPhysicalProxySession() *PhysicalProxySession {
	return &PhysicalProxySession{ready: physicalCampusReady, dial: physicalCampusDial}
}

func (s *PhysicalProxySession) Disconnect() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.proxy != nil {
		s.proxy.close()
		s.proxy = nil
	}
}

func (s *PhysicalProxySession) Exchange(raw string) (response string) {
	defer func() {
		if recover() != nil {
			s.Disconnect()
			response = `{"status":"FAILED","errorCode":"physical_proxy_failed"}`
		}
	}()
	var request struct {
		Op string `json:"op"`
	}
	if json.Unmarshal([]byte(raw), &request) != nil {
		return `{"status":"FAILED","errorCode":"invalid_command"}`
	}
	if request.Op == "disconnect" {
		s.Disconnect()
		return `{"status":"DISCONNECTED"}`
	}
	if request.Op != "endpoint" {
		return `{"status":"FAILED","errorCode":"invalid_command"}`
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if s.ready(ctx) != nil {
		s.Disconnect()
		return `{"status":"FAILED","errorCode":"physical_campus_unavailable"}`
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.proxy == nil {
		p, err := newPhysicalProxy(s.dial)
		if err != nil {
			return `{"status":"FAILED","errorCode":"physical_proxy_failed"}`
		}
		s.proxy = p
	}
	_, port, err := net.SplitHostPort(s.proxy.address)
	if err != nil {
		return `{"status":"FAILED","errorCode":"physical_proxy_failed"}`
	}
	return `{"status":"READY","port":` + port + `}`
}

func physicalUsableIP(ip net.IP) bool {
	if ip == nil || !ip.IsGlobalUnicast() || ip.IsLoopback() || ip.IsLinkLocalUnicast() || ip.IsMulticast() {
		return false
	}
	v4 := ip.To4()
	return v4 == nil || !(v4[0] == 198 && (v4[1] == 18 || v4[1] == 19))
}

func physicalCampusHost(host string) bool {
	host = strings.ToLower(strings.TrimSuffix(host, "."))
	if net.ParseIP(host) != nil || strings.ContainsAny(host, "@:/\\\x00") {
		return false
	}
	return strings.HasSuffix(host, ".buaa.edu.cn") && len(host) > len(".buaa.edu.cn")
}

func physicalCampusAddress(address string) (string, string, error) {
	host, port, err := net.SplitHostPort(address)
	if err != nil || !physicalCampusHost(host) {
		return "", "", errors.New("physical_destination_denied")
	}
	number, err := strconv.Atoi(port)
	if err != nil || number < 1 || number > 65535 {
		return "", "", errors.New("physical_destination_denied")
	}
	return strings.TrimSuffix(strings.ToLower(host), "."), port, nil
}

func newPhysicalProxy(dial func(context.Context, string, string) (net.Conn, error)) (*proxy, error) {
	p, err := newProxy(func(ctx context.Context, network, address string) (net.Conn, error) {
		if _, _, err := physicalCampusAddress(address); err != nil {
			return nil, err
		}
		return dial(ctx, network, address)
	})
	if err != nil {
		return nil, err
	}
	p.transport.DisableKeepAlives = true
	forward := p.server.Handler
	p.server.Handler = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		host := r.Host
		if r.Method != http.MethodConnect {
			if r.URL.Scheme != "http" && r.URL.Scheme != "https" {
				http.Error(w, "invalid campus request", 400)
				return
			}
			host = r.URL.Hostname()
			if !physicalCampusHost(host) || !strings.EqualFold(strings.TrimSuffix(r.Host, "."), strings.TrimSuffix(r.URL.Host, ".")) {
				http.Error(w, "campus destination denied", 403)
				return
			}
		} else if _, _, err := physicalCampusAddress(host); err != nil {
			http.Error(w, "campus destination denied", 403)
			return
		}
		if !physicalCampusHost(strings.Split(host, ":")[0]) {
			http.Error(w, "campus destination denied", 403)
			return
		}
		forward.ServeHTTP(w, r)
	})
	return p, nil
}
