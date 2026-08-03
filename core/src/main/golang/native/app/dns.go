package app

import (
	"strings"
	"sync"

	"github.com/metacubex/mihomo/dns"
)

var (
	systemDNSMu sync.RWMutex
	systemDNS   []string
)

func NotifyDnsChanged(dnsList string) {
	var addr []string
	if len(dnsList) > 0 {
		addr = strings.Split(dnsList, ",")
	}

	systemDNSMu.Lock()
	systemDNS = append(systemDNS[:0], addr...)
	systemDNSMu.Unlock()

	dns.UpdateSystemDNS(addr)
	dns.FlushCacheWithDefaultResolver()
}

func SystemDNS() []string {
	systemDNSMu.RLock()
	defer systemDNSMu.RUnlock()

	return append([]string(nil), systemDNS...)
}
