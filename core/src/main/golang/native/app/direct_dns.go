package app

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/miekg/dns"
)

// ResolveDirectHost uses the physical network's DNS servers, without Android or Clash caches.
func ResolveDirectHost(ctx context.Context, host string) ([]string, error) {
	return resolveDirectHost(ctx, host, SystemDNS())
}

func resolveDirectHost(ctx context.Context, host string, servers []string) ([]string, error) {
	if address, err := netip.ParseAddr(host); err == nil {
		return []string{address.String()}, nil
	}
	if _, valid := dns.IsDomainName(host); !valid || strings.TrimSpace(host) == "" {
		return nil, errors.New("invalid DNS hostname")
	}
	if len(servers) == 0 {
		return nil, errors.New("physical network DNS servers are unavailable")
	}

	var addresses [2][]string
	var queryErrors [2]error
	var wg sync.WaitGroup
	for i, qtype := range []uint16{dns.TypeA, dns.TypeAAAA} {
		wg.Add(1)
		go func() {
			defer wg.Done()
			addresses[i], queryErrors[i] = queryDirectDNS(ctx, host, qtype, servers)
		}()
	}
	wg.Wait()
	resolved := append(addresses[0], addresses[1]...)
	if len(resolved) != 0 {
		return resolved, nil
	}
	if err := errors.Join(queryErrors[:]...); err != nil {
		return nil, fmt.Errorf("resolve %s: %w", host, err)
	}
	return nil, fmt.Errorf("resolve %s: no A or AAAA records", host)
}

func queryDirectDNS(ctx context.Context, host string, qtype uint16, servers []string) ([]string, error) {
	query := new(dns.Msg)
	query.SetQuestion(dns.Fqdn(host), qtype)
	dialer := &net.Dialer{Timeout: 1500 * time.Millisecond, Control: func(_, _ string, raw syscall.RawConn) error {
		var protectErr error
		if err := raw.Control(func(fd uintptr) {
			if !MarkSocket(int(fd)) {
				protectErr = errors.New("cannot protect DNS socket from VPN")
			}
		}); err != nil {
			return err
		}
		return protectErr
	}}
	var lastErr error
	for _, server := range servers {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		serverHost, _, err := net.SplitHostPort(server)
		if err == nil {
			_, err = netip.ParseAddr(serverHost)
		}
		if err != nil {
			lastErr = fmt.Errorf("invalid DNS server %q: %w", server, err)
			continue
		}
		client := &dns.Client{Net: "udp", Dialer: dialer, Timeout: 1500 * time.Millisecond}
		response, _, err := client.ExchangeContext(ctx, query, server)
		if err == nil && response.Truncated {
			client.Net = "tcp"
			response, _, err = client.ExchangeContext(ctx, query, server)
		}
		if err != nil {
			lastErr = fmt.Errorf("DNS server %s: %w", server, err)
			continue
		}
		if response.Rcode != dns.RcodeSuccess {
			lastErr = fmt.Errorf("DNS server %s returned %s", server, dns.RcodeToString[response.Rcode])
			continue
		}
		var addresses []string
		for _, answer := range response.Answer {
			switch record := answer.(type) {
			case *dns.A:
				if qtype == dns.TypeA {
					addresses = append(addresses, record.A.String())
				}
			case *dns.AAAA:
				if qtype == dns.TypeAAAA {
					addresses = append(addresses, record.AAAA.String())
				}
			}
		}
		return addresses, nil
	}
	return nil, lastErr
}
