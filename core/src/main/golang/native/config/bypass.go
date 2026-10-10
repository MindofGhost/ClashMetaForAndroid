package config

import (
	"fmt"
	"net/url"
	"strings"

	"github.com/metacubex/mihomo/common/yaml"
)

type BypassEntry struct {
	Type     string        `json:"type"`
	Endpoint string        `json:"endpoint"`
	Config   string        `json:"config"`
	Check    []BypassCheck `json:"check"`
}

type BypassCheck struct {
	URL   string `json:"url"`
	Alive bool   `json:"alive"`
}

func ParseBypassConfig(data []byte) ([]BypassEntry, error) {
	var profile map[string]any
	if err := yaml.Unmarshal(data, &profile); err != nil {
		return nil, fmt.Errorf("parse profile YAML: %w", err)
	}

	raw := profile["bypass"]
	if raw == nil {
		return []BypassEntry{}, nil
	}

	entries, ok := raw.([]any)
	if !ok {
		return nil, fmt.Errorf("bypass must be an array")
	}
	result := make([]BypassEntry, 0, len(entries))
	seen := make(map[string]bool)
	for index, rawEntry := range entries {
		entry, ok := rawEntry.(map[string]any)
		if !ok {
			return nil, fmt.Errorf("bypass[%d] must be a mapping", index)
		}
		kind, _ := entry["type"].(string)
		endpoint, _ := entry["endpoint"].(string)
		kind = strings.TrimSpace(kind)
		if kind == "" || strings.TrimSpace(endpoint) == "" {
			return nil, fmt.Errorf("bypass[%d] requires non-empty type and endpoint strings", index)
		}
		if seen[endpoint] {
			return nil, fmt.Errorf("bypass[%d]: duplicate endpoint %q", index, endpoint)
		}
		seen[endpoint] = true
		commandLine, ok := entry["config"].(string)
		if kind == "turn" && (!ok || strings.TrimSpace(commandLine) == "") {
			return nil, fmt.Errorf("bypass[%d].config must be a non-empty string", index)
		}
		checks, err := parseBypassChecks(entry["check"])
		if err != nil {
			return nil, fmt.Errorf("bypass[%d].check: %w", index, err)
		}
		result = append(result, BypassEntry{Type: kind, Endpoint: endpoint, Config: strings.TrimSpace(commandLine), Check: checks})
	}
	return result, nil
}

func parseBypassChecks(raw any) ([]BypassCheck, error) {
	checks := []BypassCheck{}
	if raw == nil {
		return checks, nil
	}
	value, ok := raw.(string)
	if !ok {
		return nil, fmt.Errorf("must be a string of URL = true/false conditions separated by semicolons")
	}
	if strings.TrimSpace(value) == "" {
		return checks, nil
	}
	for _, condition := range strings.Split(value, ";") {
		condition = strings.TrimSpace(condition)
		separator := strings.LastIndex(condition, "=")
		if separator < 0 {
			return nil, fmt.Errorf("invalid condition %q: expected URL = true/false", condition)
		}
		address := strings.TrimSpace(condition[:separator])
		expected := strings.TrimSpace(condition[separator+1:])
		if expected != "true" && expected != "false" {
			return nil, fmt.Errorf("invalid condition %q: expected true or false", condition)
		}
		parsed, err := url.Parse(address)
		if err != nil || parsed.Hostname() == "" || (parsed.Scheme != "http" && parsed.Scheme != "https") {
			return nil, fmt.Errorf("invalid HTTP(S) URL %q", address)
		}
		checks = append(checks, BypassCheck{URL: address, Alive: expected == "true"})
	}
	return checks, nil
}
