package app

import (
	"context"
	"net"
	"sync/atomic"
	"testing"
	"time"

	"github.com/miekg/dns"
)

func directDNSServer(t *testing.T, handler dns.Handler) string {
	t.Helper()
	conn, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	server := &dns.Server{PacketConn: conn, Handler: handler}
	go func() { _ = server.ActivateAndServe() }()
	t.Cleanup(func() { _ = server.Shutdown(); _ = conn.Close() })
	return conn.LocalAddr().String()
}

func TestDirectDNSBypassesCachedFailure(t *testing.T) {
	var ready atomic.Bool
	server := directDNSServer(t, dns.HandlerFunc(func(w dns.ResponseWriter, query *dns.Msg) {
		response := new(dns.Msg)
		response.SetReply(query)
		if !ready.Load() {
			response.Rcode = dns.RcodeNameError
		} else if query.Question[0].Qtype == dns.TypeA {
			response.Answer = []dns.RR{&dns.A{
				Hdr: dns.RR_Header{Name: query.Question[0].Name, Rrtype: dns.TypeA, Class: dns.ClassINET, Ttl: 300},
				A:   net.ParseIP("192.0.2.10"),
			}}
		}
		_ = w.WriteMsg(response)
	}))
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, err := resolveDirectHost(ctx, "test.example", []string{server}); err == nil {
		t.Fatal("expected initial DNS failure")
	}
	ready.Store(true)
	addresses, err := resolveDirectHost(ctx, "test.example", []string{server})
	if err != nil {
		t.Fatal(err)
	}
	if len(addresses) != 1 || addresses[0] != "192.0.2.10" {
		t.Fatalf("addresses = %v, want [192.0.2.10]", addresses)
	}
}

func TestDirectDNSProtectFailure(t *testing.T) {
	previous := markSocketImpl
	var attempted atomic.Int32
	markSocketImpl = func(int) bool { attempted.Add(1); return false }
	defer func() { markSocketImpl = previous }()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if _, err := resolveDirectHost(ctx, "test.example", []string{"127.0.0.1:53"}); err == nil {
		t.Fatal("DNS lookup must fail when socket protection is refused")
	}
	if attempted.Load() == 0 {
		t.Fatal("DNS lookup did not request socket protection")
	}
}

func TestDirectDNSRequiresLiteralServers(t *testing.T) {
	if _, err := resolveDirectHost(context.Background(), "test.example", nil); err == nil {
		t.Fatal("expected error without physical network DNS servers")
	}
	if _, err := resolveDirectHost(context.Background(), "test.example", []string{"dns.example:53"}); err == nil {
		t.Fatal("DNS server names must not trigger recursive system resolution")
	}
	addresses, err := resolveDirectHost(context.Background(), "2001:db8::1", nil)
	if err != nil || len(addresses) != 1 || addresses[0] != "2001:db8::1" {
		t.Fatalf("literal address lookup = %v, %v", addresses, err)
	}
}
