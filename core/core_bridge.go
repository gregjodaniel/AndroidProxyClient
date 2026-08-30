package corebridge

import (
	"context"
	"fmt"
	"net/url"
	"strconv"
	"sync"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	_ "golang.org/x/mobile/bind"

	t2score "github.com/xjasonlyu/tun2socks/v2/core"
	"github.com/xjasonlyu/tun2socks/v2/core/device"
	"github.com/xjasonlyu/tun2socks/v2/core/device/fdbased"
	t2sproxy "github.com/xjasonlyu/tun2socks/v2/proxy"
	"github.com/xjasonlyu/tun2socks/v2/tunnel"
	gvstack "gvisor.dev/gvisor/pkg/tcpip/stack"
)

var (
	mu        sync.Mutex
	instance  *box.Box
	tunDevice device.Device
	netStack  *gvstack.Stack
)

// StartProxy starts the Sing-Box core and attaches tun2socks to the Android VPN fd
func StartProxy(configJSON string, tunFd int) (retErr error) {
	defer func() {
		if r := recover(); r != nil {
			retErr = fmt.Errorf("Go内核Panic异常: %v", r)
		}
	}()

	mu.Lock()
	defer mu.Unlock()

	stopInternal()

	// 用include.Context()挂上sing-box内置的协议/DNS/服务注册表
	ctx := include.Context(context.Background())

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

	if err := boxInst.Start(); err != nil {
		return fmt.Errorf("SingBox启动失败: %v", err)
	}
	instance = boxInst

	if tunFd > 0 {
		if err := startTun2Socks(tunFd); err != nil {
			boxInst.Close()
			instance = nil
			return fmt.Errorf("tun2socks启动失败: %v", err)
		}
	}

	return nil
}

func startTun2Socks(tunFd int) (retErr error) {
	defer func() {
		if r := recover(); r != nil {
			retErr = fmt.Errorf("tun2socks Panic: %v", r)
		}
	}()

	dev, err := fdbased.Open(strconv.Itoa(tunFd), 1500, 0)
	if err != nil {
		return fmt.Errorf("打开TUN设备失败: %w", err)
	}

	proxyURL, err := url.Parse("socks5://127.0.0.1:2080")
	if err != nil {
		dev.Close()
		return fmt.Errorf("解析本地代理地址失败: %w", err)
	}
	p, err := t2sproxy.Parse(proxyURL)
	if err != nil {
		dev.Close()
		return fmt.Errorf("初始化本地代理失败: %w", err)
	}

	t := tunnel.T()
	if t == nil {
		dev.Close()
		return fmt.Errorf("tun2socks全局Tunnel未就绪")
	}
	t.SetProxy(p)

	stack, err := t2score.CreateStack(&t2score.Config{
		LinkEndpoint:     dev,
		TransportHandler: t,
	})
	if err != nil {
		dev.Close()
		return fmt.Errorf("创建网络栈失败: %w", err)
	}

	tunDevice = dev
	netStack = stack
	return nil
}

// StopProxy cleanly stops tun2socks and Sing-Box core
func StopProxy() (retErr error) {
	defer func() {
		if r := recover(); r != nil {
			retErr = fmt.Errorf("StopProxy Panic: %v", r)
		}
	}()

	mu.Lock()
	defer mu.Unlock()
	return stopInternal()
}

func stopInternal() (retErr error) {
	defer func() {
		if r := recover(); r != nil {
			retErr = fmt.Errorf("stopInternal Panic: %v", r)
		}
	}()

	if netStack != nil {
		netStack.Close()
		netStack.Wait()
		netStack = nil
	}
	if tunDevice != nil {
		tunDevice.Close()
		tunDevice = nil
	}
	if instance != nil {
		err := instance.Close()
		instance = nil
		return err
	}
	return nil
}