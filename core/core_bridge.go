package corebridge

import (
	"fmt"

	"github.com/sagernet/sing-box/experimental/libbox"
	_ "github.com/sagernet/sing-box/include"
	_ "golang.org/x/mobile/bind"
)

type PlatformBridge interface {
	OpenTun() (int32, error)
	Protect(fd int32) bool
}

type EngineWrapper struct {
	service *libbox.BoxService
	bridge  PlatformBridge
}

func NewEngine(bridge PlatformBridge) *EngineWrapper {
	return &EngineWrapper{
		bridge: bridge,
	}
}

func (e *EngineWrapper) LocalDNSTransport() libbox.LocalDNSTransport {
	return nil
}

func (e *EngineWrapper) UsePlatformAutoDetectInterfaceControl() bool {
	return true
}

func (e *EngineWrapper) AutoDetectInterfaceControl(fd int32) error {
	if e.bridge != nil {
		if !e.bridge.Protect(fd) {
			return fmt.Errorf("protect failed for fd %d", fd)
		}
	}
	return nil
}

func (e *EngineWrapper) OpenTun(options libbox.TunOptions) (int32, error) {
	if e.bridge != nil {
		return e.bridge.OpenTun()
	}
	return 0, fmt.Errorf("no platform bridge")
}

func (e *EngineWrapper) UseProcFS() bool {
	return false
}

func (e *EngineWrapper) FindConnectionOwner(ipProtocol int32, sourceAddress string, sourcePort int32, destinationAddress string, destinationPort int32) (*libbox.ConnectionOwner, error) {
	return nil, nil
}

func (e *EngineWrapper) StartDefaultInterfaceMonitor(listener libbox.InterfaceUpdateListener) error {
	return nil
}

func (e *EngineWrapper) CloseDefaultInterfaceMonitor(listener libbox.InterfaceUpdateListener) error {
	return nil
}

func (e *EngineWrapper) GetInterfaces() (libbox.NetworkInterfaceIterator, error) {
	return nil, nil
}

func (e *EngineWrapper) UnderNetworkExtension() bool {
	return false
}

func (e *EngineWrapper) IncludeAllNetworks() bool {
	return false
}

func (e *EngineWrapper) ReadWIFIState() *libbox.WIFIState {
	return nil
}

func (e *EngineWrapper) SystemCertificates() libbox.StringIterator {
	return nil
}

func (e *EngineWrapper) ClearDNSCache() {
}

func (e *EngineWrapper) SendNotification(notification *libbox.Notification) error {
	return nil
}

func (e *EngineWrapper) Start(configJSON string) error {
	service, err := libbox.NewService(configJSON, e)
	if err != nil {
		return err
	}
	err = service.Start()
	if err != nil {
		return err
	}
	e.service = service
	return nil
}

func (e *EngineWrapper) Stop() error {
	if e.service != nil {
		err := e.service.Close()
		e.service = nil
		return err
	}
	return nil
}