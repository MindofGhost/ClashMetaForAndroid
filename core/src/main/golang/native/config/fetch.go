package config

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	U "net/url"
	"os"
	P "path"
	"runtime"
	"strconv"
	"strings"
	"syscall"
	"time"

	"cfa/native/app"

	"github.com/metacubex/mihomo/adapter/provider"
	clashHttp "github.com/metacubex/mihomo/component/http"
	C "github.com/metacubex/mihomo/constant"
	RB "github.com/metacubex/mihomo/rules/bundle"
)

const fetchTimeout = 25 * time.Second

type Status struct {
	Action            string   `json:"action"`
	Args              []string `json:"args"`
	Progress          int      `json:"progress"`
	MaxProgress       int      `json:"max"`
	SubUpload         *int64   `json:"subUpload,omitempty"`
	SubDownload       *int64   `json:"subDownload,omitempty"`
	SubTotal          *int64   `json:"subTotal,omitempty"`
	SubExpire         *int64   `json:"subExpire,omitempty"`
	SubUpdateInterval *int64   `json:"subUpdateInterval,omitempty"`
}

type fetchHeader struct {
	SubscriptionUserInfo  string
	ProfileUpdateInterval string
}

type protectedDialer struct {
	net.Dialer
}

var _ C.Dialer = (*protectedDialer)(nil)

func (d *protectedDialer) DialContext(ctx context.Context, network, address string) (net.Conn, error) {
	return d.Dialer.DialContext(ctx, network, address)
}

func (d *protectedDialer) ListenPacket(ctx context.Context, network, address string, _ netip.AddrPort) (net.PacketConn, error) {
	listener := net.ListenConfig{Control: d.Dialer.Control}
	return listener.ListenPacket(ctx, network, address)
}

func newProtectedDialer() *protectedDialer {
	dialer := &protectedDialer{
		Dialer: net.Dialer{
			Timeout:   20 * time.Second,
			KeepAlive: 30 * time.Second,
		},
	}
	dialer.Control = func(_, _ string, conn syscall.RawConn) error {
		return conn.Control(func(fd uintptr) {
			app.MarkSocket(int(fd))
		})
	}
	return dialer
}

func openUrl(ctx context.Context, url string, direct bool) (io.ReadCloser, fetchHeader, error) {
	header := http.Header{"User-Agent": {"ClashMetaForAndroid/" + app.VersionName()}}
	if hwid := app.Hwid(); hwid != "" {
		header.Set("x-hwid", hwid)
	}

	options := []clashHttp.Option{}
	if direct {
		options = append(options, clashHttp.WithDialer(newProtectedDialer()))
	}

	response, err := clashHttp.HttpRequest(ctx, url, http.MethodGet, header, nil, options...)
	if err != nil {
		return nil, fetchHeader{}, err
	}
	if response.StatusCode < http.StatusOK || response.StatusCode >= http.StatusMultipleChoices {
		_ = response.Body.Close()
		return nil, fetchHeader{}, fmt.Errorf("unexpected http status %d of %s", response.StatusCode, url)
	}

	return response.Body, fetchHeader{
		SubscriptionUserInfo:  response.Header.Get("subscription-userinfo"),
		ProfileUpdateInterval: response.Header.Get("profile-update-interval"),
	}, nil
}

func openContent(url string) (io.ReadCloser, error) {
	return app.OpenContent(url)
}

func fetch(url *U.URL, file string) (fetchHeader, error) {
	attempts := 1
	httpURL := url.Scheme == "http" || url.Scheme == "https"
	if httpURL {
		attempts = 3
	}

	var lastErr error
	for attempt := 0; attempt < attempts; attempt++ {
		if attempt > 0 {
			time.Sleep(time.Duration(attempt) * time.Second)
		}

		header, err := fetchOnceWithRoute(url, file, false)
		if err == nil {
			return header, nil
		}
		lastErr = err

		if httpURL {
			header, err = fetchOnceWithRoute(url, file, true)
			if err == nil {
				return header, nil
			}
			lastErr = fmt.Errorf("%w; direct retry failed: %v", lastErr, err)
		}
	}

	return fetchHeader{}, lastErr
}

func fetchOnceWithRoute(url *U.URL, file string, direct bool) (fetchHeader, error) {
	ctx, cancel := context.WithTimeout(context.Background(), fetchTimeout)
	defer cancel()

	var reader io.ReadCloser
	var header fetchHeader
	var err error
	switch url.Scheme {
	case "http", "https":
		reader, header, err = openUrl(ctx, url.String(), direct)
	case "content":
		reader, err = openContent(url.String())
	default:
		err = fmt.Errorf("unsupported scheme %s of %s", url.Scheme, url)
	}
	if err != nil {
		return fetchHeader{}, err
	}
	defer reader.Close()

	return header, writeFile(file, reader)
}

