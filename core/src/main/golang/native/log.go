package main

//#include "bridge.h"
import "C"

import (
	"regexp"
	"strconv"
	"strings"
	"time"
	"unsafe"

	"github.com/metacubex/mihomo/log"
)

type message struct {
	Level   string `json:"level"`
	Message string `json:"message"`
	Time    int64  `json:"time"`
}

var endpointHealthLog = regexp.MustCompile(`^Health Checked, proxy: (.*), url: .*, alive: (true|false), delay: ([0-9]+) ms uid: \{([^}]+)\}$`)
var finishedHealthLog = regexp.MustCompile(`^Finish A Health Checking \{([^}]+)\}$`)

//export subscribeHealthChecks
func subscribeHealthChecks(remote unsafe.Pointer) {
	sub := log.Subscribe()
	go func() {
		defer log.UnSubscribe(sub)
		defer C.release_object(remote)
		for msg := range sub {
			event := struct {
				Round    string `json:"round"`
				Endpoint string `json:"endpoint"`
				Alive    bool   `json:"alive"`
				Finished bool   `json:"finished"`
				Time     int64  `json:"time"`
			}{Time: time.Now().UnixMilli()}
			if match := endpointHealthLog.FindStringSubmatch(msg.Payload); match != nil {
				delay, _ := strconv.Atoi(match[3])
				event.Endpoint, event.Alive, event.Round = match[1], match[2] == "true" && delay > 0 && delay < 0xffff, match[4]
			} else if match := finishedHealthLog.FindStringSubmatch(msg.Payload); match != nil {
				event.Round, event.Finished = match[1], true
			} else {
				continue
			}
			if C.logcat_received(remote, marshalJson(event)) != 0 {
				return
			}
		}
	}()
}

func init() {
	go func() {
		sub := log.Subscribe()
		defer log.UnSubscribe(sub)

		for msg := range sub {
			cPayload := C.CString(msg.Payload)

			switch msg.LogLevel {
			case log.INFO:
				C.log_info(cPayload)
			case log.ERROR:
				C.log_error(cPayload)
			case log.WARNING:
				C.log_warn(cPayload)
			case log.DEBUG:
				C.log_debug(cPayload)
			case log.SILENT:
				C.log_verbose(cPayload)
			}
		}
	}()
}

//export subscribeLogcat
func subscribeLogcat(remote unsafe.Pointer) {
	go func(remote unsafe.Pointer) {
		sub := log.Subscribe()
		defer log.UnSubscribe(sub)

		for msg := range sub {
			if msg.LogLevel < log.Level() &&
				!strings.HasPrefix(msg.Payload, "[APP]") &&
				!strings.HasPrefix(msg.Payload, "[VK_TURN]") &&
				!strings.HasPrefix(msg.Payload, "Finish A Health Checking") {
				continue
			}

			rMsg := &message{
				Level:   msg.LogLevel.String(),
				Message: msg.Payload,
				Time:    time.Now().UnixNano() / 1000 / 1000,
			}

			if C.logcat_received(remote, marshalJson(rMsg)) != 0 {
				C.release_object(remote)

				log.Debugln("Logcat subscriber closed")

				break
			}
		}
	}(remote)

	log.Infoln("[APP] Logcat level: %s", log.Level().String())
}
