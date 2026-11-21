//go:build android

package main

import (
	"errors"
	"net"
	"os"
	"strings"
	"syscall"
	"time"
)

// turnSocketProtectControl is installed as net.Dialer.Control for every
// outbound TURN/VK/DNS socket. It passes the newly-created socket FD to the
// JPN VpnService over an abstract Unix-domain socket before connect().
func turnSocketProtectControl(_ string, _ string, raw syscall.RawConn) error {
	socketName := os.Getenv("JPN_TURN_PROTECT_SOCKET")
	if socketName == "" {
		return errors.New("JPN_TURN_PROTECT_SOCKET is not configured")
	}

	var protectErr error
	if err := raw.Control(func(fd uintptr) {
		protectErr = requestJPNVpnProtection(int(fd), socketName)
	}); err != nil {
		return err
	}
	return protectErr
}

func requestJPNVpnProtection(fd int, socketName string) error {
	socketName = strings.TrimPrefix(socketName, "@")
	address := &net.UnixAddr{Name: "@" + socketName, Net: "unix"}
	conn, err := net.DialUnix("unix", nil, address)
	if err != nil {
		return err
	}
	defer conn.Close()

	if err := conn.SetDeadline(time.Now().Add(5 * time.Second)); err != nil {
		return err
	}

	_, oobn, err := conn.WriteMsgUnix([]byte{1}, syscall.UnixRights(fd), nil)
	if err != nil {
		return err
	}
	if oobn == 0 {
		return errors.New("socket descriptor was not transferred to JPN")
	}

	var response [1]byte
	n, err := conn.Read(response[:])
	if err != nil {
		return err
	}
	if n != 1 || response[0] != 1 {
		return errors.New("JPN VpnService rejected socket protection")
	}
	return nil
}
