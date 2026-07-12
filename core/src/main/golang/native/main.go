package main

/*
#cgo LDFLAGS: -llog

#include "bridge.h"
*/
import "C"

import (
	"runtime"
	"runtime/debug"

	"cfa/native/config"
	"cfa/native/delegate"
	"cfa/native/tunnel"

	"github.com/metacubex/mihomo/log"
)

func main() {
	panic("Stub!")
}

//export coreInit
func coreInit(home, cache, versionName, gitVersion C.c_string, sdkVersion C.int, hwid C.c_string) {
	h := C.GoString(home)
	c := C.GoString(cache)
	v := C.GoString(versionName)
	g := C.GoString(gitVersion)
	s := int(sdkVersion)
	id := C.GoString(hwid)

	delegate.Init(h, c, v, g, s, id)

	reset()
}

//export reset
func reset() {
	config.LoadDefault()
	tunnel.ResetStatistic()
	tunnel.CloseAllConnections()

	runtime.GC()
	debug.FreeOSMemory()
}

//export forceGc
func forceGc() {
	go func() {
		log.Infoln("[APP] request force GC")

		runtime.GC()
		debug.FreeOSMemory()
	}()
}
