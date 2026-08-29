package corebridge

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"

	box "github.com/sagernet/sing-box"
	_ "github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	_ "golang.org/x/mobile/bind"
	"github.com/xjasonlyu/tun2socks/v2/engine"
)

var (
	mu       sync.Mutex
	instance *box.Box
	tunKey   *engine.Key
)

// StartProxy starts the Sing-Box core and attaches tun2socks to the Android VPN fd
func StartProxy(configJSON string, tunFd int) error {
	mu.Lock()
	defer mu.Unlock()

	stopInternal()

	var opts option.Options
	err := json.Unmarshal([]byte(configJSON), &opts)
	if err != nil {
		return fmt.Errorf("配置解析错误: %w", err)
	}

	boxInst, err := box.New(box.Options{
		Context: context.Background(),
		Options: opts,
	})
	if err != nil {
		return fmt.Errorf("SingBox内核初始化失败: %w", err)
	}

	err = boxInst.Start()
	if err != nil {
		return fmt.Errorf("SingBox内核启动失败: %w", err)
	}
	instance = boxInst

	if tunFd > 0 {
		key := &engine.Key{
			Device: fmt.Sprintf("fd://%d", tunFd),
			Proxy:  "socks5://127.0.0.1:2080",
			MTU:    1500,
		}
		engine.Start(key)
		tunKey = key
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
	if tunKey != nil {
		engine.Stop()
		tunKey = nil
	}
	if instance != nil {
		err := instance.Close()
		instance = nil
		return err
	}
	return nil
}