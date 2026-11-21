#!/usr/bin/env python3
"""Apply JPN's Android per-socket VPN-protection adapter to vk-turn-proxy."""

from pathlib import Path
import shutil
import sys


def replace_once(source: str, old: str, new: str, label: str) -> str:
    count = source.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    return source.replace(old, new, 1)


def patch_main(client: Path) -> None:
    path = client / "main.go"
    source = path.read_text(encoding="utf-8")

    source = replace_once(
        source,
        '\t"github.com/bschaatsbergen/dnsdialer"\n',
        "",
        "remove dnsdialer import",
    )

    source = replace_once(
        source,
        'func (directNet) Dial(network, address string) (net.Conn, error) {\n'
        '\treturn net.Dial(network, address)\n'
        '}',
        'func (directNet) Dial(network, address string) (net.Conn, error) {\n'
        '\td := net.Dialer{Control: turnSocketProtectControl}\n'
        '\treturn d.Dial(network, address)\n'
        '}',
        "protect directNet.Dial",
    )

    source = replace_once(
        source,
        'func (directNet) DialUDP(network string, laddr, raddr *net.UDPAddr) (transport.UDPConn, error) {\n'
        '\treturn net.DialUDP(network, laddr, raddr)\n'
        '}',
        'func (directNet) DialUDP(network string, laddr, raddr *net.UDPAddr) (transport.UDPConn, error) {\n'
        '\td := net.Dialer{LocalAddr: laddr, Control: turnSocketProtectControl}\n'
        '\tconn, err := d.Dial(network, raddr.String())\n'
        '\tif err != nil {\n'
        '\t\treturn nil, err\n'
        '\t}\n'
        '\tudpConn, ok := conn.(*net.UDPConn)\n'
        '\tif !ok {\n'
        '\t\t_ = conn.Close()\n'
        '\t\treturn nil, fmt.Errorf("expected UDP connection, got %T", conn)\n'
        '\t}\n'
        '\treturn udpConn, nil\n'
        '}',
        "protect directNet.DialUDP",
    )

    source = replace_once(
        source,
        'func (directNet) DialTCP(network string, laddr, raddr *net.TCPAddr) (transport.TCPConn, error) {\n'
        '\treturn net.DialTCP(network, laddr, raddr)\n'
        '}',
        'func (directNet) DialTCP(network string, laddr, raddr *net.TCPAddr) (transport.TCPConn, error) {\n'
        '\td := net.Dialer{LocalAddr: laddr, Control: turnSocketProtectControl}\n'
        '\tconn, err := d.Dial(network, raddr.String())\n'
        '\tif err != nil {\n'
        '\t\treturn nil, err\n'
        '\t}\n'
        '\ttcpConn, ok := conn.(*net.TCPConn)\n'
        '\tif !ok {\n'
        '\t\t_ = conn.Close()\n'
        '\t\treturn nil, fmt.Errorf("expected TCP connection, got %T", conn)\n'
        '\t}\n'
        '\treturn tcpConn, nil\n'
        '}',
        "protect directNet.DialTCP",
    )

    source = replace_once(
        source,
        'func (directNet) CreateDialer(dialer *net.Dialer) transport.Dialer {\n'
        '\treturn directDialer{Dialer: dialer}\n'
        '}',
        'func (directNet) CreateDialer(dialer *net.Dialer) transport.Dialer {\n'
        '\tcopy := *dialer\n'
        '\tpreviousControl := copy.Control\n'
        '\tpreviousControlContext := copy.ControlContext\n'
        '\tcopy.Control = func(network, address string, raw syscall.RawConn) error {\n'
        '\t\tif previousControl != nil {\n'
        '\t\t\tif err := previousControl(network, address, raw); err != nil {\n'
        '\t\t\t\treturn err\n'
        '\t\t\t}\n'
        '\t\t}\n'
        '\t\treturn turnSocketProtectControl(network, address, raw)\n'
        '\t}\n'
        '\tcopy.ControlContext = func(ctx context.Context, network, address string, raw syscall.RawConn) error {\n'
        '\t\tif previousControlContext != nil {\n'
        '\t\t\tif err := previousControlContext(ctx, network, address, raw); err != nil {\n'
        '\t\t\t\treturn err\n'
        '\t\t\t}\n'
        '\t\t} else if previousControl != nil {\n'
        '\t\t\tif err := previousControl(network, address, raw); err != nil {\n'
        '\t\t\t\treturn err\n'
        '\t\t\t}\n'
        '\t\t}\n'
        '\t\treturn turnSocketProtectControl(network, address, raw)\n'
        '\t}\n'
        '\treturn directDialer{Dialer: &copy}\n'
        '}',
        "protect Pion-created dialers",
    )

    source = replace_once(
        source,
        'func getCustomNetDialer() net.Dialer {\n'
        '\treturn net.Dialer{\n'
        '\t\tTimeout:   20 * time.Second,',
        'func getCustomNetDialer() net.Dialer {\n'
        '\treturn net.Dialer{\n'
        '\t\tControl:   turnSocketProtectControl,\n'
        '\t\tTimeout:   20 * time.Second,',
        "protect custom HTTP dialer",
    )

    source = replace_once(
        source,
        '\t\t\t\tvar d net.Dialer\n'
        '\t\t\t\tdnsServers := []string{',
        '\t\t\t\tvar d net.Dialer\n'
        '\t\t\t\td.Control = turnSocketProtectControl\n'
        '\t\t\t\tdnsServers := []string{',
        "protect custom DNS resolver",
    )

    source = replace_once(
        source,
        '\tdialer := websocket.Dialer{}',
        '\tdialer := websocket.Dialer{\n'
        '\t\tNetDialContext: (&net.Dialer{Control: turnSocketProtectControl}).DialContext,\n'
        '\t}',
        "protect WebSocket dialer",
    )

    source = replace_once(
        source,
        '\tvar d net.Dialer\n'
        '\tctx1, cancel := context.WithTimeout(ctx, 5*time.Second)',
        '\td := net.Dialer{Control: turnSocketProtectControl}\n'
        '\tctx1, cancel := context.WithTimeout(ctx, 5*time.Second)',
        "protect TURN stream dialer",
    )

    source = replace_once(
        source,
        '\t\tconn, err2 := net.DialUDP("udp", nil, turnServerUDPAddr) // nolint: noctx\n'
        '\t\tif err2 != nil {\n'
        '\t\t\terr = fmt.Errorf("failed to connect to TURN server: %s", err2)\n'
        '\t\t\treturn\n'
        '\t\t}',
        '\t\trawConn, err2 := d.DialContext(ctx1, "udp", turnServerAddr)\n'
        '\t\tif err2 != nil {\n'
        '\t\t\terr = fmt.Errorf("failed to connect to TURN server: %s", err2)\n'
        '\t\t\treturn\n'
        '\t\t}\n'
        '\t\tconn, ok := rawConn.(*net.UDPConn)\n'
        '\t\tif !ok {\n'
        '\t\t\t_ = rawConn.Close()\n'
        '\t\t\terr = fmt.Errorf("expected UDP connection, got %T", rawConn)\n'
        '\t\t\treturn\n'
        '\t\t}',
        "protect UDP TURN connection",
    )

    source = replace_once(
        source,
        '\tvar turnConn net.PacketConn\n'
        '\tctx1, cancel1 := context.WithTimeout(ctx, 5*time.Second)',
        '\tvar turnConn net.PacketConn\n'
        '\td := net.Dialer{Control: turnSocketProtectControl}\n'
        '\tctx1, cancel1 := context.WithTimeout(ctx, 5*time.Second)',
        "protect VLESS TURN dialer",
    )

    source = replace_once(
        source,
        '\t\tc, err1 := net.DialUDP("udp", nil, turnServerUDPAddr)\n'
        '\t\tif err1 != nil {\n'
        '\t\t\treturn nil, nil, fmt.Errorf("dial TURN (udp): %w", err1)\n'
        '\t\t}',
        '\t\trawConn, err1 := d.DialContext(ctx1, "udp", turnServerAddr)\n'
        '\t\tif err1 != nil {\n'
        '\t\t\treturn nil, nil, fmt.Errorf("dial TURN (udp): %w", err1)\n'
        '\t\t}\n'
        '\t\tc, ok := rawConn.(*net.UDPConn)\n'
        '\t\tif !ok {\n'
        '\t\t\t_ = rawConn.Close()\n'
        '\t\t\treturn nil, nil, fmt.Errorf("expected UDP connection, got %T", rawConn)\n'
        '\t\t}',
        "protect VLESS UDP TURN connection",
    )

    source = replace_once(
        source,
        '\t\tvar d net.Dialer\n'
        '\t\tc, err1 := d.DialContext(ctx1, "tcp", turnServerAddr)',
        '\t\tc, err1 := d.DialContext(ctx1, "tcp", turnServerAddr)',
        "VLESS TCP dialer already protected",
    )

    source = replace_once(
        source,
        'func main() {',
        'func main() {\n'
        '\t// Route all Go DNS lookups through the protected resolver.\n'
        '\tnet.DefaultResolver = getCustomNetDialer().Resolver',
        "install protected default resolver",
    )

    source = replace_once(
        source,
        '\t\tdialer := dnsdialer.New(\n'
        '\t\t\tdnsdialer.WithResolvers("77.88.8.8:53", "77.88.8.1:53", "8.8.8.8:53", "8.8.4.4:53", "1.1.1.1:53", "1.0.0.1:53"),\n'
        '\t\t\tdnsdialer.WithStrategy(dnsdialer.Fallback{}),\n'
        '\t\t\tdnsdialer.WithCache(100, 10*time.Hour, 10*time.Hour),\n'
        '\t\t)',
        '\t\tdialer := getCustomNetDialer()',
        "replace dnsdialer with protected resolver",
    )

    source = replace_once(
        source,
        'return getVkCredsCached(ctx, s, streamID, dialer)',
        'return getVkCredsCached(ctx, s, streamID, &dialer)',
        "pass protected resolver dialer",
    )

    source = source.replace("*dnsdialer.Dialer", "*net.Dialer")
    if "dnsdialer" in source:
        raise RuntimeError("dnsdialer references remain in client/main.go")

    path.write_text(source, encoding="utf-8")


def patch_manual_captcha(client: Path) -> None:
    path = client / "manual_captcha.go"
    source = path.read_text(encoding="utf-8")
    source = replace_once(
        source,
        '\t"github.com/bschaatsbergen/dnsdialer"\n',
        "",
        "remove captcha dnsdialer import",
    )
    source = source.replace("*dnsdialer.Dialer", "*net.Dialer")
    if "dnsdialer" in source:
        raise RuntimeError("dnsdialer references remain in client/manual_captcha.go")
    path.write_text(source, encoding="utf-8")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_core.py /path/to/vk-turn-proxy/client")

    client = Path(sys.argv[1]).resolve()
    if not (client / "main.go").is_file() or not (client / "manual_captcha.go").is_file():
        raise SystemExit(f"not a vk-turn-proxy client directory: {client}")

    patch_main(client)
    patch_manual_captcha(client)

    templates = Path(__file__).resolve().parent
    for name in ("turn_socket_protect_android.go", "turn_socket_protect_other.go"):
        shutil.copy2(templates / name, client / name)

    print("Applied JPN per-socket VPN protection patch to vk-turn-proxy client.")


if __name__ == "__main__":
    main()
