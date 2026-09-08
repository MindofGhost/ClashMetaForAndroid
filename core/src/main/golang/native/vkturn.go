package main

//#include "bridge.h"
import "C"

import (
	"context"
	"fmt"
	stdlog "log"
	"strings"
	"sync"
	"time"
	"unsafe"

	"cfa/native/app"
	"cfa/native/vkturn"

	"github.com/metacubex/mihomo/log"
	freeturn "github.com/samosvalishe/free-turn-proxy/mobile"
)

var vkTurnEvents = struct {
	sync.Mutex
	callbacks []unsafe.Pointer
}{}

type freeTurnEventSink struct{}

type freeTurnProtector struct{}

func (freeTurnEventSink) OnState(state string, streams, total int, errMsg string) {
	if errMsg != "" {
		line := fmt.Sprintf("[State] %s streams=%d/%d error=%s", state, streams, total, errMsg)
		notifyVkTurnEvent(line)
		log.Infoln("[VK_TURN] %s", line)
		return
	}
	line := fmt.Sprintf("[State] %s streams=%d/%d", state, streams, total)
	notifyVkTurnEvent(line)
	log.Infoln("[VK_TURN] %s", line)
}

func (freeTurnEventSink) OnLog(level, msg string, unixMillis int64) {
	if msg != "" {
		notifyVkTurnEvent(msg)
		log.Infoln("[VK_TURN] [%s] %s", strings.ToUpper(level), msg)
	}
}

func (freeTurnEventSink) OnCaptcha(url string) {
	if strings.TrimSpace(url) == "" {
		notifyVkTurnEvent("[Captcha] Manual captcha closed")
		return
	}
	notifyVkTurnEvent("[Captcha] Triggering manual captcha fallback...")
	notifyVkTurnEvent("ACTION REQUIRED: MANUAL CAPTCHA SOLVING NEEDED")
	notifyVkTurnEvent("CAPTCHA_URL: " + url)
}

func (freeTurnProtector) Protect(fd int) bool {
	app.MarkSocket(fd)
	return true
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
	vkTurnEvents.Lock()
	callbacks := append([]unsafe.Pointer(nil), vkTurnEvents.callbacks...)
	vkTurnEvents.Unlock()

	if len(callbacks) == 0 {
		return
	}

	var closed []unsafe.Pointer
	for _, callback := range callbacks {
		if C.logcat_received(callback, C.CString(line)) != 0 {
			closed = append(closed, callback)
		}
	}

	if len(closed) == 0 {
		return
	}

	vkTurnEvents.Lock()
	for _, callback := range closed {
		for i, existing := range vkTurnEvents.callbacks {
			if existing == callback {
				vkTurnEvents.callbacks = append(vkTurnEvents.callbacks[:i], vkTurnEvents.callbacks[i+1:]...)
				C.release_object(callback)
				break
			}
		}
	}
	vkTurnEvents.Unlock()
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
		freeturn.SetStateDir(cacheDir)
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
	freeturn.Stop()
}

//export wakeVkTurn
func wakeVkTurn() {
	freeturn.Wake()
}

//export isVkTurnRunning
func isVkTurnRunning() C.int {
	state := freeturn.GetState()
	if state == nil || state.State == freeturn.StateIdle || state.State == freeturn.StateError {
		return 0
	}

	return 1
}
