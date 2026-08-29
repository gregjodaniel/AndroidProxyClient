package corebridge

import (
	"context"
	"encoding/json"
	"syscall"

	box "github.com/sagernet/sing-box"
	_ "github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	_ "golang.org/x/mobile/bind"
)

// SocketProtector 对应 Java 层的回调接口 (gomobile 生成)
type SocketProtector interface {
	Protect(fd int32) bool
}

type EngineWrapper struct {
	instance  *box.Box
	protector SocketProtector
}

func NewEngine(protector SocketProtector) *EngineWrapper {
	return &EngineWrapper{
		protector: protector,
	}
}

// DialControl 实现底层 Socket 劫持与 Protect
func (e *EngineWrapper) DialControl(network, address string, c syscall.RawConn) error {
	if e.protector == nil {
		return nil
	}
	var protectErr error
	err := c.Control(func(fd uintptr) {
		if !e.protector.Protect(int32(fd)) {
			protectErr = syscall.EACCES
		}
	})
	if err != nil {
		return err
	}
	return protectErr
}

// Start 启动 sing-box 实例
func (e *EngineWrapper) Start(configJSON string) error {
	var opts option.Options
	err := json.Unmarshal([]byte(configJSON), &opts)
	if err != nil {
		return err
	}

	ctx := context.Background()
	instance, err := box.New(box.Options{
		Context: ctx,
		Options: opts,
	})
	if err != nil {
		return err
	}

	e.instance = instance
	return e.instance.Start()
}

func (e *EngineWrapper) Stop() error {
	if e.instance != nil {
		err := e.instance.Close()
		e.instance = nil
		return err
	}
	return nil
}