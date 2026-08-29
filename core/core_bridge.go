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
		return fmt.Errorf("配置JSON解析失败: %v", err)
	}

	// 关键修复:sing-box从这个版本起,所有协议/DNS传输/服务的构造器
	// 都通过"注册表"挂在context上,而不是像以前那样靠全局单例。
	// 之前这里直接传 context.Background(),等于给box.New()一个空的、
	// 什么注册表都没挂的context,box.New()内部会去context里找
	// EndpointRegistry/InboundRegistry等,找不到就直接返回
	// "missing endpoint registry in context"这个错误——这正是
	// 手机上截图看到的那个报错。include.Context()会把
	// include包(靠下面这行的副作用import)里已经注册好的
	// 内置协议/服务,一次性挂到这个context上。
	ctx := include.Context(context.Background())

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