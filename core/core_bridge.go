package corebridge

import (
	"context"
	"fmt"
	"net/url"
	"os"
	"runtime/debug"
	"strconv"
	"sync"
	"syscall"

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

// InitCrashLogger redirects stderr (FD 2) to a persistent file so any Go fatal errors or panics in background goroutines are recorded
func InitCrashLogger(path string) {
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0666)
	if err == nil {
		_ = syscall.Dup2(int(f.Fd()), 2)
	}
}

// StartProxy starts the Sing-Box core and attaches tun2socks to the Android VPN fd
func StartProxy(configJSON string, tunFd int) (retErr error) {
	defer func() {
		if r := recover(); r != nil {
			if instance != nil {
				_ = instance.Close()
				instance = nil
			}
			retErr = fmt.Errorf("Go内核Panic异常: %v\n[Stack]\n%s", r, string(debug.Stack()))
		}
	}()

	mu.Lock()
	defer mu.Unlock()

	stopInternal()

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
	instance = boxInst

	if err := boxInst.Start(); err != nil {
		_ = instance.Close()
		instance = nil
		return fmt.Errorf("SingBox启动失败: %v", err)
	}

	if tunFd > 0 {
		if err := startTun2Socks(tunFd); err != nil {
			if instance != nil {
				_ = instance.Close()
				instance = nil
			}
			return fmt.Errorf("tun2socks启动失败: %v", err)
		}
	}

	return nil
}

func startTun2Socks(tunFd int) (retErr error) {
	defer func() {
		if r := recover(); r != nil {
			retErr = fmt.Errorf("startTun2Socks Panic: %v\n[Stack]\n%s", r, string(debug.Stack()))
		}
	}()

	_ = syscall.SetNonblock(tunFd, true)

	dev, err := fdbased.Open(strconv.Itoa(tunFd), 1500, 0)
	if err != nil {
		return fmt.Errorf("打开TUN设备(FD %d)失败: %w", tunFd, err)
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
			retErr = fmt.Errorf("StopProxy Panic: %v\n[Stack]\n%s", r, string(debug.Stack()))
		}
	}()

	mu.Lock()
	defer mu.Unlock()
	return stopInternal()
}

func stopInternal() (retErr error) {
	defer func() {
		if r := recover(); r != nil {
			retErr = fmt.Errorf("stopInternal Panic: %v\n[Stack]\n%s", r, string(debug.Stack()))
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