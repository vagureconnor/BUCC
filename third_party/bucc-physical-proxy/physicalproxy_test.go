package physicalproxy

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestPhysicalProxyOnlyAcceptsCampusOriginalNames(t *testing.T) {
	for _, address := range []string{"sso.buaa.edu.cn:443", "UC.BUAA.EDU.CN:443", "portal.buaa.edu.cn:8443"} {
		if _, _, err := physicalCampusAddress(address); err != nil {
			t.Fatalf("campus address refused: %s", address)
		}
	}
	for _, address := range []string{"buaa.edu.cn:443", "buaa.edu.cn.evil.invalid:443", "buaa.edu.cn@127.0.0.1:443", "127.0.0.1:443", "10.0.0.1:443", "[::1]:443", "localhost:80", "sso.buaa.edu.cn:0", "sso.buaa.edu.cn:65536"} {
		if _, _, err := physicalCampusAddress(address); err == nil {
			t.Fatalf("non-campus address accepted: %s", address)
		}
	}
}

func TestPhysicalProxySessionRequiresCampusReadiness(t *testing.T) {
	var fail atomic.Bool
	fail.Store(true)
	s := &PhysicalProxySession{ready: func(context.Context) error {
		if fail.Load() {
			return errors.New("private network detail")
		}
		return nil
	}, dial: func(context.Context, string, string) (net.Conn, error) { return nil, errors.New("not used") }}
	if got := s.Exchange(`{"op":"endpoint"}`); got != `{"status":"FAILED","errorCode":"physical_campus_unavailable"}` {
		t.Fatal(got)
	}
	fail.Store(false)
	var response struct {
		Status string
		Port   int
	}
	if err := json.Unmarshal([]byte(s.Exchange(`{"op":"endpoint"}`)), &response); err != nil || response.Status != "READY" || response.Port < 1 {
		t.Fatal("missing endpoint")
	}
	if got := s.Exchange(`{"op":"disconnect"}`); got != `{"status":"DISCONNECTED"}` {
		t.Fatal(got)
	}
	c, err := net.DialTimeout("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(response.Port)), 100*time.Millisecond)
	if err == nil {
		c.Close()
		t.Fatal("disconnected proxy still listening")
	}
}

func TestPhysicalProxyForwardsPostOnceAndRejectsOffCampus(t *testing.T) {
	var posts atomic.Int32
	upstream := http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { posts.Add(1); io.WriteString(w, "fixture") })}
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	go upstream.Serve(listener)
	defer upstream.Close()
	p, err := newPhysicalProxy(func(ctx context.Context, network, address string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, network, listener.Addr().String())
	})
	if err != nil {
		t.Fatal(err)
	}
	defer p.close()
	endpoint, _ := url.Parse("http://" + p.address)
	client := &http.Client{Transport: &http.Transport{Proxy: http.ProxyURL(endpoint)}, Timeout: time.Second}
	for _, target := range []string{"http://127.0.0.1/private", "http://other.invalid/private", "http://buaa.edu.cn.evil.invalid/private"} {
		res, err := client.Post(target, "text/plain", strings.NewReader("fixture"))
		if err != nil {
			t.Fatal(err)
		}
		res.Body.Close()
		if res.StatusCode != 403 {
			t.Fatal("non-campus request forwarded")
		}
	}
	res, err := client.Post("http://sso.buaa.edu.cn/fixture", "text/plain", strings.NewReader("fixture"))
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != 200 || posts.Load() != 1 {
		t.Fatalf("POST count %d", posts.Load())
	}
	client.CloseIdleConnections()
}

func TestPhysicalProxyClosesExistingConnectAndConcurrentLifecycle(t *testing.T) {
	peers := make(chan net.Conn, 1)
	p, err := newPhysicalProxy(func(context.Context, string, string) (net.Conn, error) { a, b := net.Pipe(); peers <- b; return a, nil })
	if err != nil {
		t.Fatal(err)
	}
	conn, err := net.Dial("tcp", p.address)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	io.WriteString(conn, "CONNECT sso.buaa.edu.cn:443 HTTP/1.1\r\nHost: sso.buaa.edu.cn:443\r\n\r\n")
	response, err := http.ReadResponse(bufio.NewReader(conn), &http.Request{Method: "CONNECT"})
	if err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != 200 {
		t.Fatal("CONNECT refused")
	}
	peer := <-peers
	defer peer.Close()
	p.close()
	peer.SetReadDeadline(time.Now().Add(time.Second))
	if _, err = peer.Read(make([]byte, 1)); err == nil {
		t.Fatal("upstream tunnel not closed")
	}
	s := &PhysicalProxySession{ready: func(context.Context) error { return nil }, dial: func(context.Context, string, string) (net.Conn, error) { return nil, errors.New("not used") }}
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < 10; j++ {
				s.Exchange(`{"op":"endpoint"}`)
				s.Disconnect()
			}
		}()
	}
	wg.Wait()
	s.Disconnect()
}

func TestPhysicalDestinationsRejectLocalAndFakeIPAnswers(t *testing.T) {
	for _, address := range []string{"127.0.0.1", "::1", "0.0.0.0", "169.254.1.2", "224.0.0.1", "198.18.0.1", "198.19.255.255", "::ffff:198.18.1.2"} {
		if physicalUsableIP(net.ParseIP(address)) {
			t.Fatalf("unsafe destination accepted: %s", address)
		}
	}
	for _, address := range []string{"10.254.15.1", "172.16.1.2", "192.168.1.2", "121.194.4.13"} {
		if !physicalUsableIP(net.ParseIP(address)) {
			t.Fatalf("campus destination refused: %s", address)
		}
	}
}
