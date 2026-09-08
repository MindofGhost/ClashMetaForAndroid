package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"strings"
	"time"
)

type freeTurnLegacyConfig struct {
	TURNHost       string
	TURNPort       string
	Listen         string
	VKLink         string
	VKLinks        string
	YandexLink     string
	PeerAddr       string
	NumStreams     int
	UseUDP         bool
	NoDTLS         bool
	VLESSMode      bool
	VLESSBond      bool
	WrapMode       bool
	WrapKeyHex     string
	ObfProfile     string
	ObfKey         string
	ObfTiming      time.Duration
	StreamsPerCred int
	Debug          bool
	ManualCaptcha  bool
	CaptchaSolver  string
	CaptchaHost    string
}

type freeTurnClientJSON struct {
	Peer     string             `json:"peer"`
	ClientID string             `json:"clientId"`
	Provider string             `json:"provider"`
	TURN     freeTurnTURNJSON   `json:"turn"`
	Proxy    freeTurnProxyJSON  `json:"proxy"`
	VK       freeTurnVKJSON     `json:"vk"`
	Obf      freeTurnObfJSON    `json:"obf"`
	DNS      freeTurnDNSJSON    `json:"dns"`
	Log      freeTurnLogJSON    `json:"log"`
	Tunnel   freeTurnTunnelJSON `json:"tunnel"`
}

type freeTurnTURNJSON struct {
	N         int    `json:"n"`
	Transport string `json:"transport"`
	Host      string `json:"host"`
	Port      string `json:"port"`
}

type freeTurnProxyJSON struct {
	Mode   string `json:"mode"`
	Listen string `json:"listen"`
}

type freeTurnVKJSON struct {
	Links          []string `json:"links"`
	StreamsPerCred int      `json:"streamsPerCred"`
	ManualCaptcha  bool     `json:"manualCaptcha"`
	Platform       string   `json:"platform"`
}

type freeTurnObfJSON struct {
	Profile  string `json:"profile"`
	Key      string `json:"key"`
	TimingMs int    `json:"timingMs"`
}

type freeTurnDNSJSON struct {
	Mode string `json:"mode"`
}

type freeTurnLogJSON struct {
	Debug bool `json:"debug"`
}

type freeTurnTunnelJSON struct {
	Mode string `json:"mode"`
	MTU  int    `json:"mtu"`
}

func freeTurnConfigJSONFromLegacyArgs(args []string, hwid string) (string, error) {
	cfg, err := parseFreeTurnLegacyConfig(args)
	if err != nil {
		return "", err
	}
	if cfg.YandexLink != "" {
		return "", fmt.Errorf("-yandex-link is not supported by free-turn-proxy mobile provider")
	}
	if cfg.NoDTLS {
		return "", fmt.Errorf("-no-dtls is not supported by free-turn-proxy mobile adapter")
	}
	if cfg.VLESSBond {
		notifyVkTurnEvent("[Config] -vless-bond is ignored by free-turn-proxy mobile adapter")
	}
	if cfg.CaptchaHost != "" {
		notifyVkTurnEvent("[Config] -captcha-host is ignored; mobile captcha URL is provided by the embedded solver")
	}

	links := splitCSV(cfg.VKLinks)
	if cfg.VKLink != "" {
		links = append(links, cfg.VKLink)
	}
	links = uniqueNonEmpty(links)
	if len(links) == 0 {
		return "", fmt.Errorf("missing -vk-link or -links")
	}
	if cfg.PeerAddr == "" {
		return "", fmt.Errorf("missing -peer")
	}

	transport := "tcp"
	if cfg.UseUDP {
		transport = "udp"
	}

	mode := "udp"
	if cfg.VLESSMode {
		mode = "tcp"
	}

	obfProfile := "none"
	obfKey := ""
	if cfg.WrapMode {
		obfProfile = "rtpopus"
		obfKey = cfg.WrapKeyHex
	}
	if strings.TrimSpace(cfg.ObfProfile) != "" {
		obfProfile = strings.TrimSpace(cfg.ObfProfile)
		obfKey = cfg.ObfKey
	}

	clientID := strings.TrimSpace(hwid)
	if clientID == "" {
		clientID = "cmfa"
	}

	out := freeTurnClientJSON{
		Peer:     cfg.PeerAddr,
		ClientID: clientID,
		Provider: "vk",
		TURN: freeTurnTURNJSON{
			N:         cfg.NumStreams,
			Transport: transport,
			Host:      cfg.TURNHost,
			Port:      cfg.TURNPort,
		},
		Proxy: freeTurnProxyJSON{
			Mode:   mode,
			Listen: cfg.Listen,
		},
		VK: freeTurnVKJSON{
			Links:          links,
			StreamsPerCred: cfg.StreamsPerCred,
			ManualCaptcha:  cfg.ManualCaptcha,
			Platform:       "mobile",
		},
		Obf: freeTurnObfJSON{
			Profile:  obfProfile,
			Key:      obfKey,
			TimingMs: int(cfg.ObfTiming / time.Millisecond),
		},
		DNS: freeTurnDNSJSON{
			Mode: "auto",
		},
		Log: freeTurnLogJSON{
			Debug: cfg.Debug,
		},
		Tunnel: freeTurnTunnelJSON{
			Mode: "none",
			MTU:  1280,
		},
	}

	data, err := json.Marshal(out)
	if err != nil {
		return "", err
	}
	return string(data), nil
}

