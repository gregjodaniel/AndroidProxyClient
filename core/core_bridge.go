package corebridge

import (
	"context"
	"encoding/json"

	box "github.com/sagernet/sing-box"
	_ "github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	_ "golang.org/x/mobile/bind"
)

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