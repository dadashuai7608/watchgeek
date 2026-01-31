
README.md

# AdbToolSingleton

Android 无线 ADB 调试工具类库（单例模式）

[![License](https://img.shields.io/badge/License-LGPL--3.0%2FGPL--3.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)

AdbToolSingleton 是一个用于 Android 无线 ADB 调试的单例工具类，封装了配对、连接和非交互式命令执行功能。

**核心特性：**
- 🔌 支持 Android 11+ 无线配对功能
- 🚀 非交互式 Shell 命令执行（支持超时控制）
- 🧵 自动线程切换（回调默认在主线程执行）
- 🔒 内置嵌套引号安全检查
- ⚡ 单例模式管理，全局唯一实例

---

## 📦 快速开始

### 1. 添加依赖

```gradle
dependencies {
    // 必需：libadb-android 库
    implementation 'io.github.muntashirakon:libadb-android:2.0.0'
}
```

2. 基础使用示例

```java
public class MainActivity extends AppCompatActivity {
    private AdbToolSingleton adbTool;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 初始化（单例）
        adbTool = AdbToolSingleton.getInstance(this);
        
        // 连接设备（假设已配对）
        adbTool.connectAdb("192.168.1.100", 5555, new AdbConnectCallback() {
            @Override
            public void onConnectSuccess() {
                // 执行命令
                executeCommand();
            }
            
            @Override
            public void onConnectFailed(String errorMsg) {
                Log.e("ADB", "连接失败: " + errorMsg);
            }
        });
    }
    
    private void executeCommand() {
        // 获取已安装应用列表
        adbTool.executeNonInteractive("pm list packages -3", 
            new AdbNonInteractiveCallback() {
                @Override
                public void onCommandCompleted(String output) {
                    // 在主线程回调，可直接更新UI
                    textView.setText(output);
                }
                
                @Override
                public void onCommandFailed(String errorMsg) {
                    Toast.makeText(MainActivity.this, errorMsg, Toast.LENGTH_SHORT).show();
                }
            });
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        adbTool.disconnectAdb();
        adbTool.destroy();
    }
}
```

3. 无线配对（Android 11+）

```java
// 首次连接需要配对
adbTool.pairAdb("192.168.1.100", 42073, "123456", new AdbPairCallback() {
    @Override
    public void onPairSuccess() {
        // 配对成功后进行连接
        adbTool.connectAdb("192.168.1.100", 5555, connectCallback);
    }
    
    @Override
    public void onPairFailed(String errorMsg) {
        Log.e("ADB", "配对失败: " + errorMsg);
    }
});
```

---

📄 详细文档

完整 API 文档、高级用法和最佳实践请参阅：[调用文档全文](docs/USAGE.md)

关键接口：
- `getInstance(Context)` - 获取单例
- `pairAdb(host, port, code, callback)` - 无线配对
- `connectAdb(host, port, callback)` - 建立连接
- `executeNonInteractive(command, callback)` - 执行命令
- `setDefaultTimeoutSeconds(seconds)` - 设置超时
- `isAdbConnectionAlive()` - 检查连接状态

---

⚖️ 许可证与协议 (License)

双许可选择 (Dual License)

本库采用 LGPL-3.0 或 GPL-3.0 双许可（默认 LGPL-3.0）：

- LGPL-3.0：允许集成到闭源应用，但修改本库必须开源
- GPL-3.0：强传染性开源，集成后整体需开源

🚫 非商业条款 (Non-Commercial)

严禁将本库作为商品销售：
- ❌ 禁止销售源代码或编译产物（AAR/JAR）
- ❌ 禁止销售本库的使用权或商业授权
- ❌ 禁止将本库简单包装后作为独立产品售卖

允许的商业行为：
- ✅ 将本库集成到商业应用作为功能组件（应用核心价值不能仅为本库）
- ✅ 提供基于本库的定制开发、技术支持服务（收取服务费，非库本身费用）
- ✅ 接受自愿捐赠支持开发

👥 版权归属与特权

著作权人：飞上蓝天的飞友 (Fsy) & 表极客 (WatchGeek) 共同所有

作者特权（不适用于第三方）：
- 可在任何项目（开源/闭源）中无限制使用
- 可闭源修改，无需遵守 LGPL/GPL 开源义务
- 可将本库集成到闭源商业软件

限制：即使作为作者，也严禁单独销售本库。

第三方义务

如果您不是两位著作权人，使用本库时必须：
1. 开源义务：修改本库代码后必须使用相同协议（LGPL/GPL）开源
2. 非商业继承：保留"禁止销售"条款，不得移除或削弱
3. 归属声明：在应用关于页面声明使用了本库并保留版权声明
4. 协议保持：不得改为 MIT/Apache 等允许商业贩卖的协议

完整协议文本请参阅 [LICENSE](LICENSE)。

---

🔗 第三方依赖

本库使用了 [libadb-android](https://github.com/MuntashirAkon/libadb-android)（Copyright 2021 © Muntashir Al-Islam），采用 GPL-3.0-or-later 或 Apache-2.0 双许可。

---

🤝 贡献

欢迎提交 Issue 和 Pull Request。贡献的代码将视为与本项目采用相同许可协议。

注意：提交 PR 即表示您同意将代码版权授予飞上蓝天的飞友(Fsy)和表极客(WatchGeek)共同所有，以确保协议执行的一致性。

```

---

## 代码文件头示例

请将此头部添加到每个 Java 源文件顶部：

```java
/*
 * AdbToolSingleton - Android ADB 工具单例类
 * 
 * 版权所有 (Copyright) 2024-2025:
 *   - 飞上蓝天的飞友 (Fsy)
 *   - 表极客 (WatchGeek)
 * 
 * 许可协议 (License):
 *   本库采用 LGPL-3.0 或 GPL-3.0 双许可（默认 LGPL-3.0）。
 *   
 *   非商业条款：
 *   严禁销售本库源代码或编译产物（AAR/JAR等）。
 *   允许集成到商业应用，但应用核心价值不能仅为本库。
 *   
 *   特殊权利声明：
 *   飞上蓝天的飞友(Fsy)和表极客(WatchGeek)作为共同著作权人，
 *   保留无限制使用、闭源修改等特权，但均受"禁止销售"条款约束。
 * 
 *   第三方义务：
 *   修改本库必须开源并遵守相同协议，保留本声明。
 * 
 * 第三方依赖:
 *   - libadb-android (GPL-3.0-or-later / Apache-2.0)
 *     Copyright 2021 © Muntashir Al-Islam
 * 
 * 完整协议文本参见项目根目录 LICENSE 和 THIRD-PARTY-NOTICES.md 文件。
 * 
 * 警告：移除或修改本声明中的版权归属和许可条款将构成侵权。
 */
package com.adbservice;

import android.content.Context;
// ... 其他 import
```

---
