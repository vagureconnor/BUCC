// SPDX-License-Identifier: AGPL-3.0-only
package physicalproxy

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

type proxy struct {
	address     string
	listener    net.Listener
	server      *http.Server
	transport   *http.Transport
	mu          sync.Mutex
	tunnels     map[net.Conn]struct{}
	connections map[net.Conn]struct{}
	requests    map[*http.Request]context.CancelFunc
	paused      bool
	probeToken  string
	probeDial   func(context.Context, string, string) (net.Conn, error)
}

func newProxy(dial func(context.Context, string, string) (net.Conn, error)) (*proxy, error) {
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	var secret [32]byte
	if _, err := rand.Read(secret[:]); err != nil {
		listener.Close()
		return nil, err
	}
	p := &proxy{address: listener.Addr().String(), listener: listener, tunnels: make(map[net.Conn]struct{}), connections: make(map[net.Conn]struct{}), requests: make(map[*http.Request]context.CancelFunc), probeToken: hex.EncodeToString(secret[:])}
	p.transport = &http.Transport{DialContext: dial, Proxy: nil, TLSHandshakeTimeout: 10 * time.Second, ResponseHeaderTimeout: 15 * time.Second, IdleConnTimeout: 30 * time.Second}
	p.server = &http.Server{ReadHeaderTimeout: 10 * time.Second, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 64 << 10}
	p.server.ConnState = func(conn net.Conn, state http.ConnState) {
		p.mu.Lock()
		defer p.mu.Unlock()
		if state == http.StateNew {
			p.connections[conn] = struct{}{}
		}
		if state == http.StateClosed || state == http.StateHijacked {
			delete(p.connections, conn)
		}
	}
	p.server.Handler = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		internalProbe := r.Method == http.MethodConnect && r.Host == "uc.buaa.edu.cn:443" && subtle.ConstantTimeCompare([]byte(r.Header.Get("X-Bucc-Readiness-Probe")), []byte(p.probeToken)) == 1
		ctx, cancel := context.WithCancel(r.Context())
		defer cancel()
		p.mu.Lock()
		if p.paused && !internalProbe {
			p.mu.Unlock()
			http.Error(w, "campus validation required", http.StatusServiceUnavailable)
			return
		}
		requestKey := r
		if !internalProbe {
			p.requests[requestKey] = cancel
		}
		p.mu.Unlock()
		defer func() { p.mu.Lock(); delete(p.requests, requestKey); p.mu.Unlock() }()
		r = r.WithContext(ctx)
		if p.isSelf(r.Host) || p.isSelf(r.URL.Host) {
			http.Error(w, "proxy loop blocked", http.StatusForbidden)
			return
		}
		if r.Method == http.MethodConnect {
			selectedDial := dial
			if internalProbe && p.probeDial != nil {
				selectedDial = p.probeDial
			}
			target, err := selectedDial(r.Context(), "tcp", r.Host)
			if err != nil {
				http.Error(w, "campus destination unavailable", 502)
				return
			}
			h, ok := w.(http.Hijacker)
			if !ok {
				target.Close()
				http.Error(w, "unsupported tunnel", 500)
				return
			}
			conn, buf, err := h.Hijack()
			if err != nil {
				target.Close()
				return
			}
			p.mu.Lock()
			if p.paused && !internalProbe {
				p.mu.Unlock()
				conn.Close()
				target.Close()
				return
			}
			p.tunnels[conn] = struct{}{}
			p.tunnels[target] = struct{}{}
			p.mu.Unlock()
			defer func() {
				conn.Close()
				target.Close()
				p.mu.Lock()
				delete(p.tunnels, conn)
				delete(p.tunnels, target)
				p.mu.Unlock()
			}()
			buf.WriteString("HTTP/1.1 200 Connection Established\r\n\r\n")
			if buf.Flush() != nil {
				return
			}
			done := make(chan struct{}, 2)
			go func() { io.Copy(target, buf); done <- struct{}{} }()
			go func() { io.Copy(conn, target); done <- struct{}{} }()
			<-done
			conn.Close()
			target.Close()
			<-done
			return
		}
		if r.URL.Scheme != "http" && r.URL.Scheme != "https" {
			http.Error(w, "invalid proxy request", 400)
			return
		}
		req := r.Clone(r.Context())
		req.RequestURI = ""
		req.Header.Del("X-Bucc-Readiness-Probe")
		req.Header.Del("Proxy-Authorization")
		req.Header.Del("Proxy-Connection")
		res, err := p.transport.RoundTrip(req)
		if err != nil {
			http.Error(w, "campus destination unavailable", 502)
			return
		}
		defer res.Body.Close()
		for k, values := range res.Header {
			for _, v := range values {
				w.Header().Add(k, v)
			}
		}
		w.WriteHeader(res.StatusCode)
		io.Copy(w, res.Body)
	})
	go p.server.Serve(listener)
	return p, nil
}
func (p *proxy) close() {
	p.setPaused(true)
	p.server.Close()
	p.transport.CloseIdleConnections()
	p.mu.Lock()
	defer p.mu.Unlock()
	for conn := range p.tunnels {
		conn.Close()
	}
}

func (p *proxy) isSelf(address string) bool {
	host, port, err := net.SplitHostPort(address)
	if err != nil {
		return false
	}
	_, ownPort, _ := net.SplitHostPort(p.address)
	return port == ownPort && (host == "127.0.0.1" || strings.EqualFold(strings.TrimSuffix(host, "."), "localhost"))
}

// Fail closed, cancel in-flight forwarding, and close existing WebKit/Python tunnels.
// Requests rejected during validation are never retained or replayed.
func (p *proxy) setPaused(paused bool) {
	p.mu.Lock()
	p.paused = paused
	var conns []net.Conn
	var cancels []context.CancelFunc
	if paused {
		for conn := range p.connections {
			conns = append(conns, conn)
		}
		for conn := range p.tunnels {
			conns = append(conns, conn)
		}
		for _, cancel := range p.requests {
			cancels = append(cancels, cancel)
		}
	}
	p.mu.Unlock()
	for _, cancel := range cancels {
		cancel()
	}
	for _, conn := range conns {
		conn.Close()
	}
	if paused {
		p.transport.CloseIdleConnections()
	}
}
