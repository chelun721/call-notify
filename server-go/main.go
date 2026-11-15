package main

// 远程来电通知 · 通信服务端（Go 单文件可执行版）
//
// 职责与 Node 版完全一致：只做两端之间的通信中转。
//
//	通知端  POST /api/v1/notify  (X-API-Key)
//	   ↓
//	本服务  ── WebSocket 长连接 ──►  安卓 App（模拟系统来电 + TTS 朗读）
//
// 编译：
//	GOOS=windows GOARCH=amd64 go build -trimpath -ldflags "-s -w" -o callnotify-server.exe
//	GOOS=linux   GOARCH=amd64 go build -trimpath -ldflags "-s -w" -o callnotify-server
//
// 运行： ./callnotify-server            （默认 0.0.0.0:8787，data.json 落在可执行文件同目录）
//
//	-port 9000 -host 127.0.0.1 -data /var/lib/callnotify/data.json
//	也认环境变量 PORT / HOST / DATA_FILE

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
	"unicode/utf8"

	"golang.org/x/text/encoding/simplifiedchinese"
	"golang.org/x/text/transform"
)

// ---------------------------------------------------------------- 配置

const (
	rateLimitPerMin     = 30 // 单设备每分钟最多下发条数
	deviceCreatePerHour = 10 // 单 IP 每小时最多注册设备数
	maxBodyBytes        = 64 << 10
)

// 可配置的保留策略（默认值在 main 里按 flag/环境变量覆盖）
type Policy struct {
	OfflineRetention time.Duration // 设备离线后保留多久再删；0 = 断开即删
	QueueRetention   time.Duration // 未送达（queued）通知保留多久，重连后补发
	NotifyRetention  time.Duration // 终态通知在内存里保留多久（用于查状态）
	QueueMax         int           // 单设备离线队列上限
}

var allowedAck = map[string]bool{
	"delivered": true, "ringing": true, "answered": true, "completed": true,
	"rejected": true, "missed": true, "failed": true, "ignored": true,
}

// ---------------------------------------------------------------- 数据模型

type Device struct {
	ID         string `json:"id"`
	Name       string `json:"name"`
	APIKey     string `json:"apiKey"`
	CreatedAt  string `json:"createdAt"`
	LastSeenAt string `json:"lastSeenAt,omitempty"`
	Online     bool   `json:"online"`
}

type Payload struct {
	CallerName   string `json:"callerName"`
	CallerNumber string `json:"callerNumber"`
	Text         string `json:"text"`
	DelaySeconds int    `json:"delaySeconds"`
	RingSeconds  int    `json:"ringSeconds"`
	SpeakTimes   int    `json:"speakTimes"`
	Priority     string `json:"priority"`
	ReceivedAt   string `json:"receivedAt"`
}

type Notification struct {
	ID        string
	DeviceID  string
	Status    string
	Payload   Payload
	CreatedAt time.Time
	UpdatedAt string
	Note      string
}

func (n *Notification) view() map[string]any {
	return map[string]any{
		"id":        n.ID,
		"status":    n.Status,
		"note":      emptyToNil(n.Note),
		"createdAt": n.CreatedAt.UTC().Format(time.RFC3339Nano),
		"updatedAt": n.UpdatedAt,
		"payload":   n.Payload,
	}
}

// ---------------------------------------------------------------- 服务

type Server struct {
	mu sync.Mutex

	devices    map[string]*Device // deviceId -> device
	deviceByKey map[string]*Device // apiKey  -> device

	notifications map[string]*Notification
	queue         map[string][]string // deviceId -> 待补发通知 id

	sockets map[string]*client // deviceId -> 在线连接

	rateBuckets   map[string][]time.Time // deviceId -> 下发时间戳
	createBuckets map[string][]time.Time // ip -> 注册时间戳

	dataFile string
	policy   Policy
}

