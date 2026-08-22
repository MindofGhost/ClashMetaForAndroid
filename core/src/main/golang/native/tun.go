package main

//#include "bridge.h"
import "C"

import (
	"context"
	"io"
	"sync"
	"unsafe"

	"golang.org/x/sync/semaphore"

	"cfa/native/app"
	"cfa/native/tun"
)

var rTunLock sync.Mutex
var rTun *remoteTun

type remoteTun struct {
	closer   io.Closer
	callback unsafe.Pointer

	closed bool
	limit  *semaphore.Weighted
}

func newRemoteTun(callback unsafe.Pointer) *remoteTun {
	return &remoteTun{callback: callback, limit: semaphore.NewWeighted(4)}
}

func (t *remoteTun) markSocket(fd int) {
	_ = t.limit.Acquire(context.Background(), 1)
	defer t.limit.Release(1)

	if t.closed {
		return
	}

	C.mark_socket(t.callback, C.int(fd))
}

func (t *remoteTun) querySocketUid(protocol int, source, target string) int {
	_ = t.limit.Acquire(context.Background(), 1)
	defer t.limit.Release(1)

	if t.closed {
		return -1
	}

	return int(C.query_socket_uid(t.callback, C.int(protocol), C.CString(source), C.CString(target)))
}

func (t *remoteTun) close() {
	_ = t.limit.Acquire(context.TODO(), 4)
	defer t.limit.Release(4)

	t.closed = true

	if t.closer != nil {
		_ = t.closer.Close()
	}

	app.ApplyTunContext(nil, nil)

	C.release_object(t.callback)
}

//export prepareTun
func prepareTun(callback unsafe.Pointer) {
	rTunLock.Lock()
	defer rTunLock.Unlock()

	if rTun != nil {
		rTun.close()
	}

	remote := newRemoteTun(callback)
	app.ApplyTunContext(remote.markSocket, remote.querySocketUid)
	rTun = remote
}

//export startTun
func startTun(fd C.int, stack, gateway, portal, dns C.c_string, callback unsafe.Pointer) C.int {
	rTunLock.Lock()
	defer rTunLock.Unlock()

	f := int(fd)
	s := C.GoString(stack)
	g := C.GoString(gateway)
	p := C.GoString(portal)
	d := C.GoString(dns)

	var remote *remoteTun
	if rTun != nil && !rTun.closed && rTun.closer == nil {
		remote = rTun
		C.release_object(callback)
	} else {
		if rTun != nil {
			rTun.close()
		}

		remote = newRemoteTun(callback)
		app.ApplyTunContext(remote.markSocket, remote.querySocketUid)
		rTun = remote
	}

	closer, err := tun.Start(f, s, g, p, d)
	if err != nil {
		remote.close()
		rTun = nil

		return 1
	}

	remote.closer = closer

	rTun = remote

	return 0
}

//export stopTun
func stopTun() {
	rTunLock.Lock()
	defer rTunLock.Unlock()

	if rTun != nil {
		rTun.close()
		rTun = nil
	}
}
