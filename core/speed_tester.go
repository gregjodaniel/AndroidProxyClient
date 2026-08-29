package corebridge

import (
	"context"
	"net"
	"net/http"
	"time"
)

// MeasureTCPPing 快速测试 TCP 三次握手延迟 (毫秒)
func MeasureTCPPing(address string, timeoutMs int) int32 {
	start := time.Now()
	conn, err := net.DialTimeout("tcp", address, time.Duration(timeoutMs)*time.Millisecond)
	if err != nil {
		return -1
	}
	_ = conn.Close()
	return int32(time.Since(start).Milliseconds())
}

// MeasureHTTPDelay 针对特定代理出站测试真实网页连通延迟
func MeasureHTTPDelay(client *http.Client, testURL string, timeoutMs int) int32 {
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()

	req, err := http.NewRequestWithContext(ctx, "GET", testURL, nil)
	if err != nil {
		return -1
	}

	start := time.Now()
	resp, err := client.Do(req)
	if err != nil {
		return -1
	}
	_ = resp.Body.Close()

	if resp.StatusCode >= 200 && resp.StatusCode < 400 {
		return int32(time.Since(start).Milliseconds())
	}
	return -1
}
