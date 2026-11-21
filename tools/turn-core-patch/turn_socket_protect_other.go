//go:build !android

package main

import "syscall"

// Desktop and server builds do not run inside the JPN Android VPN.
func turnSocketProtectControl(_ string, _ string, _ syscall.RawConn) error {
	return nil
}
