package main

import (
	"embed"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

//go:embed public/index.html
var publicFS embed.FS

var upgrader = websocket.Upgrader{
	ReadBufferSize:  4096,
	WriteBufferSize: 4096,
	// 客户端是安卓 App / 调试脚本，不是浏览器，不做 Origin 限制
	CheckOrigin: func(r *http.Request) bool { return true },
}

// client 一条在线长连接。gorilla 只允许一个写者，所以所有写都过 mu。
type client struct {
	conn     *websocket.Conn
	deviceID string

	mu     sync.Mutex
	closed bool
}

func (c *client) sendJSON(v any) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return errors.New("connection closed")
	}
	_ = c.conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
	return c.conn.WriteJSON(v)
}

func (c *client) ping() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return errors.New("connection closed")
	}
	return c.conn.WriteControl(websocket.PingMessage, nil, time.Now().Add(10*time.Second))
}

func (c *client) closeWith(code int, reason string) {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return
	}
	c.closed = true
	_ = c.conn.WriteControl(
		websocket.CloseMessage,
		websocket.FormatCloseMessage(code, reason),
		time.Now().Add(time.Second),
	)
	c.mu.Unlock()
	_ = c.conn.Close()
}

func (c *client) close() { c.closeWith(websocket.CloseNormalClosure, "server closing") }

func (s *Server) closeAllSockets() {
	s.mu.Lock()
	all := make([]*client, 0, len(s.sockets))
	for _, c := range s.sockets {
		all = append(all, c)
	}
	s.mu.Unlock()
	for _, c := range all {
		c.closeWith(1001, "server shutdown")
	}
}

// ---------------------------------------------------------------- /ws

func (s *Server) handleWS(w http.ResponseWriter, r *http.Request) {
	deviceID := r.URL.Query().Get("deviceId")
	apiKey := r.URL.Query().Get("apiKey")

	s.mu.Lock()
	dev := s.devices[deviceID]
	authorized := dev != nil && apiKey != "" && dev.APIKey == apiKey
	s.mu.Unlock()

	if !authorized {
		reason := "设备不存在"
		if dev != nil {
			reason = "密钥不匹配"
		}
		log.Printf("[ws] 拒绝连接 deviceId=%q 原因=%s ip=%s", deviceID, reason, clientIP(r))
		writeErr(w, http.StatusUnauthorized, "密钥不正确")
		return
	}

	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("[ws] 升级失败 deviceId=%s: %v", deviceID, err)
		return
	}

	c := &client{conn: conn, deviceID: deviceID}
	_ = conn.SetReadDeadline(time.Now().Add(60 * time.Second))
	conn.SetPongHandler(func(string) error {
		return conn.SetReadDeadline(time.Now().Add(60 * time.Second))
	})

	// 同一设备重复连接：踢掉旧的
	s.mu.Lock()
	old := s.sockets[deviceID]
	s.sockets[deviceID] = c
	dev.Online = true
	dev.LastSeenAt = nowISO()
	s.saveLocked()
	online := len(s.sockets)
	s.mu.Unlock()

	if old != nil && old != c {
		old.closeWith(4000, "replaced by new connection")
	}
	log.Printf("[ws] %s 已连接（在线 %d）", deviceID, online)

	_ = c.sendJSON(map[string]any{
		"type":       "ready",
		"deviceId":   deviceID,
		"serverTime": nowISO(),
		"deviceName": dev.Name,
	})

	s.mu.Lock()
	s.flushQueueLocked(dev, c)
	s.mu.Unlock()

	// 心跳：20 秒一次 ping，客户端（OkHttp / ws）会自动回 pong；60 秒没动静就断开
	stopPing := make(chan struct{})
	go func() {
		t := time.NewTicker(20 * time.Second)
		defer t.Stop()
		for {
			select {
			case <-t.C:
				if err := c.ping(); err != nil {
					c.closeWith(websocket.CloseAbnormalClosure, "ping failed")
					return
				}
			case <-stopPing:
				return
			}
		}
	}()

	for {
		_, raw, readErr := conn.ReadMessage()
		if readErr != nil {
			break
		}
		s.handleWSMessage(dev, c, raw)
	}

	close(stopPing)
	c.close()

	s.mu.Lock()
	if s.sockets[deviceID] == c {
		delete(s.sockets, deviceID)
		dev.Online = false
		dev.LastSeenAt = nowISO()
		s.saveLocked()
		log.Printf("[ws] %s 已断开（在线 %d）", deviceID, len(s.sockets))
		// 只有显式配置了 offline-retention > 0 才可能"断开即删"；
		// 默认 0 = 保留设备身份，避免客户端因 401 而换密钥
		if s.policy.OfflineRetention == 0 {
			// 这里刻意什么都不做：保留设备与密钥
		}
	}
	s.mu.Unlock()
}

func (s *Server) handleWSMessage(dev *Device, c *client, raw []byte) {
	var msg map[string]any
	if err := json.Unmarshal(raw, &msg); err != nil {
		return
	}
	s.mu.Lock()
	dev.LastSeenAt = nowISO()
	s.mu.Unlock()

	switch toStr(msg["type"]) {
	case "ping":
		_ = c.sendJSON(map[string]any{"type": "pong", "t": time.Now().UnixMilli()})

	case "ack", "status":
		id := toStr(msg["id"])
		status := toStr(msg["status"])
		if id == "" || !allowedAck[status] {
			return
		}
		s.mu.Lock()
		n := s.notifications[id]
		if n != nil && n.DeviceID == dev.ID {
			s.setStatusLocked(n, status, toStr(msg["note"]))
			log.Printf("[ack] %s %s → %s", dev.ID, id, status)
		}
		s.mu.Unlock()

	case "hello":
		s.mu.Lock()
		s.saveLocked()
		s.mu.Unlock()
	}
}

// ---------------------------------------------------------------- 静态资源（调试控制台）

func (s *Server) handleStatic(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/")
	if path == "" {
		path = "index.html"
	}
	if path != "index.html" {
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte("404 Not Found"))
		return
	}
	body, err := publicFS.ReadFile("public/index.html")
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_, _ = w.Write(body)
}