func NewServer(dataFile string, policy Policy) *Server {
	s := &Server{
		devices:       make(map[string]*Device),
		deviceByKey:   make(map[string]*Device),
		notifications: make(map[string]*Notification),
		queue:         make(map[string][]string),
		sockets:       make(map[string]*client),
		rateBuckets:   make(map[string][]time.Time),
		createBuckets: make(map[string][]time.Time),
		dataFile:      dataFile,
		policy:        policy,
	}
	s.load()
	return s
}

func (s *Server) load() {
	raw, err := os.ReadFile(s.dataFile)
	if err != nil {
		if !os.IsNotExist(err) {
			log.Printf("[store] 读取 %s 失败：%v", s.dataFile, err)
		}
		return
	}
	var file struct {
		Devices map[string]*Device `json:"devices"`
	}
	if err := json.Unmarshal(raw, &file); err != nil {
		log.Printf("[store] 解析失败，使用空数据：%v", err)
		return
	}
	for id, d := range file.Devices {
		d.Online = false // 进程重启后所有连接都不在了
		d.ID = id
		s.devices[id] = d
		s.deviceByKey[d.APIKey] = d
	}
}

// saveLocked 只落盘设备密钥，通知正文只在内存（30 分钟 TTL）
func (s *Server) saveLocked() {
	tmp := s.dataFile + ".tmp"
	body, err := json.MarshalIndent(map[string]any{"devices": s.devices}, "", "  ")
	if err != nil {
		log.Printf("[store] 序列化失败：%v", err)
		return
	}
	if err := os.MkdirAll(filepath.Dir(s.dataFile), 0o755); err != nil {
		log.Printf("[store] 建目录失败：%v", err)
		return
	}
	if err := os.WriteFile(tmp, body, 0o600); err != nil {
		log.Printf("[store] 写入失败：%v", err)
		return
	}
	if err := os.Rename(tmp, s.dataFile); err != nil {
		log.Printf("[store] 落盘失败：%v", err)
	}
}

func (s *Server) cleanupNotifications() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.janitorLocked()
}

// janitorLocked 按保留策略清理：设备过期、通知过期、队列残留
func (s *Server) janitorLocked() {
	now := time.Now()

	for id, n := range s.notifications {
		ttl := s.policy.NotifyRetention
		if n.Status == "queued" {
			// 未送达的按另一套时长算：排队久了没补发出去就没意义了
			ttl = s.policy.QueueRetention
		}
		if ttl > 0 && now.Sub(n.CreatedAt) > ttl {
			delete(s.notifications, id)
		}
	}

	for id, d := range s.devices {
		if d.Online {
			continue
		}
		// 0 = 永不按"离线"删除设备。
		// 删设备 = 删身份 = 客户端 401 = 换密钥，这个代价远大于清理那点存储。
		if s.policy.OfflineRetention <= 0 {
			continue
		}
		ref := d.LastSeenAt
		if ref == "" {
			ref = d.CreatedAt // 注册后从没连上过的，也按注册时间算
		}
		t, err := time.Parse(time.RFC3339Nano, ref)
		if err != nil {
			continue
		}
		if now.Sub(t) >= s.policy.OfflineRetention {
			s.purgeDeviceLocked(id, "离线超过 "+s.policy.OfflineRetention.String())
		}
	}
}

// purgeDeviceLocked 删除设备及其队列、通知
func (s *Server) purgeDeviceLocked(deviceID, reason string) {
	dev := s.devices[deviceID]
	if dev == nil {
		return
	}
	delete(s.devices, deviceID)
	delete(s.deviceByKey, dev.APIKey)
	delete(s.queue, deviceID)
	for id, n := range s.notifications {
		if n.DeviceID == deviceID {
			delete(s.notifications, id)
		}
	}
	s.saveLocked()
	log.Printf("[device] 删除设备 %s（%s）", deviceID, reason)
}

// ---------------------------------------------------------------- 工具

func newID(prefix string) string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return prefix + hex.EncodeToString(b)
}

