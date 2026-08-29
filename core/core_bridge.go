package corebridge

import (
	"fmt"
	"sync"

	_ "golang.org/x/mobile/bind"
	"github.com/sagernet/sing-box/experimental/libbox"
	_ "github.com/sagernet/sing-box/include"
	"github.com/xjasonlyu/tun2socks/v2/engine"
)

var (
	mu      sync.Mutex
	service *libbox.BoxService
	started bool
)

type PlatformBridge struct{}

func (b *PlatformBridge) LocalDNSTransport() libbox.LocalDNSTransport {
	return nil
}

func (b *PlatformBridge) UsePlatformAutoDetectInterfaceControl() bool {
	return false
}

func (b *PlatformBridge) AutoDetectInterfaceControl(fd int32) error {
	return nil
}

func (b *PlatformBridge) OpenTun(options libbox.TunOptions) (int32, error) {
	return 0, nil
}

func (b *PlatformBridge) WriteLog(message string) {}

func (b *PlatformBridge) UseProcFS() bool {
	return false
}

func (b *PlatformBridge) FindConnectionOwner(ipProtocol int32, sourceAddress string, sourcePort int32, destinationAddress string, destinationPort int32) (int32, error) {
	return 0, nil
}

func (b *PlatformBridge) PackageNameByUid(uid int32) (string, error) {
	return "", nil
}

func (b *PlatformBridge) UIDByPackageName(packageName string) (int32, error) {
	return 0, nil
}

func (b *PlatformBridge) StartDefaultInterfaceMonitor(listener libbox.InterfaceUpdateListener) error {
	return nil
}

func (b *PlatformBridge) CloseDefaultInterfaceMonitor(listener libbox.InterfaceUpdateListener) error {
	return nil
}

func (b *PlatformBridge) GetInterfaces() (libbox.NetworkInterfaceIterator, error) {
	return nil, nil
}

func (b *PlatformBridge) UnderNetworkExtension() bool {
	return false
}

func (b *PlatformBridge) IncludeAllNetworks() bool {
	return false
}

func (b *PlatformBridge) ClearDNSCache() {}

func (b *PlatformBridge) ReadSystemDNSServers() string {
	return ""
}

// StartProxy starts the Sing-Box core and attaches tun2socks to the Android VPN fd
func StartProxy(configJSON string, tunFd int) error {
	mu.Lock()
	defer mu.Unlock()

	stopInternal()

	bridge := &PlatformBridge{}
	boxService, err := libbox.NewService(configJSON, bridge)
	if err != nil {
		return fmt.Errorf("SingBox配置初始化失败: %w", err)
	}

	err = boxService.Start()
	if err != nil {
		boxService.Close()
		return fmt.Errorf("SingBox启动失败: %w", err)
	}
	service = boxService

	if tunFd > 0 {
		key := &engine.Key{
			Device: fmt.Sprintf("fd://%d", tunFd),
			Proxy:  "socks5://127.0.0.1:2080",
			MTU:    1500,
		}
		engine.Insert(key)
		engine.Start()
		started = true
	}

	return nil
}

// StopProxy cleanly stops tun2socks and Sing-Box core
func StopProxy() error {
	mu.Lock()
	defer mu.Unlock()
	return stopInternal()
}

func stopInternal() error {
	if started {
		engine.Stop()
		started = false
	}
	if service != nil {
		err := service.Close()
		service = nil
		return err
	}
	return nil
}