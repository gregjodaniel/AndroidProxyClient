package corebridge

import (
	"fmt"

	"github.com/sagernet/sing-box/experimental/libbox"
	_ "github.com/sagernet/sing-box/include"
	_ "golang.org/x/mobile/bind"
)

type SocketProtector interface {
	Protect(fd int32) bool
}

type EngineWrapper struct {
	service   *libbox.BoxService
	protector SocketProtector
}

func NewEngine(protector SocketProtector) *EngineWrapper {
	return &EngineWrapper{
		protector: protector,
	}
}

func (e *EngineWrapper) AutoDetectInterfaceControl(fd int32) error {
	if e.protector != nil {
		if !e.protector.Protect(fd) {
			return fmt.Errorf("protect failed for fd %d", fd)
		}
	}
	return nil
}

func (e *EngineWrapper) OpenTun(options libbox.TunOptions) (int32, error) {
	return 0, nil
}

func (e *EngineWrapper) UsePlatformAutoDetectInterfaceControl() bool {
	return true
}

func (e *EngineWrapper) UsePlatformDefaultInterfaceMonitor() bool {
	return false
}

func (e *EngineWrapper) UsePlatformInterfaceControl() bool {
	return false
}

func (e *EngineWrapper) ClearDNSCache() {
}

func (e *EngineWrapper) FindConnectionOwner(ipProtocol int32, sourceAddress string, sourcePort int32, destinationAddress string, destinationPort int32) (int32, error) {
	return 0, nil
}

func (e *EngineWrapper) PackageNameByUid(uid int32) (string, error) {
	return "", nil
}

func (e *EngineWrapper) UidByPackageName(packageName string) (int32, error) {
	return 0, nil
}

func (e *EngineWrapper) WriteLog(message string) {
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