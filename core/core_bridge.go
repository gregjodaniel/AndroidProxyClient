package corebridge

import (
	"context"
	"fmt"
	"net/netip"
	"net/url"
	"os"
	"runtime/debug"
	"strconv"
	"sync"
	"syscall"

	"golang.org/x/sys/unix"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	_ "golang.org/x/mobile/bind"

	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/control"
	"github.com/sagernet/sing/common/logger"
	"github.com/sagernet/sing/common/x/list"
	"github.com/sagernet/sing/service"

	t2score "github.com/xjasonlyu/tun2socks/v2/core"
	"github.com/xjasonlyu/tun2socks/v2/core/device"
	"github.com/xjasonlyu/tun2socks/v2/core/device/fdbased"
	t2sproxy "github.com/xjasonlyu/tun2socks/v2/proxy"
	"github.com/xjasonlyu/tun2socks/v2/tunnel"
	gvstack "gvisor.dev/gvisor/pkg/tcpip/stack"
)

var (
	mu        sync.Mutex
	instance  *box.Box
	tunDevice device.Device
	netStack  *gvstack.Stack
)

// InitCrashLogger redirects stderr (FD 2) to a persistent file so any Go fatal errors or panics in background goroutines are recorded
func InitCrashLogger(path string) {
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0666)
	if err == nil {
		_ = unix.Dup2(int(f.Fd()), 2)
	}
}

// StartProxy starts the Sing-Box core and attaches tun2socks to the Android VPN fd
func StartProxy(configJSON string, tunFd int) (retErr error) {
	defer func() {
		if r := recover(); r != nil {
			if instance != nil {
				_ = instance.Close()
				instance = nil
			}
			retErr = fmt.Errorf("Go内核Panic异常: %v\n[Stack]\n%s", r, string(debug.Stack()))
		}
	}()

	mu.Lock()
	defer mu.Unlock()

	stopInternal()

	ctx := include.Context(context.Background())
	// v1.2.8: 注入 Android 平台桩, 解决 direct 出站在 Android 上
	// 因 netlink 被禁、InterfaceMonitor 为 nil 导致的启动 panic, 详见文件底部注释。
	ctx = service.ContextWith[adapter.PlatformInterface](ctx, &androidPlatformStub{})

	var opts option.Options
	err := opts.UnmarshalJSONContext(ctx, []byte(configJSON))
	if err != nil {
		return fmt.Errorf("配置JSON解析失败: %v", err)
	}

	boxInst, err := box.New(box.Options{
		Context: ctx,
		Options: opts,
	})
	if err != nil {
		return fmt.Errorf("SingBox配置初始化失败: %v", err)
	}
	instance = boxInst

	if err := boxInst.Start(); err != nil {
		_ = instance.Close()
		instance = nil
		return fmt.Errorf("SingBox启动失败: %v", err)
	}

	if tunFd > 0 {
		if err := startTun2Socks(tunFd); err != nil {
			if instance != nil {
				_ = instance.Close()
				instance = nil
			}
			return fmt.Errorf("tun2socks启动失败: %v", err)
		}
	}

	return nil
}

func startTun2Socks(tunFd int) (retErr error) {
	defer func() {
		if r := recover(); r != nil {
			retErr = fmt.Errorf("startTun2Socks Panic: %v\n[Stack]\n%s", r, string(debug.Stack()))
		}
	}()

	_ = syscall.SetNonblock(tunFd, true)

	// 为 gVisor 单独 dup 一份 fd: Kotlin 侧的 ParcelFileDescriptor 保留原始 fd 的
	// 所有权, Go 侧关闭时只关闭 dup 出来的 fd。否则两边重复 close 同一个 fd,
	// 轻则 EBADF 无害, 重则在 fd 号被复用时误关其它资源的 fd, 重连时偶发崩溃。
	dupFd, err := unix.Dup(tunFd)
	if err != nil {
		return fmt.Errorf("复制TUN FD失败: %w", err)
	}

	dev, err := fdbased.Open(strconv.Itoa(dupFd), 1500, 0)
	if err != nil {
		_ = unix.Close(dupFd)
		return fmt.Errorf("打开TUN设备(FD %d)失败: %w", tunFd, err)
	}

	proxyURL, err := url.Parse("socks5://127.0.0.1:2080")
	if err != nil {
		dev.Close()
		return fmt.Errorf("解析本地代理地址失败: %w", err)
	}
	p, err := t2sproxy.Parse(proxyURL)
	if err != nil {
		dev.Close()
		return fmt.Errorf("初始化本地代理失败: %w", err)
	}

	t := tunnel.T()
	if t == nil {
		dev.Close()
		return fmt.Errorf("tun2socks全局Tunnel未就绪")
	}
	t.SetProxy(p)

	stack, err := t2score.CreateStack(&t2score.Config{
		LinkEndpoint:     dev,
		TransportHandler: t,
	})
	if err != nil {
		dev.Close()
		return fmt.Errorf("创建网络栈失败: %w", err)
	}

	tunDevice = dev
	netStack = stack
	return nil
}

