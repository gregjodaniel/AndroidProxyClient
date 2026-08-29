package corebridge

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	_ "golang.org/x/mobile/bind"
	"github.com/xjasonlyu/tun2socks/v2/engine"
)

var (
	mu       sync.Mutex
	instance *box.Box
	cancel   context.CancelFunc
	started  bool
)

// StartProxy starts the Sing-Box core and attaches tun2socks to the Android VPN fd
func StartProxy(configJSON string, tunFd int) error {
	mu.Lock()
	defer mu.Unlock()

	stopInternal()

	var opts option.Options
	err := json.Unmarshal([]byte(configJSON), &opts)
	if err != nil {
		return fmt.Errorf("配置JSON解析失败: %w", err)
	}

	ctx, cancelFunc := context.WithCancel(context.Background())
	ctx = box.Context(
		ctx,
		include.InboundRegistry(),
		include.OutboundRegistry(),
		include.EndpointRegistry(),
	)

	boxInst, err := box.New(box.Options{
		Context: ctx,
		Options: opts,
	})
	if err != nil {
		cancelFunc()
		return fmt.Errorf("SingBox配置初始化失败: %w", err)
	}

	err = boxInst.Start()
	if err != nil {
		cancelFunc()
		return fmt.Errorf("SingBox启动失败: %w", err)
	}
	instance = boxInst
	cancel = cancelFunc

	if tunFd > 0 {
		key := &engine.Key{
			Device: fmt.Sprintf("fd://%d", tunFd),
			Proxy:  "socks5://127.0.0.1:2080",
			MTU:    1500,
		}
		engine.Insert(key)
		err = engine.Start()
		if err != nil {
			stopInternal()
			return fmt.Errorf("tun2socks启动失败: %w", err)
		}
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
	if instance != nil {
		err := instance.Close()
		instance = nil
		if cancel != nil {
			cancel()
			cancel = nil
		}
		return err
	}
	if cancel != nil {
		cancel()
		cancel = nil
	}
	return nil
}