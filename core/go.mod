module corebridge

go 1.23

require (
	github.com/sagernet/sing-box v1.13.20
	// v2.5.2有一个已知问题:多次start/stop会因为fd被关闭两次而崩溃,
	// 这个bug在v2.7.0才修复(见官方release notes里的PR #495)。
	// "连接-断开-再连接"是这个App最高频的操作路径,必须用修复后的版本。
	github.com/xjasonlyu/tun2socks/v2 v2.7.0
)