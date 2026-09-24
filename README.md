# ScreenLink

一台手机当**被控端**（共享屏幕、接受远程操作），另一台当**主控端**（看画面、远程点击）。用 IP 直连，密钥鉴权，Shizuku 注入。

## 功能

- **被控端**：MediaProjection 采集屏幕 → 缩放 → JPEG → TCP 推流
- **主控端**：实时画面 + 手指直接操作（点按 / 滑动），支持沉浸全屏
- **远程按键**：返回 / 主页 / 最近任务 / 唤醒 / 电源 / 音量 / 回车 / 截图
- **密钥鉴权**：可自己设置，也可以一键随机生成（16 位 ≈ 80 bit 熵）
- **画质可调**：宽度 360~1080、JPEG 质量、5~30 fps，主控端连接时自行下发
- **M3 UI**：Material 3 + 动态取色 + 深色模式

## 连接方式

被控端开一个 TCP 端口（默认 `27100`）等连接，主控端填 `IP + 端口 + 密钥` 主动连上去。

- 同一个 Wi-Fi：直接填被控端的局域网 IP（被控端页面会显示出来）
- 跨网络：需要自己做端口映射，或者用 VPN / 异地组网
- 主控端和被控端是同一个 App 的两个角色，装上同一个 APK 就能互控

## 安全设计

| 环节 | 做法 |
| --- | --- |
| 密钥 | 不参与网络传输，只在本地参与运算 |
| 鉴权 | 服务端发 nonce，客户端回 `HMAC-SHA256(key, "SLNK-AUTH-v1" \|\| serverNonce \|\| clientNonce)`，常数时间比较 |
| 会话密钥 | `HKDF-SHA256(key, salt = serverNonce \|\| clientNonce)` 派生 32 字节 |
| 传输 | 每条消息 AES-256-GCM，随机 12 字节 IV，AAD 绑定消息类型防篡改 |
| 防爆破 | 密钥错误时延迟 800ms 再回复，且一个连接只给一次机会 |

## 用到的能力

- **Shizuku**（必需）：以 shell 身份执行输入注入
  - 首选：反射拿 `IInputManager` binder 直接 `injectInputEvent`，实时跟手
  - 降级：Shizuku 进程执行 `input tap / swipe / keyevent`，只支持"抬手才生效"
  - 没授权也能用，但只能看画面，点不动
- **MediaProjection**（必需）：屏幕采集，系统会弹一次授权框
- **前台服务**：`mediaProjection` 类型，通知栏常驻，「停止」按钮可随时掐断

## 环境要求

- Android 8.0 (API 26) 及以上
- 被控端需要自行安装并启动 [Shizuku](https://shizuku.rikka.app/)
- 部分 ROM 会拦截隐藏 API，这种情况下自动降级到命令行注入

## 构建

推送到 GitHub 后由 Actions 自动编译，产物在：

```
https://github.com/hahahaoha/ScreenLink/releases/download/latest/ScreenLink-debug.apk
```

本地构建：

```bash
./gradlew assembleDebug
```

## 协议

```
帧格式： MAGIC("SLNK",4) | type(1) | length(4) | payload
握手（明文）:
  S -> C  HELLO        ver(1) + nonce(16) + requireKey(1)
  C -> S  AUTH         clientNonce(16) + hmac(32)
  S -> C  AUTH_RESULT  ok(1) + reasonLen(2) + reason
会话（AES-256-GCM）:
  S -> C  FRAME        ts(8) + w(4) + h(4) + jpeg
  C -> S  TOUCH        action(1) + x(4 float) + y(4 float)   # 0~1 归一化坐标
  C -> S  KEY          keyCode(4)
  C -> S  PING / PONG  延迟测量，3 秒一次
  S -> C  CONFIG       realW(4) + realH(4) + capW(4) + capH(4)
  C -> S  SET_QUALITY  width(4) + quality(4) + fps(4)
  both    BYE          主动断开
```

归一化坐标的好处：主控端不管怎么缩放显示，被控端都能换算回真实像素；被控端旋转屏幕时会重新下发 `CONFIG`，坐标照样对得上。

## 已知限制

- 单客户端：一个被控端同时只接受一个主控端
- JPEG 逐帧传，没上 H.264，高帧率 + 高画质时流量偏大
- 只做了单指触摸，没做双指缩放
- 没做音频、剪贴板同步、文件传输
- 没有 NAT 穿透，跨网络需要端口映射或 VPN
