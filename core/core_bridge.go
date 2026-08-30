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
func StartProxy(configJSON string, tunFd int) error {
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
			// tun2socks没起来,把已经启动的sing-box也一并关掉,
			// 不要留一个"内核在跑但流量进不来"的半吊子状态
			boxInst.Close()
			instance = nil
			return fmt.Errorf("tun2socks启动失败: %v", err)
		}
	}

	return nil
}

// startTun2Socks 直接调用tun2socks的底层building block(core.CreateStack等),
// 而不是它对外暴露的 engine.Insert()/engine.Start() 便捷封装。
//
// 关键原因(这是这次修复的核心):engine.Start()源码里是这样写的——
//
//	func Start() {
//	    if err := start(); err != nil {
//	        log.Fatalf("[ENGINE] failed to start: %v", err)
//	    }
//	}
//
// tun2socks本身是设计给命令行工具用的,遇到启动失败就直接log.Fatalf,
// 这在Go里等价于打印完日志立刻os.Exit(1)——不是panic,不会被recover()
// 拦截,更不会被Kotlin那边的try-catch拦截,是整个进程级别的瞬间终止。
// 这正是"点击连接直接闪退、连个报错弹窗都没有"的真正原因,不是
// 我们上层代码的bug,是这个库的这一层封装本身不适合嵌入到常驻的
// 移动端App进程里用。
//
// 解决办法是绕开这层会自杀的封装,直接调用它底下那些正常返回error
// 的函数(fdbased.Open / proxy.Parse / core.CreateStack),这样任何
// 失败都会变成一个普通的Go error,顺着StartProxy()的返回值一路
// 传回Kotlin,变成一个可以正常显示、不会导致进程崩溃的错误弹窗。
func startTun2Socks(tunFd int) error {
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
	tunnel.T().SetProxy(p)

	stack, err := t2score.CreateStack(&t2score.Config{
		LinkEndpoint:     dev,
		TransportHandler: tunnel.T(),
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
func StopProxy() error {
	mu.Lock()
	defer mu.Unlock()
	return stopInternal()
}

func stopInternal() error {
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
