package main

//#include "bridge.h"
import "C"

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	stdlog "log"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
	"unsafe"

	"cfa/native/app"
	"cfa/native/config"
	"cfa/native/vkturn"

	"github.com/metacubex/mihomo/log"
	freeturn "github.com/samosvalishe/free-turn-proxy/mobile"
)

var vkTurnEvents = struct {
	sync.Mutex
	callbacks []unsafe.Pointer
}{}

type freeTurnEventSink struct {
	endpoint string
	token    string
}

type freeTurnProtector struct{}

type freeTurnEvent struct {
	Endpoint string  `json:"endpoint"`
	Token    string  `json:"token"`
	Message  string  `json:"message"`
	Captcha  *string `json:"captcha,omitempty"`
}

func (s freeTurnEventSink) emit(line string) {
	notifyTurnEvent(freeTurnEvent{Endpoint: s.endpoint, Token: s.token, Message: line})
	log.Infoln("[VK_TURN] [endpoint=%s] %s", s.endpoint, line)
}

func (s freeTurnEventSink) OnState(state string, streams, total int, errMsg string) {
	line := fmt.Sprintf("[State] %s streams=%d/%d", state, streams, total)
	if errMsg != "" {
		line += " error=" + errMsg
	}
	s.emit(line)
}

func (s freeTurnEventSink) OnLog(level, msg string, unixMillis int64) {
	if msg != "" {
		s.emit(fmt.Sprintf("[%s] %s", strings.ToUpper(level), msg))
	}
}

func (s freeTurnEventSink) OnCaptcha(url string) {
	notifyTurnEvent(freeTurnEvent{Endpoint: s.endpoint, Token: s.token, Captcha: &url})
}

func (freeTurnProtector) Protect(fd int) bool {
	return app.MarkSocket(fd)
}

type vkTurnLogWriter struct{}

func (vkTurnLogWriter) Write(p []byte) (int, error) {
	line := strings.TrimSpace(string(p))
	if line != "" {
		notifyVkTurnEvent(line)
		log.Infoln("[VK_TURN] %s", line)
	}

	return len(p), nil
}

func notifyVkTurnEvent(line string) {
	notifyTurnEvent(freeTurnEvent{Message: line})
}

func notifyTurnEvent(event freeTurnEvent) {
	payload, err := json.Marshal(event)
	if err != nil {
		return
	}
	vkTurnEvents.Lock()
	defer vkTurnEvents.Unlock()
	alive := vkTurnEvents.callbacks[:0]
	for _, callback := range vkTurnEvents.callbacks {
		if C.logcat_received(callback, C.CString(string(payload))) != 0 {
			C.release_object(callback)
		} else {
			alive = append(alive, callback)
		}
	}
	vkTurnEvents.callbacks = alive
}

func init() {
	stdlog.SetFlags(0)
	stdlog.SetOutput(vkTurnLogWriter{})
	freeturn.SetEventSink(freeTurnEventSink{})
	freeturn.SetProtect(freeTurnProtector{})
}

func parseCommandLine(commandLine string) ([]string, error) {
	var result []string
	var current strings.Builder
	var quote rune
	escaping := false

	for _, char := range commandLine {
		switch {
		case escaping:
			current.WriteRune(char)
			escaping = false
		case char == '\\' && quote != '\'':
			escaping = true
		case quote != 0:
			if char == quote {
				quote = 0
			} else {
				current.WriteRune(char)
			}
		case char == '\'' || char == '"':
			quote = char
		case char == ' ' || char == '\t' || char == '\n' || char == '\r':
			if current.Len() > 0 {
				result = append(result, current.String())
				current.Reset()
			}
		default:
			current.WriteRune(char)
		}
	}

	if escaping {
		current.WriteRune('\\')
	}
	if quote != 0 {
		return nil, fmt.Errorf("unclosed quote")
	}
	if current.Len() > 0 {
		result = append(result, current.String())
	}

	return result, nil
}

//export subscribeVkTurnEvents
func subscribeVkTurnEvents(callback unsafe.Pointer) {
	vkTurnEvents.Lock()
	vkTurnEvents.callbacks = append(vkTurnEvents.callbacks, callback)
	vkTurnEvents.Unlock()
}

//export readVkTurnConfig
func readVkTurnConfig(path C.c_string) *C.char {
	data, err := os.ReadFile(C.GoString(path))
	if err != nil {
		log.Warnln("[VK_TURN] failed to read profile: %s", err.Error())
		return nil
	}
	entries, err := config.ParseBypassConfig(data)
	if err != nil {
		log.Warnln("[VK_TURN] invalid bypass configuration: %s", err.Error())
		return nil
	}
	return marshalJson(entries)
}

//export startVkTurn
func startVkTurn(args C.c_string) {
	argLine := C.GoString(args)
	parsedArgs, err := parseCommandLine(argLine)
	if err != nil {
		log.Warnln("[VK_TURN] invalid arguments: %s", err.Error())
		return
	}

	configJSON, err := freeTurnConfigJSONFromLegacyArgs(parsedArgs, app.Hwid())
	if err != nil {
		log.Warnln("[VK_TURN] invalid free-turn configuration: %s", err.Error())
		return
	}
	if cacheDir := strings.TrimSpace(app.CacheDir()); cacheDir != "" {
		freeturn.SetStateDir(filepath.Join(cacheDir, "freeturn"))
	}

	log.Infoln("[VK_TURN] starting free-turn-proxy: %s", strings.Join(parsedArgs, " "))

	if err := freeturn.Restart(configJSON, 0); err != nil {
		log.Warnln("[VK_TURN] free-turn-proxy start failed: %s", err.Error())
	}
}

