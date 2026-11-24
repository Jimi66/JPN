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


def patch_turn_diagnostics(client: Path) -> None:
    path = client / "main.go"
    source = path.read_text(encoding="utf-8")

    source = replace_once(
        source,
        '\terr := oneDtlsConnection(ctx, peer, listenConn, inboundChan, connchan, okchan, streamID)\n'
        '\t\t\tif err != nil {',
        '\terr := oneDtlsConnection(ctx, peer, listenConn, inboundChan, connchan, okchan, streamID)\n'
        '\t\t\tif err != nil {\n'
        '\t\t\t\tlog.Printf("[JPN_DIAG] DTLS_HANDSHAKE_FAILED stream=%d error=%v", streamID, err)',
        "log DTLS worker failures",
    )

    source = replace_once(
        source,
        '\tdtlsConn, err1 := dtlsFunc(dtlsctx, conn1, peer)\n'
        '\tif err1 != nil {\n'
        '\t\treturn fmt.Errorf("failed to connect DTLS: %s", err1)\n'
        '\t}',
        '\tlog.Printf("[JPN_DIAG] DTLS_HANDSHAKE_START stream=%d", streamID)\n'
        '\tdtlsConn, err1 := dtlsFunc(dtlsctx, conn1, peer)\n'
        '\tif err1 != nil {\n'
        '\t\tlog.Printf("[JPN_DIAG] DTLS_HANDSHAKE_FAILED stream=%d error=%v", streamID, err1)\n'
        '\t\treturn fmt.Errorf("failed to connect DTLS: %s", err1)\n'
        '\t}\n'
        '\tlog.Printf("[JPN_DIAG] DTLS_HANDSHAKE_OK stream=%d", streamID)',
        "log DTLS handshake stages",
    )

    source = replace_once(
        source,
        '\tuser, pass, urlTarget, err1 := turnParams.getCreds(ctx, turnParams.link, streamID)\n'
        '\tif err1 != nil {',
        '\tlog.Printf("[JPN_DIAG] CREDENTIALS_REQUEST stream=%d cache=%d", streamID, getCacheID(streamID))\n'
        '\tuser, pass, urlTarget, err1 := turnParams.getCreds(ctx, turnParams.link, streamID)\n'
        '\tif err1 != nil {',
        "log TURN credential request",
    )
    source = replace_once(
        source,
        '\t\terr = fmt.Errorf("failed to get TURN credentials: %s", err1)\n'
        '\t\treturn\n'
        '\t}\n'
        '\turlhost, urlport, err1 := net.SplitHostPort(urlTarget)',
        '\t\terr = fmt.Errorf("failed to get TURN credentials: %s", err1)\n'
        '\t\treturn\n'
        '\t}\n'
        '\tlog.Printf("[JPN_DIAG] CREDENTIALS_OK stream=%d cache=%d", streamID, getCacheID(streamID))\n'
        '\turlhost, urlport, err1 := net.SplitHostPort(urlTarget)',
        "log successful TURN credentials",
    )

    source = replace_once(
        source,
        '\trelayConn, err1 := client.Allocate()\n'
        '\tif err1 != nil {',
        '\tlog.Printf("[JPN_DIAG] TURN_ALLOCATE_START stream=%d", streamID)\n'
        '\trelayConn, err1 := client.Allocate()\n'
        '\tif err1 != nil {',
        "log TURN allocation start",
    )
    source = replace_once(
        source,
        '\t// Reset error count on successful allocation\n'
        '\tgetStreamCache(streamID).errorCount.Store(0)',
        '\tlog.Printf("[JPN_DIAG] TURN_ALLOCATE_OK stream=%d", streamID)\n'
        '\t// Reset error count on successful allocation\n'
        '\tgetStreamCache(streamID).errorCount.Store(0)',
        "log successful TURN allocation",
    )

    source = replace_once(
        source,
        '\tnumStreams := *n\n'
        '\tif numStreams <= 0 {\n'
        '\t\tnumStreams = 1\n'
        '\t}',
        '\tnumStreams := *n\n'
        '\tif numStreams <= 0 {\n'
        '\t\tnumStreams = 1\n'
        '\t}\n'
        '\tlog.Printf("[JPN_DIAG] WORKER_PLAN requested=%d", numStreams)',
        "log requested worker count",
    )
    source = replace_once(
        source,
        '\tokchan := make(chan struct{})\n'
        '\tconnchan := make(chan net.PacketConn)\n'
        '\twg1.Add(1)',
        '\tokchan := make(chan struct{})\n'
        '\tconnchan := make(chan net.PacketConn)\n'
        '\tlog.Printf("[JPN_DIAG] WORKER_PAIR_CREATED stream=%d index=0", 1)\n'
        '\twg1.Add(1)',
        "log first worker pair creation",
    )
    source = replace_once(
        source,
        '\tfor i := 1; i < numStreams; i++ {\n'
        '\t\tcchan := make(chan net.PacketConn)',
        '\tfor i := 1; i < numStreams; i++ {\n'
        '\t\tlog.Printf("[JPN_DIAG] WORKER_PAIR_CREATED stream=%d index=%d", i, i)\n'
        '\t\tcchan := make(chan net.PacketConn)',
        "log additional worker pair creation",
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




def patch_android_captcha_url(client: Path) -> None:
    path = client / "manual_captcha.go"
    source = path.read_text(encoding="utf-8")
    source = replace_once(
        source,
        'func runCaptchaServerAndWait(handler http.Handler, captchaURL string, keyCh <-chan string, logPrefix string) (string, error) {',
        'func runCaptchaServerAndWait(handler http.Handler, captchaURL, androidURL string, keyCh <-chan string, logPrefix string) (string, error) {',
        "add direct Android CAPTCHA URL",
    )
    source = replace_once(
        source,
        '\tfmt.Println("JPN_CAPTCHA_URL=" + captchaURL)\n'
        '\tif runtime.GOOS != "android" {\n'
        '\t\topenBrowser(captchaURL)\n'
        '\t}',
        '\tif runtime.GOOS == "android" && androidURL != "" {\n'
        '\t\tfmt.Println("JPN_CAPTCHA_URL=" + androidURL)\n'
        '\t} else {\n'
        '\t\tfmt.Println("JPN_CAPTCHA_URL=" + captchaURL)\n'
        '\t}\n'
        '\tif runtime.GOOS != "android" {\n'
        '\t\topenBrowser(captchaURL)\n'
        '\t}',
        "open original VK CAPTCHA in Android WebView",
    )
    source = replace_once(
        source,
        'return runCaptchaServerAndWait(mux, localCaptchaOrigin(), keyCh, "captcha HTTP server error")',
        'return runCaptchaServerAndWait(mux, localCaptchaOrigin(), "", keyCh, "captcha HTTP server error")',
        "update image CAPTCHA server call",
    )
    source = replace_once(
        source,
        'return runCaptchaServerAndWait(mux, localCaptchaURLForTarget(targetURL), keyCh, "proxy HTTP server error")',
        'return runCaptchaServerAndWait(mux, localCaptchaURLForTarget(targetURL), redirectURI, keyCh, "proxy HTTP server error")',
        "pass original VK redirect URI to Android",
    )
    path.write_text(source, encoding="utf-8")


def patch_manual_captcha_wait(client: Path) -> None:
    path = client / "manual_captcha.go"
    source = path.read_text(encoding="utf-8")

    source = replace_once(
        source,
        'func runCaptchaServerAndWait(handler http.Handler, captchaURL, androidURL string, keyCh <-chan string, logPrefix string) (string, error) {',
        'func runCaptchaServerAndWait(ctx context.Context, handler http.Handler, captchaURL, androidURL string, keyCh <-chan string, logPrefix string) (string, error) {',
        "make manual CAPTCHA wait cancellable",
    )
    source = replace_once(
        source,
        '\tkey := <-keyCh\n'
        '\n'
        '\tctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)\n'
        '\tdefer cancel()\n'
        '\tif err := srv.Shutdown(ctx); err != nil {\n'
        '\t\treturn "", err\n'
        '\t}\n'
        '\n'
        '\treturn key, nil',
        '\tvar key string\n'
        '\tselect {\n'
        '\tcase key = <-keyCh:\n'
        '\tcase <-ctx.Done():\n'
        '\t}\n'
        '\n'
        '\tshutdownCtx, cancel := context.WithTimeout(context.Background(), 2*time.Second)\n'
        '\tdefer cancel()\n'
        '\tif err := srv.Shutdown(shutdownCtx); err != nil {\n'
        '\t\treturn "", err\n'
        '\t}\n'
        '\tif key == "" {\n'
        '\t\treturn "", ctx.Err()\n'
        '\t}\n'
        '\treturn key, nil',
        "wait for CAPTCHA token until VPN context is cancelled",
    )
    source = replace_once(
        source,
        'func solveCaptchaViaHTTP(captchaImg string) (string, error) {',
        'func solveCaptchaViaHTTP(ctx context.Context, captchaImg string) (string, error) {',
        "pass context to image CAPTCHA server",
    )
    source = replace_once(
        source,
        'return runCaptchaServerAndWait(mux, localCaptchaOrigin(), "", keyCh, "captcha HTTP server error")',
        'return runCaptchaServerAndWait(ctx, mux, localCaptchaOrigin(), "", keyCh, "captcha HTTP server error")',
        "make image CAPTCHA wait cancellable",
    )
    source = replace_once(
        source,
        'func solveCaptchaViaProxy(redirectURI string, dialer *net.Dialer) (string, error) {',
        'func solveCaptchaViaProxy(ctx context.Context, redirectURI string, dialer *net.Dialer) (string, error) {',
        "pass context to VK CAPTCHA proxy",
    )
    source = replace_once(
        source,
        'return runCaptchaServerAndWait(mux, localCaptchaURLForTarget(targetURL), redirectURI, keyCh, "proxy HTTP server error")',
        'return runCaptchaServerAndWait(ctx, mux, localCaptchaURLForTarget(targetURL), redirectURI, keyCh, "proxy HTTP server error")',
        "make VK CAPTCHA proxy wait cancellable",
    )
    path.write_text(source, encoding="utf-8")

    main_path = client / "main.go"
    main_source = main_path.read_text(encoding="utf-8")
    main_source = replace_once(
        main_source,
        'manualCtx, manualCancel := context.WithTimeout(ctx, 60*time.Second)',
        'manualCtx := ctx',
        "remove fixed 60-second manual CAPTCHA timeout",
    )
    main_source = replace_once(
        main_source,
        't, e = solveCaptchaViaProxy(captchaErr.RedirectURI, dialer)',
        't, e = solveCaptchaViaProxy(manualCtx, captchaErr.RedirectURI, dialer)',
        "pass VPN context to VK CAPTCHA proxy",
    )
    main_source = replace_once(
        main_source,
        'k, e = solveCaptchaViaHTTP(captchaErr.CaptchaImg)',
        'k, e = solveCaptchaViaHTTP(manualCtx, captchaErr.CaptchaImg)',
        "pass VPN context to image CAPTCHA",
    )
    main_source = replace_once(
        main_source,
        'case <-manualCtx.Done():\n'
        '\t\t\t\t\t\tsolveErr = fmt.Errorf("manual captcha timed out after 60s")',
        'case <-manualCtx.Done():\n'
        '\t\t\t\t\t\tsolveErr = fmt.Errorf("manual captcha cancelled: %w", manualCtx.Err())',
        "report CAPTCHA cancellation instead of timeout",
    )
    main_source = replace_once(
        main_source,
        '\t\t\t\t\tmanualCancel()\n',
        '',
        "remove manual CAPTCHA timeout cancellation",
    )
    main_path.write_text(main_source, encoding="utf-8")

def patch_captcha_diagnostics(client: Path) -> None:
    path = client / "manual_captcha.go"
    source = path.read_text(encoding="utf-8")

    source = replace_once(
        source,
        '\t\tlog.Printf("[Captcha Proxy] HTTP %s %s", r.Method, r.URL.String())',
        '\t\tlog.Printf("[Captcha Proxy] Page request: %s %s", r.Method, r.URL.Path)',
        "avoid logging CAPTCHA query parameters",
    )

    source = replace_once(
        source,
        '\tmux.HandleFunc("/generic_proxy", func(w http.ResponseWriter, r *http.Request) {\n'
        '\t\ttargetAuthURL := r.URL.Query().Get("proxy_url")',
        '\tmux.HandleFunc("/generic_proxy", func(w http.ResponseWriter, r *http.Request) {\n'
        '\t\tlog.Printf("[Captcha Proxy] Resource request: %s %s", r.Method, r.URL.Path)\n'
        '\t\ttargetAuthURL := r.URL.Query().Get("proxy_url")',
        "log CAPTCHA resource requests without query strings",
    )

    source = replace_once(
        source,
        '\t\t\tif strings.Contains(res.Request.URL.Path, "captchaNotRobot.check") {\n'
        '\t\t\t\tnotifyKey(keyCh, extractSuccessToken(bodyBytes))\n'
        '\t\t\t}\n'
        '\n'
        '\t\t\tif strings.Contains(contentType, "text/html") {',
        '\t\t\tif strings.Contains(res.Request.URL.Path, "captchaNotRobot.check") {\n'
        '\t\t\t\ttoken := extractSuccessToken(bodyBytes)\n'
        '\t\t\t\tlog.Printf("[Captcha Proxy] CAPTCHA check response: %d bytes; success token present: %t", len(bodyBytes), token != "")\n'
        '\t\t\t\tnotifyKey(keyCh, token)\n'
        '\t\t\t}\n'
        '\n'
        '\t\t\tif strings.Contains(contentType, "text/html") {\n'
        '\t\t\t\thtml := strings.ToLower(string(bodyBytes))\n'
        '\t\t\t\tlog.Printf("[Captcha Proxy] HTML diagnostics: %d bytes; scripts=%d (external=%d); images=%d; forms=%d; iframes=%d; has not_robot marker=%t",\n'
        '\t\t\t\t\tlen(bodyBytes), strings.Count(html, "<script"), strings.Count(html, "<script src"),\n'
        '\t\t\t\t\tstrings.Count(html, "<img"), strings.Count(html, "<form"), strings.Count(html, "<iframe"), strings.Contains(html, "not_robot"))',
        "log safe CAPTCHA HTML structure metrics",
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
    patch_turn_diagnostics(client)
    patch_manual_captcha(client)
    patch_android_captcha_url(client)
    patch_manual_captcha_wait(client)
    patch_captcha_diagnostics(client)
    patch_vk_captcha(client)

    templates = Path(__file__).resolve().parent
    for name in ("turn_socket_protect_android.go", "turn_socket_protect_other.go"):
        shutil.copy2(templates / name, client / name)

    print("Applied JPN per-socket VPN protection patch to vk-turn-proxy client.")


if __name__ == "__main__":
    main()