func writeFile(file string, reader io.Reader) error {
	_ = os.MkdirAll(P.Dir(file), 0700)
	tmp := file + ".tmp"
	f, err := os.OpenFile(tmp, os.O_WRONLY|os.O_TRUNC|os.O_CREATE, 0600)
	if err != nil {
		return err
	}
	if _, err = io.Copy(f, reader); err != nil {
		_ = f.Close()
		_ = os.Remove(tmp)
		return err
	}
	if err = f.Close(); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	if err = os.Rename(tmp, file); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	return nil
}

func parseProfileUpdateInterval(value string) (int64, bool) {
	hours, err := strconv.ParseInt(strings.TrimSpace(value), 10, 64)
	if err != nil {
		return 0, false
	}
	if hours <= 0 {
		return 0, true
	}
	interval := time.Duration(hours) * time.Hour
	if interval < 15*time.Minute {
		interval = 15 * time.Minute
	}
	return int64(interval / time.Millisecond), true
}

func reportSubscriptionInfo(header fetchHeader, reportStatus func(string)) {
	if header.SubscriptionUserInfo == "" && header.ProfileUpdateInterval == "" {
		return
	}
	status := Status{Action: "SubscriptionInfo", Args: []string{}, Progress: -1, MaxProgress: -1}
	if header.SubscriptionUserInfo != "" {
		info := provider.NewSubscriptionInfo(header.SubscriptionUserInfo)
		expire := info.Expire * 1000
		status.SubUpload = &info.Upload
		status.SubDownload = &info.Download
		status.SubTotal = &info.Total
		status.SubExpire = &expire
	}
	if interval, ok := parseProfileUpdateInterval(header.ProfileUpdateInterval); ok {
		status.SubUpdateInterval = &interval
	}
	bytes, _ := json.Marshal(&status)
	reportStatus(string(bytes))
}

func FetchAndValid(path string, rawURL string, force bool, reportStatus func(string)) error {
	configPath := P.Join(path, "config.yaml")
	if _, err := os.Stat(configPath); os.IsNotExist(err) || force {
		url, err := U.Parse(rawURL)
		if err != nil {
			return err
		}
		bytes, _ := json.Marshal(&Status{
			Action: "FetchConfiguration", Args: []string{url.Host}, Progress: -1, MaxProgress: -1,
		})
		reportStatus(string(bytes))

		header, err := fetch(url, configPath)
		if err != nil {
			return err
		}
		reportSubscriptionInfo(header, reportStatus)
	}

	defer runtime.GC()
	rawCfg, err := UnmarshalAndPatch(path)
	if err != nil {
		return err
	}

	var providerErr error
	forEachProviders(rawCfg, func(index int, total int, name string, providerConfig map[string]any, prefix string) {
		if providerErr != nil {
			return
		}
		bytes, _ := json.Marshal(&Status{
			Action: "FetchProviders", Args: []string{name}, Progress: index, MaxProgress: total,
		})
		reportStatus(string(bytes))

		u, uok := providerConfig["url"]
		p, pok := providerConfig["path"]
		if !uok || !pok {
			return
		}
		us, uok := u.(string)
		ps, pok := p.(string)
		if !uok || !pok {
			return
		}
		if _, err := os.Stat(ps); err == nil && !force {
			return
		}
		url, err := U.Parse(us)
		if err != nil {
			providerErr = fmt.Errorf("parse provider %s url: %w", name, err)
			return
		}

		if prefix == RULES {
			if pib, ok := providerConfig["path-in-bundle"].(string); ok && pib != "" {
				if file, err := RB.Open(pib); err == nil {
					defer file.Close()
					if err := writeFile(ps, file); err == nil {
						return
					}
				}
			}
		}
		if _, err := fetch(url, ps); err != nil {
			providerErr = fmt.Errorf("fetch provider %s: %w", name, err)
		}
	})
	if providerErr != nil {
		return providerErr
	}

	bytes, _ := json.Marshal(&Status{
		Action: "Verifying", Args: []string{}, Progress: 0xffff, MaxProgress: 0xffff,
	})
	reportStatus(string(bytes))
	cfg, err := Parse(rawCfg)
	if err != nil {
		return err
	}
	destroyProviders(cfg)
	return nil
}
