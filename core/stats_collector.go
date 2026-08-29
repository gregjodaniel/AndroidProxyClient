package corebridge

import (
	"sync"
	"time"
)

type StatsCallback interface {
	OnStatsUpdate(uplinkSpeed, downlinkSpeed, totalUplink, totalDownlink int64)
}

type TrafficMonitor struct {
	mu            sync.Mutex
	callback      StatsCallback
	lastUplink    int64
	lastDownlink  int64
	totalUplink   int64
	totalDownlink int64
	stopChan      chan struct{}
}

func NewTrafficMonitor(cb StatsCallback) *TrafficMonitor {
	return &TrafficMonitor{
		callback: cb,
		stopChan: make(chan struct{}),
	}
}

func (tm *TrafficMonitor) RecordUplink(bytes int64) {
	tm.mu.Lock()
	defer tm.mu.Unlock()
	tm.totalUplink += bytes
}

func (tm *TrafficMonitor) RecordDownlink(bytes int64) {
	tm.mu.Lock()
	defer tm.mu.Unlock()
	tm.totalDownlink += bytes
}

func (tm *TrafficMonitor) StartPolling(interval time.Duration) {
	ticker := time.NewTicker(interval)
	go func() {
		for {
			select {
			case <-ticker.C:
				tm.mu.Lock()
				upSpeed := (tm.totalUplink - tm.lastUplink) / int64(interval.Seconds())
				downSpeed := (tm.totalDownlink - tm.lastDownlink) / int64(interval.Seconds())
				tm.lastUplink = tm.totalUplink
				tm.lastDownlink = tm.totalDownlink
				
				totalUp := tm.totalUplink
				totalDown := tm.totalDownlink
				tm.mu.Unlock()

				if tm.callback != nil {
					tm.callback.OnStatsUpdate(upSpeed, downSpeed, totalUp, totalDown)
				}
			case <-tm.stopChan:
				ticker.Stop()
				return
			}
		}
	}()
}

func (tm *TrafficMonitor) Stop() {
	close(tm.stopChan)
}
