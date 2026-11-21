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


def patch_streams_per_credential(client: Path) -> None:
    path = client / "main.go"
    source = path.read_text(encoding="utf-8")

    source = replace_once(
        source,
        'const (\n'
        '\tcredentialLifetime = 10 * time.Minute\n'
        '\tcacheSafetyMargin  = 60 * time.Second\n'
        '\tmaxCacheErrors     = 3\n'
        '\terrorWindow        = 10 * time.Second\n'
        '\tstreamsPerCache    = 10\n'
        ')\n'
        '\n'
        'func getCacheID(streamID int) int {',
        'const (\n'
        '\tcredentialLifetime = 10 * time.Minute\n'
        '\tcacheSafetyMargin  = 60 * time.Second\n'
        '\tmaxCacheErrors     = 3\n'
        '\terrorWindow        = 10 * time.Second\n'
        ')\n'
        '\n'
        '// Configurable number of TURN streams sharing one VK credential cache.\n'
        'var streamsPerCache = 10\n'
        '\n'
        'func getCacheID(streamID int) int {',
        "make streams-per-credential configurable",
    )

    source = replace_once(
        source,
        '\tn := flag.Int("n", 0, "connections to TURN (default 10 for VK, 1 for Yandex)")\n'
        '\tudp := flag.Bool("udp", false, "connect to TURN with UDP")',
        '\tn := flag.Int("n", 0, "connections to TURN (default 10 for VK, 1 for Yandex)")\n'
        '\tstreamsPerCredential := flag.Int("streams-per-cred", 10, "TURN streams sharing one VK credential cache")\n'
        '\tudp := flag.Bool("udp", false, "connect to TURN with UDP")',
        "add streams-per-cred CLI flag",
    )

    source = replace_once(
        source,
        '\tflag.Parse()\n'
        '\tif *peerAddr == "" {',
        '\tflag.Parse()\n'
        '\tif *streamsPerCredential < 1 {\n'
        '\t\tlog.Panicf("streams-per-cred must be at least 1")\n'
        '\t}\n'
        '\tstreamsPerCache = *streamsPerCredential\n'
        '\tif *peerAddr == "" {',
        "apply streams-per-cred setting",
    )

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
    source = replace_once(
        source,
        '\topenBrowser(captchaURL)\n',
        '\tfmt.Println("JPN_CAPTCHA_URL=" + captchaURL)\n'
        '\tif runtime.GOOS != "android" {\n'
        '\t\topenBrowser(captchaURL)\n'
        '\t}\n',
        "hand off Android CAPTCHA URL to WebView",
    )
    path.write_text(source, encoding="utf-8")


def patch_vk_captcha(client: Path) -> None:
    path = client / "main.go"
    source = path.read_text(encoding="utf-8")
    old_fields = (
        '\t// Extract captcha_sid\n'
        '\tcaptchaSid, ok := errData["captcha_sid"].(string)\n'
        '\tif !ok {\n'
        '\t\t// try numeric\n'
        '\t\tif sidNum, ok2 := errData["captcha_sid"].(float64); ok2 {\n'
        '\t\t\tcaptchaSid = fmt.Sprintf("%.0f", sidNum)\n'
        '\t\t} else {\n'
        '\t\t\tlog.Printf("missing captcha_sid in captcha error data")\n'
        '\t\t\treturn nil\n'
        '\t\t}\n'
        '\t}\n'
        '\n'
        '\t// Extract captcha_img\n'
        '\tcaptchaImg, ok := errData["captcha_img"].(string)\n'
        '\tif !ok {\n'
        '\t\tlog.Printf("missing captcha_img in captcha error data")\n'
        '\t\treturn nil\n'
        '\t}\n'
    )
    new_fields = (
        '\t// captcha_sid and captcha_img are optional in VK redirect-based CAPTCHA responses.\n'
        '\tvar captchaSid string\n'
        '\tif sid, ok := errData["captcha_sid"].(string); ok {\n'
        '\t\tcaptchaSid = sid\n'
        '\t} else if sidNum, ok := errData["captcha_sid"].(float64); ok {\n'
        '\t\tcaptchaSid = fmt.Sprintf("%.0f", sidNum)\n'
        '\t}\n'
        '\tvar captchaImg string\n'
        '\tif img, ok := errData["captcha_img"].(string); ok {\n'
        '\t\tcaptchaImg = img\n'
        '\t}\n'
    )
    source = replace_once(source, old_fields, new_fields, "optional VK captcha fields")
    old_request = (
        '\t\t\t\tif captchaKey != "" {\n'
        '\t\t\t\t\tdata = fmt.Sprintf("vk_join_link=https://vk.com/call/join/%s&name=%s&captcha_key=%s&captcha_sid=%s&access_token=%s",\n'
        '\t\t\t\t\t\tlink, escapedName, neturl.QueryEscape(captchaKey), captchaErr.CaptchaSid, token1)\n'
        '\t\t\t\t} else {\n'
        '\t\t\t\t\tdata = fmt.Sprintf("vk_join_link=https://vk.com/call/join/%s&name=%s&captcha_key=&captcha_sid=%s&is_sound_captcha=0&success_token=%s&captcha_ts=%s&captcha_attempt=%s&access_token=%s",\n'
        '\t\t\t\t\t\tlink, escapedName, captchaErr.CaptchaSid, neturl.QueryEscape(successToken), captchaErr.CaptchaTs, captchaErr.CaptchaAttempt, token1)\n'
        '\t\t\t\t}\n'
    )
    new_request = (
        '\t\t\t\tif captchaKey != "" {\n'
        '\t\t\t\t\tdata = fmt.Sprintf("vk_join_link=https://vk.com/call/join/%s&name=%s&captcha_key=%s&access_token=%s",\n'
        '\t\t\t\t\t\tlink, escapedName, neturl.QueryEscape(captchaKey), token1)\n'
        '\t\t\t\t} else {\n'
        '\t\t\t\t\tdata = fmt.Sprintf("vk_join_link=https://vk.com/call/join/%s&name=%s&captcha_key=&is_sound_captcha=0&success_token=%s&captcha_ts=%s&captcha_attempt=%s&access_token=%s",\n'
        '\t\t\t\t\t\tlink, escapedName, neturl.QueryEscape(successToken), captchaErr.CaptchaTs, captchaErr.CaptchaAttempt, token1)\n'
        '\t\t\t\t}\n'
        '\t\t\t\tif captchaErr.CaptchaSid != "" {\n'
        '\t\t\t\t\tdata += "&captcha_sid=" + neturl.QueryEscape(captchaErr.CaptchaSid)\n'
        '\t\t\t\t}\n'
    )
    source = replace_once(source, old_request, new_request, "optional captcha_sid retry parameter")
    path.write_text(source, encoding="utf-8")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_core.py /path/to/vk-turn-proxy/client")

    client = Path(sys.argv[1]).resolve()
    if not (client / "main.go").is_file() or not (client / "manual_captcha.go").is_file():
        raise SystemExit(f"not a vk-turn-proxy client directory: {client}")

    patch_main(client)
    patch_streams_per_credential(client)
    patch_manual_captcha(client)
    patch_vk_captcha(client)

    templates = Path(__file__).resolve().parent
    for name in ("turn_socket_protect_android.go", "turn_socket_protect_other.go"):
        shutil.copy2(templates / name, client / name)

    print("Applied JPN per-socket VPN protection patch to vk-turn-proxy client.")


if __name__ == "__main__":
    main()
