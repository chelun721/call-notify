//go:build windows

package main

import "syscall"

// Windows 控制台默认是 GBK 代码页，Go 直接输出 UTF-8 会变成乱码。
// 启动时把输入/输出代码页切成 65001（UTF-8）。
func init() {
	kernel32 := syscall.NewLazyDLL("kernel32.dll")
	if p := kernel32.NewProc("SetConsoleOutputCP"); p.Find() == nil {
		_, _, _ = p.Call(65001)
	}
	if p := kernel32.NewProc("SetConsoleCP"); p.Find() == nil {
		_, _, _ = p.Call(65001)
	}
}
