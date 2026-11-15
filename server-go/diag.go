package main

// 自检面板：GET /api/v1/diag
//
// 手机不响的时候先看这个接口，它回答三个问题：
//   1. 手机连上来了吗（devices[].online / lastSeenAt）
//   2. 通知发出去了吗、卡在哪一步（recentNotifications[].status / note）
//   3. 有没有离线积压（queued）

import (
	"net/http"
	"sort"
	"time"
)

func (s *Server) handleDiag(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	defer s.mu.Unlock()

	devices := make([]map[string]any, 0, len(s.devices))
	for _, d := range s.devices {
		devices = append(devices, map[string]any{
			"deviceId":   d.ID,
			"name":       d.Name,
			"online":     d.Online,
			"lastSeenAt": emptyToNil(d.LastSeenAt),
			"queued":     len(s.queue[d.ID]),
		})
	}
	sort.Slice(devices, func(i, j int) bool {
		return toStr(devices[i]["lastSeenAt"]) > toStr(devices[j]["lastSeenAt"])
	})

	all := make([]*Notification, 0, len(s.notifications))
	for _, n := range s.notifications {
		all = append(all, n)
	}
	sort.Slice(all, func(i, j int) bool { return all[i].CreatedAt.After(all[j].CreatedAt) })
	if len(all) > 20 {
		all = all[:20]
	}
	recent := make([]map[string]any, 0, len(all))
	for _, n := range all {
		recent = append(recent, map[string]any{
			"id":        n.ID,
			"deviceId":  n.DeviceID,
			"status":    n.Status,
			"note":      emptyToNil(n.Note),
			"caller":    n.Payload.CallerName,
			"text":      clip(n.Payload.Text, 40),
			"createdAt": n.CreatedAt.UTC().Format(time.RFC3339Nano),
			"ageSec":    int(time.Since(n.CreatedAt).Seconds()),
		})
	}

	hints := make([]string, 0, 4)
	onlineCount := 0
	for _, d := range s.devices {
		if d.Online {
			onlineCount++
		}
	}
	switch {
	case len(s.devices) == 0:
		hints = append(hints, "还没有任何设备注册：先在 App 里填服务器地址并点「注册设备」")
	case onlineCount == 0:
		hints = append(hints, "有设备但都不在线：手机端 App 需在运行、且能访问到本服务（地址/端口/防火墙/安全组）")
	}
	if len(s.notifications) == 0 && onlineCount > 0 {
		hints = append(hints, "设备在线但没有收到过任何通知：检查通知端用的 X-API-Key 是否属于该设备")
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"uptimeSec":           int(time.Since(startedAt).Seconds()),
		"devices":             devices,
		"online":              onlineCount,
		"recentNotifications": recent,
		"hints":               hints,
		"policy": map[string]any{
			"offlineRetention": s.policy.OfflineRetention.String(),
			"queueRetention":   s.policy.QueueRetention.String(),
			"notifyRetention":  s.policy.NotifyRetention.String(),
			"queueMax":         s.policy.QueueMax,
		},
	})
}