func newKey() string {
	b := make([]byte, 24)
	_, _ = rand.Read(b)
	return "cn_" + hex.EncodeToString(b)
}

func nowISO() string { return time.Now().UTC().Format(time.RFC3339Nano) }

func emptyToNil(v string) any {
	if v == "" {
		return nil
	}
	return v
}

func clip(sv string, max int) string {
	r := []rune(sv)
	if len(r) <= max {
		return sv
	}
	return string(r[:max])
}

func clampInt(v float64, min, maxV, def int) int {
	if v != v { // NaN
		return def
	}
	n := int(v + 0.5)
	if v < 0 {
		n = int(v - 0.5)
	}
	if n < min {
		return min
	}
	if n > maxV {
		return maxV
	}
	return n
}

// decodeBody 优先 UTF-8；不是合法 UTF-8 时按 GBK 再解一次。
// 原因：Windows 下 curl / 命令行拼中文，实际发出的是 GBK 字节。
func decodeBody(raw []byte) []byte {
	raw = bytes.TrimSpace(raw)
	if len(raw) == 0 || utf8.Valid(raw) {
		return raw
	}
	if out, _, err := transform.Bytes(simplifiedchinese.GBK.NewDecoder(), raw); err == nil {
		return bytes.TrimSpace(out)
	}
	return raw
}

func toFloat(v any) (float64, bool) {
	switch t := v.(type) {
	case float64:
		return t, true
	case json.Number:
		f, err := t.Float64()
		return f, err == nil
	case string:
		f, err := strconv.ParseFloat(strings.TrimSpace(t), 64)
		return f, err == nil
	}
	return 0, false
}

func toStr(v any) string {
	if v == nil {
		return ""
	}
	if s, okS := v.(string); okS {
		return s
	}
	return fmt.Sprint(v)
}

func apiKeyOf(r *http.Request) string {
	if v := strings.TrimSpace(r.Header.Get("X-API-Key")); v != "" {
		return v
	}
	auth := r.Header.Get("Authorization")
	if len(auth) > 7 && strings.EqualFold(auth[:7], "Bearer ") {
		return strings.TrimSpace(auth[7:])
	}
	return ""
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	h := w.Header()
	h.Set("Content-Type", "application/json; charset=utf-8")
	h.Set("Access-Control-Allow-Origin", "*")
	h.Set("Access-Control-Allow-Headers", "Content-Type, X-API-Key, Authorization")
	h.Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
	if code == http.StatusNoContent || code == http.StatusNotModified {
		w.WriteHeader(code)
		return
	}
	body, err := json.Marshal(v)
	if err != nil {
		w.WriteHeader(http.StatusInternalServerError)
		return
	}
	h.Set("Content-Length", strconv.Itoa(len(body)))
	w.WriteHeader(code)
	_, _ = w.Write(body)
}

func writeErr(w http.ResponseWriter, code int, msg string) {
	writeJSON(w, code, map[string]string{"error": msg})
}

func readBodyMap(r *http.Request) (map[string]any, error) {
	limited := http.MaxBytesReader(nil, r.Body, maxBodyBytes)
	raw, err := io.ReadAll(limited)
	if err != nil {
		return nil, fmt.Errorf("body too large")
	}
	raw = decodeBody(raw)
	if len(raw) == 0 {
		return map[string]any{}, nil
	}
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber()
	out := map[string]any{}
	if err := dec.Decode(&out); err != nil {
		return nil, fmt.Errorf("invalid json")
	}
	return out, nil
}

func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

// ---------------------------------------------------------------- 限流

func (s *Server) rateLimitedLocked(deviceID string) bool {
	now := time.Now()
	kept := s.rateBuckets[deviceID][:0]
	for _, t := range s.rateBuckets[deviceID] {
		if now.Sub(t) < time.Minute {
			kept = append(kept, t)
		}
	}
	kept = append(kept, now)
	s.rateBuckets[deviceID] = kept
	return len(kept) > rateLimitPerMin
}