//export resolveVkTurnHost
func resolveVkTurnHost(host C.c_string) *C.char {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	addresses, err := vkturn.ResolveHost(ctx, C.GoString(host))
	if err != nil {
		log.Infoln("[VK_TURN] resolver failed: %s", err.Error())
		return nil
	}

	return C.CString(strings.Join(addresses, ","))
}

//export stopVkTurn
func stopVkTurn() {
	cancelTurnExcept("")
	for endpoint := range turnInstancesSnapshot() {
		if err := stopTurnInstance(endpoint); err != nil {
			log.Warnln("[VK_TURN] stop %s failed: %v", endpoint, err)
		}
	}
	freeturn.Stop()
}

//export wakeVkTurn
func wakeVkTurn() {
	for _, instance := range turnInstancesSnapshot() {
		instance.proxy.Wake()
	}
	freeturn.Wake()
}

//export reconnectVkTurn
func reconnectVkTurn() {
	for _, instance := range turnInstancesSnapshot() {
		instance.proxy.Reconnect()
	}
	freeturn.Reconnect()
}

//export isVkTurnRunning
func isVkTurnRunning() C.int {
	for _, instance := range turnInstancesSnapshot() {
		if turnStateRunning(instance.proxy.GetState()) {
			return 1
		}
	}
	state := freeturn.GetState()
	if state == nil || state.State == freeturn.StateIdle || state.State == freeturn.StateError {
		return 0
	}

	return 1
}

type turnInstance struct {
	proxy  *freeturn.ProxyInstance
	listen string
	mode   bool
}

var turnInstances = struct {
	sync.Mutex
	entries map[string]*turnInstance
}{entries: make(map[string]*turnInstance)}

func turnInstancesSnapshot() map[string]*turnInstance {
	turnInstances.Lock()
	defer turnInstances.Unlock()
	result := make(map[string]*turnInstance, len(turnInstances.entries))
	for endpoint, instance := range turnInstances.entries {
		result[endpoint] = instance
	}
	return result
}

func turnStateRunning(state *freeturn.Snapshot) bool {
	return state != nil && state.State != freeturn.StateIdle && state.State != freeturn.StateError
}

func startTurnInstance(endpoint, commandLine, token string) error {
	args, err := parseCommandLine(commandLine)
	if err != nil {
		return err
	}
	legacy, err := parseFreeTurnLegacyConfig(args)
	if err != nil {
		return err
	}
	configJSON, err := freeTurnConfigJSONFromLegacyArgs(args, app.Hwid())
	if err != nil {
		return err
	}
	turnInstances.Lock()
	defer turnInstances.Unlock()
	if old := turnInstances.entries[endpoint]; old != nil {
		if err := old.proxy.Stop(); err != nil {
			return err
		}
		delete(turnInstances.entries, endpoint)
	}
	for other, instance := range turnInstances.entries {
		if instance.listen == legacy.Listen && instance.mode == legacy.VLESSMode {
			return fmt.Errorf("listen address %s is already used by %s", legacy.Listen, other)
		}
	}
	cacheDir := strings.TrimSpace(app.CacheDir())
	if cacheDir == "" {
		return fmt.Errorf("app cache directory is absent")
	}
	hash := sha256.Sum256([]byte(endpoint))
	stateDir := filepath.Join(cacheDir, "freeturn", fmt.Sprintf("%x", hash[:16]))
	instance := freeturn.NewProxyInstance(stateDir, freeTurnEventSink{endpoint: endpoint, token: token})
	if err := instance.Start(configJSON); err != nil {
		return err
	}
	turnInstances.entries[endpoint] = &turnInstance{proxy: instance, listen: legacy.Listen, mode: legacy.VLESSMode}
	log.Infoln("[VK_TURN] started endpoint=%s listen=%s", endpoint, legacy.Listen)
	return nil
}

func stopTurnInstance(endpoint string) error {
	turnInstances.Lock()
	defer turnInstances.Unlock()
	instance := turnInstances.entries[endpoint]
	if instance == nil {
		return nil
	}
	if err := instance.proxy.Stop(); err != nil {
		return err
	}
	delete(turnInstances.entries, endpoint)
	return nil
}

//export startVkTurnInstance
func startVkTurnInstance(endpoint, args, token C.c_string) *C.char {
	if err := startTurnInstance(C.GoString(endpoint), C.GoString(args), C.GoString(token)); err != nil {
		return C.CString(err.Error())
	}
	return nil
}

//export stopVkTurnInstance
func stopVkTurnInstance(endpoint C.c_string) *C.char {
	if err := stopTurnInstance(C.GoString(endpoint)); err != nil {
		return C.CString(err.Error())
	}
	return nil
}

//export reconnectVkTurnInstance
func reconnectVkTurnInstance(endpoint C.c_string) C.int {
	if instance := turnInstancesSnapshot()[C.GoString(endpoint)]; instance != nil && instance.proxy.Reconnect() {
		return 1
	}
	return 0
}

//export cancelVkTurnExcept
func cancelVkTurnExcept(endpoint C.c_string) {
	cancelTurnExcept(C.GoString(endpoint))
}

func cancelTurnExcept(endpoint string) {
	for name, instance := range turnInstancesSnapshot() {
		if name != endpoint {
			instance.proxy.Cancel()
		}
	}
}

//export queryVkTurnStates
func queryVkTurnStates() *C.char {
	states := make(map[string]string)
	for endpoint, instance := range turnInstancesSnapshot() {
		states[endpoint] = instance.proxy.GetState().State
	}
	return marshalJson(states)
}
