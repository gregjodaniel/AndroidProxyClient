package corebridge

import (
	"context"
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
	started  bool
)

// StartProxy starts the Sing-Box core and attaches tun2socks to the Android VPN fd
func StartProxy(configJSON string, tunFd int) error {
	mu.Lock()
	defer mu.Unlock()

	stopInternal()

	// 关键: 先通过 include.Context 构造包含全套协议/DNS/出站注册表的 context
	ctx := include.Context(context.Background())

	// 关键: 使用 opts.UnmarshalJSONContext(ctx, ...) 结合注册表上下文精准反序列化
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

	err = boxInst.Start()
	if err != nil {
		return fmt.Errorf("SingBox启动失败: %v", err)
	}
	instance = boxInst

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
	if instance != nil {
		err := instance.Close()
		instance = nil
		return err
	}
	return nil
}