func (s *Server) deviceCreateLimitedLocked(ip string) bool {
	now := time.Now()
	kept := s.createBuckets[ip][:0]
	for _, t := range s.createBuckets[ip] {
		if now.Sub(t) < time.Hour {
			kept = append(kept, t)
		}
	}
	kept = append(kept, now)
	s.createBuckets[ip] = kept
	return len(kept) > deviceCreatePerHour
}

// ---------------------------------------------------------------- 下发

func (s *Server) setStatusLocked(n *Notification, status, note string) {
	n.Status = status
	n.UpdatedAt = nowISO()
	if note != "" {
		n.Note = note
	}
}

// dispatchLocked 在线立即推送，离线入队等重连补发
func (s *Server) dispatchLocked(dev *Device, p Payload) (*Notification, bool) {
	n := &Notification{
		ID:        newID("ntf_"),
		DeviceID:  dev.ID,
		Status:    "created",
		Payload:   p,
		CreatedAt: time.Now(),
		UpdatedAt: nowISO(),
	}
	s.notifications[n.ID] = n

	if c := s.sockets[dev.ID]; c != nil {
		if err := c.sendJSON(notifyMessage(n)); err == nil {
			s.setStatusLocked(n, "sent", "")
			return n, true
		}
		c.close()
		delete(s.sockets, dev.ID)
	}

	q := append(s.queue[dev.ID], n.ID)
	for len(q) > s.policy.QueueMax {
		dropped := q[0]
		q = q[1:]
		// 被挤出队列的通知必须标记出来，否则通知端查状态会一直卡在 queued
		if dn := s.notifications[dropped]; dn != nil && dn.Status == "queued" {
			dn.Status = "expired"
			dn.UpdatedAt = nowISO()
			dn.Note = "设备离线太久，队列已满，本条被丢弃"
		}
	}
	s.queue[dev.ID] = q
	s.setStatusLocked(n, "queued", "设备离线，已排队等待重连")
	return n, false
}

func (s *Server) flushQueueLocked(dev *Device, c *client) {
	ids := s.queue[dev.ID]
	if len(ids) == 0 {
		return
	}
	delete(s.queue, dev.ID)
	sent := 0
	for _, id := range ids {
		n := s.notifications[id]
		if n == nil || n.Status != "queued" {
			continue
		}
		if time.Since(n.CreatedAt) > s.policy.QueueRetention {
			s.setStatusLocked(n, "expired", "排队超时未送达")
			continue
		}
		if err := c.sendJSON(notifyMessage(n)); err != nil {
			// 连接已经坏了，剩下的留在队列里等下次重连
			s.queue[dev.ID] = append(s.queue[dev.ID], id)
			continue
		}
		s.setStatusLocked(n, "sent", "离线补发")
		sent++
	}
	if sent > 0 {
		log.Printf("[queue] 向 %s 补发 %d 条离线通知", dev.ID, sent)
	}
}

func notifyMessage(n *Notification) map[string]any {
	return map[string]any{
		"type":         "notify",
		"id":           n.ID,
		"callerName":   n.Payload.CallerName,
		"callerNumber": n.Payload.CallerNumber,
		"text":         n.Payload.Text,
		"delaySeconds": n.Payload.DelaySeconds,
		"ringSeconds":  n.Payload.RingSeconds,
		"speakTimes":   n.Payload.SpeakTimes,
		"priority":     n.Payload.Priority,
		"receivedAt":   n.Payload.ReceivedAt,
	}
}

func (s *Server) publicDevice(d *Device) map[string]any {
	var lastSeen any
	if d.LastSeenAt != "" {
		lastSeen = d.LastSeenAt
	}
	return map[string]any{
		"deviceId":   d.ID,
		"name":       d.Name,
		"online":     d.Online,
		"lastSeenAt": lastSeen,
		"createdAt":  d.CreatedAt,
	}
}

