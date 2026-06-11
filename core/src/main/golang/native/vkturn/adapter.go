package vkturn

import (
	"context"
	"flag"
)

func ParseConfig(args []string) (Config, error) {
	var cfg Config

	flags := flag.NewFlagSet("vk-turn-proxy", flag.ContinueOnError)
	genWrapKey := flags.Bool("gen-wrap-key", false, "print a fresh 64-character hex key for -wrap-key and exit")
	flags.StringVar(&cfg.TURNHost, "turn", "", "override TURN server ip")
	flags.StringVar(&cfg.TURNPort, "port", "", "override TURN port")
	flags.StringVar(&cfg.Listen, "listen", "127.0.0.1:9000", "listen on ip:port")
	flags.StringVar(&cfg.VKLink, "vk-link", "", "VK calls invite link \"https://vk.com/call/join/...\"")
	flags.StringVar(&cfg.YandexLink, "yandex-link", "", "Yandex telemost invite link \"https://telemost.yandex.ru/j/...\"")
	flags.StringVar(&cfg.PeerAddr, "peer", "", "peer server address (host:port)")
	flags.IntVar(&cfg.NumStreams, "n", 0, "connections to TURN (default 10 for VK, 1 for Yandex)")
	flags.BoolVar(&cfg.UseUDP, "udp", false, "connect to TURN with UDP")
	flags.BoolVar(&cfg.NoDTLS, "no-dtls", false, "connect without obfuscation. DO NOT USE")
	flags.BoolVar(&cfg.VLESSMode, "vless", false, "VLESS mode: forward TCP connections (for VLESS) instead of UDP packets")
	flags.BoolVar(&cfg.VLESSBond, "vless-bond", false, "bond one VLESS TCP connection across all active smux sessions")
	flags.BoolVar(&cfg.WrapMode, "wrap", false, "WRAP mode: SRTP-like AEAD obfuscation for DTLS packets before they reach TURN ChannelData")
	flags.StringVar(&cfg.WrapKeyHex, "wrap-key", "", "32-byte hex-encoded shared key for -wrap (64 hex chars)")
	flags.IntVar(&cfg.StreamsPerCred, "streams-per-cred", streamsPerCache, "number of TURN streams sharing one VK credential cache")
	flags.BoolVar(&cfg.Debug, "debug", false, "enable debug logging")
	flags.BoolVar(&cfg.ManualCaptcha, "manual-captcha", false, "skip auto captcha solving, use manual mode immediately")
	flags.StringVar(&cfg.CaptchaSolver, "captcha-solver", "v2", "auto captcha solver implementation: v1|v2")
	flags.StringVar(&cfg.CaptchaHost, "captcha-host", "", "manual captcha host:port to expose in addition to localhost:8765")

	if err := flags.Parse(args); err != nil {
		return Config{}, err
	}
	if *genWrapKey {
		key, err := genWrapKeyHex()
		if err != nil {
			return Config{}, err
		}
		cfg.WrapKeyHex = key
	}

	return cfg, nil
}

func Run(ctx context.Context, args []string) error {
	cfg, err := ParseConfig(args)
	if err != nil {
		return err
	}

	return RunWithConfig(ctx, cfg)
}
