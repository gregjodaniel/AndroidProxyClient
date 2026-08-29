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
	return true
}

func (e *EngineWrapper) UsePlatformInterfaceControl() bool {
	return true
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