// ---------------------------------------------------------------- HTTP 路由

func (s *Server) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", s.handleHealth)
	mux.HandleFunc("GET /api/health", s.handleHealth)
	mux.HandleFunc("GET /api/v1/diag", s.handleDiag)

	mux.HandleFunc("POST /api/v1/devices", s.handleCreateDevice)
	mux.HandleFunc("GET /api/v1/me", s.handleMe)
	mux.HandleFunc("GET /api/v1/devices/{id}", s.handleDeviceInfo)
	mux.HandleFunc("POST /api/v1/devices/{id}/key", s.handleRotateKey)
	mux.HandleFunc("POST /api/v1/devices/{id}/notify", s.handleNotifyByDevice)

	mux.HandleFunc("POST /api/v1/notify", s.handleNotifyByKey)
	mux.HandleFunc("GET /api/v1/notify/{id}", s.handleNotifyStatusByKey)
	mux.HandleFunc("GET /api/v1/devices/{id}/notifications/{nid}", s.handleNotifyStatusByDevice)

	mux.HandleFunc("GET /ws", s.handleWS)

	mux.HandleFunc("GET /", s.handleStatic)
	mux.HandleFunc("/", s.handleFallback)

	return loggingMW(mux)
}

// loggingMW 打印每一个进来的请求。排查"通知没到"时，这条日志能立刻区分：
//   - 根本没有日志 → 手机/通知端压根没连到这个服务（网络、端口、安全组、地址写错）
//   - 有 401 日志   → 连到了，但密钥不对（换了服务器没重新注册）
//   - 有 202 日志   → 服务端已投递，问题在手机端（App 没运行 / 通话账户没启用）
func loggingMW(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()

		// WebSocket 需要 Hijack，不能包一层 ResponseWriter
		if strings.HasPrefix(r.URL.Path, "/ws") {
			log.Printf("[http] %s %s ← %s（握手结果见下一条 ws 日志）", r.Method, r.URL.Path, clientIP(r))
			next.ServeHTTP(w, r)
			return
		}

		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(rec, r)
		log.Printf("[http] %s %s ← %s → %d (%s)",
			r.Method, r.URL.Path, clientIP(r), rec.status,
			time.Since(start).Round(time.Millisecond))
	})
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(code int) {
	r.status = code
	r.ResponseWriter.WriteHeader(code)
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	online := len(s.sockets)
	devices := len(s.devices)
	s.mu.Unlock()
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":        true,
		"devices":   devices,
		"online":    online,
		"uptimeSec": int(time.Since(startedAt).Seconds()),
	})
}

func (s *Server) handleCreateDevice(w http.ResponseWriter, r *http.Request) {
	ip := clientIP(r)

	s.mu.Lock()
	limited := s.deviceCreateLimitedLocked(ip)
	s.mu.Unlock()
	if limited {
		writeErr(w, http.StatusTooManyRequests, "注册过于频繁，请稍后再试")
		return
	}

	body, err := readBodyMap(r)
	if err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}

	name := clip(strings.TrimSpace(toStr(body["name"])), 40)
	if name == "" {
		name = "我的手机"
	}
	dev := &Device{
		ID:        newID("dev_"),
		Name:      name,
		APIKey:    newKey(),
		CreatedAt: nowISO(),
	}

	s.mu.Lock()
	s.devices[dev.ID] = dev
	s.deviceByKey[dev.APIKey] = dev
	s.saveLocked()
	s.mu.Unlock()

	log.Printf("[device] 新设备 %s (%s)", dev.ID, dev.Name)
	resp := s.publicDevice(dev)
	resp["apiKey"] = dev.APIKey
	writeJSON(w, http.StatusCreated, resp)
}

