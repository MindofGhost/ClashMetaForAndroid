package main

//#include "bridge.h"
import "C"

import (
	"context"
	"fmt"
	stdlog "log"
	"strings"
	"sync"
	"unsafe"

	"cfa/native/vkturn"

	"github.com/metacubex/mihomo/log"
)

var vkTurnRuntime = struct {
	sync.Mutex
	cancel context.CancelFunc
	done   chan struct{}
}{}

var vkTurnEvents = struct {
	sync.Mutex
	callbacks []unsafe.Pointer
}{}

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

	vkTurnRuntime.Lock()
	if vkTurnRuntime.cancel != nil {
		vkTurnRuntime.cancel()
		done := vkTurnRuntime.done
		vkTurnRuntime.Unlock()
		<-done
		vkTurnRuntime.Lock()
	}

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	vkTurnRuntime.cancel = cancel
	vkTurnRuntime.done = done
	vkTurnRuntime.Unlock()

	go func() {
		defer close(done)
		log.Infoln("[VK_TURN] starting: %s", strings.Join(parsedArgs, " "))

		if err := vkturn.Run(ctx, parsedArgs); err != nil && ctx.Err() == nil {
			log.Warnln("[VK_TURN] stopped with error: %s", err.Error())
		} else {
			log.Infoln("[VK_TURN] stopped")
		}

		vkTurnRuntime.Lock()
		if vkTurnRuntime.done == done {
			vkTurnRuntime.cancel = nil
			vkTurnRuntime.done = nil
		}
		vkTurnRuntime.Unlock()
	}()
}

//export stopVkTurn
func stopVkTurn() {
	vkTurnRuntime.Lock()
	cancel := vkTurnRuntime.cancel
	done := vkTurnRuntime.done
	if cancel == nil {
		vkTurnRuntime.Unlock()
		return
	}
	cancel()
	vkTurnRuntime.Unlock()

	<-done
}

//export isVkTurnRunning
func isVkTurnRunning() C.int {
	vkTurnRuntime.Lock()
	defer vkTurnRuntime.Unlock()

	if vkTurnRuntime.cancel == nil {
		return 0
	}

	return 1
}