// StopProxy cleanly stops tun2socks and Sing-Box core
func StopProxy() (retErr error) {
	defer func() {
		if r := recover(); r != nil {
			retErr = fmt.Errorf("StopProxy Panic: %v\n[Stack]\n%s", r, string(debug.Stack()))
		}
	}()

	mu.Lock()
	defer mu.Unlock()
	return stopInternal()
}

func stopInternal() (retErr error) {
	defer func() {
		if r := recover(); r != nil {
			retErr = fmt.Errorf("stopInternal Panic: %v\n[Stack]\n%s", r, string(debug.Stack()))
		}
	}()

	if netStack != nil {
		netStack.Close()
		netStack.Wait()
		netStack = nil
	}
	if tunDevice != nil {
		tunDevice.Close()
		tunDevice = nil
	}
	if instance != nil {
		err := instance.Close()
		instance = nil
		return err
	}
	return nil
}

// ---------------------------------------------------------------------------
// Android 平台桩 (v1.2.8):
// 解决 sing-box direct 出站在 Android 上因 netlink 被禁而 panic 的问题。
//
// 背景: direct 出站在 Start() 时会调 fetchMyAddresses(),
// 里面取 h.network.InterfaceMonitor().MyInterfaces()。普通 Android App 里
// Google 禁了 NETLINK_ROUTE socket, sing-tun 的 NewNetworkUpdateMonitor
// 会返回 ErrNetlinkBanned, NetworkManager 的 interfaceMonitor 保持 nil,
// direct 出站一启动就空指针 panic (v1.2.4 曾因此删掉 direct 出站)。
//
// 解法: 往 service ctx 注入 PlatformInterface 桩, 让 NewNetworkManager
// 走 platform 分支, 拿到一个空但 nil-safe 的 monitor。
// direct 出站的 loopback 保护在本 App 不需要——App 自身已用
// addDisallowedApplication 排除出 VPN, 自身流量不可能回流进 TUN。
// ---------------------------------------------------------------------------

var (
	_ adapter.PlatformInterface      = (*androidPlatformStub)(nil)
	_ tun.DefaultInterfaceMonitor    = (*androidMonitorStub)(nil)
)

type androidMonitorStub struct{}

func (m *androidMonitorStub) Start() error { return nil }
func (m *androidMonitorStub) Close() error { return nil }
func (m *androidMonitorStub) DefaultInterface() *control.Interface { return nil }
func (m *androidMonitorStub) OverrideAndroidVPN() bool              { return false }
func (m *androidMonitorStub) AndroidVPNEnabled() bool               { return false }
func (m *androidMonitorStub) RegisterCallback(callback tun.DefaultInterfaceUpdateCallback) *list.Element[tun.DefaultInterfaceUpdateCallback] {
	return nil
}
func (m *androidMonitorStub) UnregisterCallback(element *list.Element[tun.DefaultInterfaceUpdateCallback]) {
}
func (m *androidMonitorStub) RegisterMyInterface(interfaceName string) {}
func (m *androidMonitorStub) MyInterfaces() []string                    { return nil }

type androidPlatformStub struct{}

func (p *androidPlatformStub) Initialize(networkManager adapter.NetworkManager) error { return nil }
func (p *androidPlatformStub) UsePlatformAutoDetectInterfaceControl() bool           { return false }
func (p *androidPlatformStub) AutoDetectInterfaceControl(fd int) error                { return nil }
func (p *androidPlatformStub) UsePlatformInterface() bool                            { return false }
func (p *androidPlatformStub) OpenInterface(options *tun.Options, platformOptions option.TunPlatformOptions) (tun.Tun, error) {
	return nil, os.ErrInvalid
}
func (p *androidPlatformStub) UsePlatformDefaultInterfaceMonitor() bool { return true }
func (p *androidPlatformStub) CreateDefaultInterfaceMonitor(logger logger.Logger) tun.DefaultInterfaceMonitor {
	return &androidMonitorStub{}
}
func (p *androidPlatformStub) UsePlatformNetworkInterfaces() bool { return false }
func (p *androidPlatformStub) NetworkInterfaces() ([]adapter.NetworkInterface, error) {
	return nil, os.ErrInvalid
}
func (p *androidPlatformStub) UnderNetworkExtension() bool              { return false }
func (p *androidPlatformStub) NetworkExtensionIncludeAllNetworks() bool { return false }
func (p *androidPlatformStub) ClearDNSCache()                          {}
func (p *androidPlatformStub) RequestPermissionForWIFIState() error    { return nil }
func (p *androidPlatformStub) ReadWIFIState() adapter.WIFIState        { return adapter.WIFIState{} }
func (p *androidPlatformStub) SystemCertificates() []string             { return nil }
func (p *androidPlatformStub) UsePlatformConnectionOwnerFinder() bool  { return false }
func (p *androidPlatformStub) FindConnectionOwner(request *adapter.FindConnectionOwnerRequest) (*adapter.ConnectionOwner, error) {
	return nil, os.ErrInvalid
}
func (p *androidPlatformStub) UsePlatformWIFIMonitor() bool       { return false }
func (p *androidPlatformStub) UsePlatformNotification() bool      { return false }
func (p *androidPlatformStub) SendNotification(notification *adapter.Notification) error {
	return nil
}
func (p *androidPlatformStub) MyInterfaceAddress() []netip.Addr { return nil }