func (s *Server) handleMe(w http.ResponseWriter, r *http.Request) {
	key := apiKeyOf(r)
	s.mu.Lock()
	dev := s.deviceByKey[key]
	s.mu.Unlock()
	if dev == nil {
		writeErr(w, http.StatusUnauthorized, "密钥不正确")
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	writeJSON(w, http.StatusOK, s.publicDevice(dev))
}

// authDeviceByIDLocked 返回设备或错误码/错误信息
func (s *Server) authDeviceByIDLocked(r *http.Request, deviceID string) (*Device, int, string) {
	dev := s.devices[deviceID]
	if dev == nil {
		return nil, http.StatusNotFound, "设备不存在"
	}
	key := apiKeyOf(r)
	if key == "" || key != dev.APIKey {
		return nil, http.StatusUnauthorized, "密钥不正确"
	}
	return dev, 0, ""
}

func (s *Server) handleDeviceInfo(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	s.mu.Lock()
	dev, code, msg := s.authDeviceByIDLocked(r, id)
	if dev == nil {
		s.mu.Unlock()
		writeErr(w, code, msg)
		return
	}
	resp := s.publicDevice(dev)
	s.mu.Unlock()
	writeJSON(w, http.StatusOK, resp)
}

func (s *Server) handleRotateKey(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	s.mu.Lock()
	dev, code, msg := s.authDeviceByIDLocked(r, id)
	if dev == nil {
		s.mu.Unlock()
		writeErr(w, code, msg)
		return
	}
	delete(s.deviceByKey, dev.APIKey)
	dev.APIKey = newKey()
	s.deviceByKey[dev.APIKey] = dev
	c := s.sockets[dev.ID]
	s.saveLocked()
	s.mu.Unlock()

	if c != nil {
		c.closeWith(4001, "key rotated")
	}
	log.Printf("[device] %s 密钥已轮换", dev.ID)
	writeJSON(w, http.StatusOK, map[string]string{"deviceId": dev.ID, "apiKey": dev.APIKey})
}

func (s *Server) handleNotifyByKey(w http.ResponseWriter, r *http.Request) {
	key := apiKeyOf(r)
	s.mu.Lock()
	dev := s.deviceByKey[key]
	s.mu.Unlock()
	if dev == nil {
		writeErr(w, http.StatusUnauthorized, "密钥不正确")
		return
	}
	s.doNotify(w, r, dev)
}

func (s *Server) handleNotifyByDevice(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	s.mu.Lock()
	dev, code, msg := s.authDeviceByIDLocked(r, id)
	s.mu.Unlock()
	if dev == nil {
		writeErr(w, code, msg)
		return
	}
	s.doNotify(w, r, dev)
}

func (s *Server) doNotify(w http.ResponseWriter, r *http.Request, dev *Device) {
	body, err := readBodyMap(r)
	if err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}

	rawName := strings.TrimSpace(toStr(body["callerName"]))
	if rawName == "" {
		rawName = strings.TrimSpace(toStr(body["name"]))
	}
	rawNumber := strings.TrimSpace(toStr(body["callerNumber"]))
	if rawNumber == "" {
		rawNumber = strings.TrimSpace(toStr(body["number"]))
	}
	rawText := toStr(body["text"])
	if rawText == "" {
		rawText = toStr(body["content"])
	}

	callerName := clip(rawName, 40)
	callerNumber := clip(rawNumber, 32)
	text := clip(rawText, 2000)
	if text == "" && callerName == "" {
		writeErr(w, http.StatusBadRequest, "至少需要 callerName 或 text")
		return
	}
	if callerName == "" {
		callerName = "未知来电"
	}
	if callerNumber == "" {
		callerNumber = "10086"
	}

	delay := 0
	if v, okV := toFloat(body["delaySeconds"]); okV {
		delay = clampInt(v, 0, 3600, 0)
	}
	ring := 60
	if v, okV := toFloat(body["ringSeconds"]); okV {
		ring = clampInt(v, 10, 300, 60)
	}
	times := 1
	if v, okV := toFloat(body["speakTimes"]); okV {
		times = clampInt(v, 1, 5, 1)
	}
	priority := "high"
	if toStr(body["priority"]) == "low" {
		priority = "low"
	}

	p := Payload{
		CallerName:   callerName,
		CallerNumber: callerNumber,
		Text:         text,
		DelaySeconds: delay,
		RingSeconds:  ring,
		SpeakTimes:   times,
		Priority:     priority,
		ReceivedAt:   nowISO(),
	}

	s.mu.Lock()
	if s.rateLimitedLocked(dev.ID) {
		s.mu.Unlock()
		writeErr(w, http.StatusTooManyRequests,
			fmt.Sprintf("超过频率限制（%d 次/分钟）", rateLimitPerMin))
		return
	}
	n, delivered := s.dispatchLocked(dev, p)
	devID := dev.ID
	s.mu.Unlock()

	log.Printf("[notify] → %s %s | %s (%s)", devID, callerName, clip(text, 30), n.Status)
	writeJSON(w, http.StatusAccepted, map[string]any{
		"id":           n.ID,
		"status":       n.Status,
		"deviceOnline": delivered,
		"deviceId":     devID,
	})
}