func parseFreeTurnLegacyConfig(args []string) (freeTurnLegacyConfig, error) {
	cfg := freeTurnLegacyConfig{
		Listen:         "127.0.0.1:9000",
		NumStreams:     10,
		StreamsPerCred: 10,
		CaptchaSolver:  "v2",
	}

	flags := flag.NewFlagSet("vk-turn-proxy", flag.ContinueOnError)
	flags.StringVar(&cfg.TURNHost, "turn", cfg.TURNHost, "")
	flags.StringVar(&cfg.TURNPort, "port", cfg.TURNPort, "")
	flags.StringVar(&cfg.Listen, "listen", cfg.Listen, "")
	flags.StringVar(&cfg.VKLink, "vk-link", cfg.VKLink, "")
	flags.StringVar(&cfg.VKLinks, "links", cfg.VKLinks, "")
	flags.StringVar(&cfg.YandexLink, "yandex-link", cfg.YandexLink, "")
	flags.StringVar(&cfg.PeerAddr, "peer", cfg.PeerAddr, "")
	flags.IntVar(&cfg.NumStreams, "n", cfg.NumStreams, "")
	flags.BoolVar(&cfg.UseUDP, "udp", cfg.UseUDP, "")
	flags.BoolVar(&cfg.NoDTLS, "no-dtls", cfg.NoDTLS, "")
	flags.BoolVar(&cfg.VLESSMode, "vless", cfg.VLESSMode, "")
	flags.BoolVar(&cfg.VLESSBond, "vless-bond", cfg.VLESSBond, "")
	flags.BoolVar(&cfg.WrapMode, "wrap", cfg.WrapMode, "")
	flags.StringVar(&cfg.WrapKeyHex, "wrap-key", cfg.WrapKeyHex, "")
	flags.StringVar(&cfg.ObfProfile, "obf-profile", cfg.ObfProfile, "")
	flags.StringVar(&cfg.ObfKey, "obf-key", cfg.ObfKey, "")
	flags.DurationVar(&cfg.ObfTiming, "obf-timing", cfg.ObfTiming, "")
	flags.IntVar(&cfg.StreamsPerCred, "streams-per-cred", cfg.StreamsPerCred, "")
	flags.BoolVar(&cfg.Debug, "debug", cfg.Debug, "")
	flags.BoolVar(&cfg.ManualCaptcha, "manual-captcha", cfg.ManualCaptcha, "")
	flags.StringVar(&cfg.CaptchaSolver, "captcha-solver", cfg.CaptchaSolver, "")
	flags.StringVar(&cfg.CaptchaHost, "captcha-host", cfg.CaptchaHost, "")

	if err := flags.Parse(args); err != nil {
		return freeTurnLegacyConfig{}, err
	}
	if flags.NArg() > 0 {
		return freeTurnLegacyConfig{}, fmt.Errorf("unsupported positional arguments: %s", strings.Join(flags.Args(), " "))
	}
	if cfg.WrapMode && cfg.WrapKeyHex == "" {
		return freeTurnLegacyConfig{}, fmt.Errorf("-wrap requires -wrap-key")
	}

	return cfg, nil
}

func splitCSV(value string) []string {
	if strings.TrimSpace(value) == "" {
		return nil
	}
	parts := strings.Split(value, ",")
	out := make([]string, 0, len(parts))
	for _, part := range parts {
		out = append(out, strings.TrimSpace(part))
	}
	return out
}

func uniqueNonEmpty(values []string) []string {
	seen := map[string]struct{}{}
	out := make([]string, 0, len(values))
	for _, value := range values {
		value = strings.TrimSpace(value)
		if value == "" {
			continue
		}
		if _, ok := seen[value]; ok {
			continue
		}
		seen[value] = struct{}{}
		out = append(out, value)
	}
	return out
}
