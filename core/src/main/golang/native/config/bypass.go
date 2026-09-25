package config

import (
	"fmt"
	"strings"

	"github.com/metacubex/mihomo/common/yaml"
)

// ParseTurnBypassConfig selects the first turn entry in the bypass array.
func ParseTurnBypassConfig(data []byte) (string, error) {
	var profile map[string]any
	if err := yaml.Unmarshal(data, &profile); err != nil {
		return "", fmt.Errorf("parse profile YAML: %w", err)
	}

	raw := profile["bypass"]
	if raw == nil {
		return "", nil
	}

	entries, ok := raw.([]any)
	if !ok {
		return "", fmt.Errorf("bypass must be an array")
	}
	for index, rawEntry := range entries {
		entry, ok := rawEntry.(map[string]any)
		if !ok {
			return "", fmt.Errorf("bypass[%d] must be a mapping", index)
		}
		if entry["type"] != "turn" {
			continue
		}
		commandLine, ok := entry["config"].(string)
		if !ok || strings.TrimSpace(commandLine) == "" {
			return "", fmt.Errorf("bypass[%d].config must be a non-empty string", index)
		}
		return strings.TrimSpace(commandLine), nil
	}
	return "", nil
}