func (s *Server) handleNotifyStatusByKey(w http.ResponseWriter, r *http.Request) {
	key := apiKeyOf(r)
	s.mu.Lock()
	dev := s.deviceByKey[key]
	if dev == nil {
		s.mu.Unlock()
		writeErr(w, http.StatusUnauthorized, "密钥不正确")
		return
	}
	n := s.notifications[r.PathValue("id")]
	if n == nil || n.DeviceID != dev.ID {
		s.mu.Unlock()
		writeErr(w, http.StatusNotFound, "通知不存在")
		return
	}
	view := n.view()
	s.mu.Unlock()
	writeJSON(w, http.StatusOK, view)
}

func (s *Server) handleNotifyStatusByDevice(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	s.mu.Lock()
	dev, code, msg := s.authDeviceByIDLocked(r, id)
	if dev == nil {
		s.mu.Unlock()
		writeErr(w, code, msg)
		return
	}
	n := s.notifications[r.PathValue("nid")]
	if n == nil || n.DeviceID != dev.ID {
		s.mu.Unlock()
		writeErr(w, http.StatusNotFound, "通知不存在")
		return
	}
	view := n.view()
	s.mu.Unlock()
	writeJSON(w, http.StatusOK, view)
}

func (s *Server) handleFallback(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodOptions {
		writeJSON(w, http.StatusNoContent, nil)
		return
	}
	writeErr(w, http.StatusNotFound, "not found")
}

// ---------------------------------------------------------------- 启动

var startedAt = time.Now()

func main() {
	port := envOr("PORT", "8787")
	host := envOr("HOST", "0.0.0.0")
	dataFile := envOr("DATA_FILE", "")

	flagPort := flag.String("port", port, "监听端口")
	flagHost := flag.String("host", host, "监听地址")
	flagData := flag.String("data", dataFile, "数据文件路径（默认可执行文件同目录 data.json）")
	flagOffline := flag.Duration("offline-retention", envDuration("OFFLINE_RETENTION", 0),
		"设备离线多久后从存储里删除；0 = 永不删除（默认，密钥/身份永久保留）")
	flagQueue := flag.Duration("queue-retention", envDuration("QUEUE_RETENTION", 10*time.Minute),
		"未送达通知（queued）保留多久，手机重连后补发")
	flagNotify := flag.Duration("notify-retention", envDuration("NOTIFY_RETENTION", 30*time.Minute),
		"终态通知在内存里保留多久，用于查状态")
	flagQueueMax := flag.Int("queue-max", envInt("QUEUE_MAX", 20), "单设备离线队列上限条数")
	flag.Parse()

	policy := Policy{
		OfflineRetention: *flagOffline,
		QueueRetention:   *flagQueue,
		NotifyRetention:  *flagNotify,
		QueueMax:         *flagQueueMax,
	}
	if policy.QueueMax < 1 {
		policy.QueueMax = 1
	}

	if *flagData == "" {
		exe, err := os.Executable()
		if err == nil {
			*flagData = filepath.Join(filepath.Dir(exe), "data.json")
		} else {
			*flagData = "data.json"
		}
	}

	srv := NewServer(*flagData, policy)
	go func() {
		// 5 秒扫一次：保留策略秒级生效，用户不用等半分钟
		t := time.NewTicker(5 * time.Second)
		defer t.Stop()
		for range t.C {
			srv.cleanupNotifications()
		}
	}()

	addr := net.JoinHostPort(*flagHost, *flagPort)
	httpSrv := &http.Server{
		Addr:              addr,
		Handler:           srv.routes(),
		ReadHeaderTimeout: 10 * time.Second,
	}

	banner(*flagHost, *flagPort, *flagData, len(srv.devices), srv.policy)

	go func() {
		if err := httpSrv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("监听失败：%v", err)
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	<-stop
	fmt.Println("\n正在关闭…")
	srv.closeAllSockets()
	srv.mu.Lock()
	srv.saveLocked()
	srv.mu.Unlock()
	_ = httpSrv.Close()
}

func banner(host, port, dataFile string, deviceCount int, policy Policy) {
	addrs := []string{"127.0.0.1"}
	if ifaces, err := net.Interfaces(); err == nil {
		for _, iface := range ifaces {
			if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
				continue
			}
			addrsOfIface, err := iface.Addrs()
			if err != nil {
				continue
			}
			for _, a := range addrsOfIface {
				if ipnet, okNet := a.(*net.IPNet); okNet && ipnet.IP.To4() != nil {
					addrs = append(addrs, ipnet.IP.String())
				}
			}
		}
	}
	out := os.Stdout
	fmt.Fprintln(out, "")
	fmt.Fprintln(out, "  远程来电通知 · 服务端已启动（Go 版）")
	fmt.Fprintln(out, "  ─────────────────────────────────────────")
	for _, ip := range addrs {
		fmt.Fprintln(out, "  访问地址   http://"+ip+":"+port)
	}
	fmt.Fprintln(out, "  WebSocket  ws://<上面任一地址>:"+port+"/ws")
	fmt.Fprintln(out, "  自检面板   http://<上面任一地址>:"+port+"/api/v1/diag")
	fmt.Fprintln(out, "  数据文件   "+dataFile)
	fmt.Fprintln(out, "  已注册设备 "+strconv.Itoa(deviceCount)+" 台")
	fmt.Fprintf(out, "  保留策略   离线设备 %s / 未送达 %s / 状态 %s / 队列上限 %d 条\n",
		policy.OfflineRetention, policy.QueueRetention, policy.NotifyRetention, policy.QueueMax)
	fmt.Fprintln(out, "")
	fmt.Fprintln(out, "  手机不响时，按顺序看这四件事：")
	fmt.Fprintln(out, "    1) 手机上打开 http://服务器IP:"+port+"/health 能否看到 JSON")
	fmt.Fprintln(out, "    2) 云服务器安全组 / 本机防火墙是否放行 TCP "+port)
	fmt.Fprintln(out, "    3) App 首页状态点是否绿色「在线」；换过服务器要重新点「注册设备」")
	fmt.Fprintln(out, "    4) 有 202 日志但手机没反应 → 问题在手机端通话账户 / 后台保活")
	fmt.Fprintln(out, "")
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func envDuration(key string, def time.Duration) time.Duration {
	if v := os.Getenv(key); v != "" {
		if d, err := time.ParseDuration(v); err == nil {
			return d
		}
		log.Printf("[config] %s=%q 解析失败，用默认 %s", key, v, def)
	}
	return def
}

func envInt(key string, def int) int {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return def
}
