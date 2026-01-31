
AdbToolSingleton 调用文档

1. 类概述

`AdbToolSingleton` 是一个用于 Android 无线 ADB 调试的单例工具类，封装了配对、连接和非交互式命令执行功能。

主要特性：
- 单例模式管理，全局唯一实例
- 支持 Android 11+ 的无线配对功能
- 非交互式 Shell 命令执行（支持超时控制）
- 自动线程切换（回调默认在主线程执行）
- 内置嵌套引号安全检查

依赖要求：

```gradle
// 需要 libadb-android 库
implementation 'io.github.muntashirakon:libadb-android:x.x.x'
```

---

2. 核心接口定义

2.1 连接回调

```java
public interface AdbConnectCallback {
    void onConnectSuccess();                    // 连接成功
    void onConnectFailed(String errorMsg);      // 连接失败
}
```

2.2 配对回调

```java
public interface AdbPairCallback {
    void onPairSuccess();                       // 配对成功
    void onPairFailed(String errorMsg);         // 配对失败
}
```

2.3 命令执行回调

```java
public interface AdbNonInteractiveCallback {
    void onCommandCompleted(String output);     // 命令执行完成（含 stdout + stderr）
    void onCommandFailed(String errorMsg);      // 执行失败（超时/连接失效/参数错误）
}
```

---

3. API 方法详解

3.1 获取实例

```java
public static AdbToolSingleton getInstance(@NonNull Context context)
```

说明： 获取单例实例，首次调用时会初始化 ADB 连接管理器。

参数：
- `context` - 应用上下文（Activity 或 Application）

示例：

```java
AdbToolSingleton adbTool = AdbToolSingleton.getInstance(getApplicationContext());
```

---

3.2 无线配对（Android 11+）

```java
public void pairAdb(@NonNull String host, int port, 
                    @NonNull String code, @NonNull AdbPairCallback callback)
```

前置条件： 系统版本 ≥ Android 11 (API 30)

参数：
- `host` - 设备 IP 地址（如 "192.168.1.100"）
- `port` - 配对端口（通常为 42073-43000 范围）
- `code` - 6 位数字配对码
- `callback` - 配对结果回调

错误处理：
- 系统版本低于 Android 11 → 回调失败
- 配对码非 6 位数字 → 回调失败
- 端口非法（1-65535 范围外）→ 回调失败

示例：

```java
adbTool.pairAdb("192.168.1.100", 42073, "123456", new AdbToolSingleton.AdbPairCallback() {
    @Override
    public void onPairSuccess() {
        Log.d("ADB", "配对成功，可进行连接");
    }
    
    @Override
    public void onPairFailed(String errorMsg) {
        Log.e("ADB", "配对失败: " + errorMsg);
    }
});
```

---

3.3 建立连接

```java
public void connectAdb(@NonNull String host, int port, 
                       @NonNull AdbConnectCallback callback)
```

参数：
- `host` - 设备 IP 地址
- `port` - ADB 调试端口（通常为 5555 或配对后分配的端口）
- `callback` - 连接结果回调

示例：

```java
adbTool.connectAdb("192.168.1.100", 5555, new AdbToolSingleton.AdbConnectCallback() {
    @Override
    public void onConnectSuccess() {
        Log.d("ADB", "连接成功");
        // 此时可执行命令
    }
    
    @Override
    public void onConnectFailed(String errorMsg) {
        Log.e("ADB", "连接失败: " + errorMsg);
    }
});
```

---

3.4 执行非交互式命令（核心方法）

```java
public void executeNonInteractive(@NonNull String fullCommand, 
                                  @NonNull AdbNonInteractiveCallback callback)
```

功能： 执行一次性 Shell 命令，返回完整输出（不支持交互式命令如 `top`、`vim`）

参数：
- `fullCommand` - 完整 Shell 命令（如 `"ls -la /sdcard"`）
- `callback` - 执行结果回调

约束检查：
- 命令不能为空或仅空白字符
- 命令不能包含嵌套双引号（`""`）或未闭合引号

超时机制：
- 默认 10 秒超时（可通过 `setDefaultTimeoutSeconds` 修改）
- 超时后自动触发 `onCommandFailed`

示例：

```java
adbTool.executeNonInteractive("pm list packages -3", new AdbToolSingleton.AdbNonInteractiveCallback() {
    @Override
    public void onCommandCompleted(String output) {
        // output 包含所有第三方应用包名
        Log.d("ADB", "已安装应用:\n" + output);
    }
    
    @Override
    public void onCommandFailed(String errorMsg) {
        Log.e("ADB", "命令执行失败: " + errorMsg);
    }
});
```

---

3.5 超时配置

设置全局默认超时

```java
public void setDefaultTimeoutSeconds(int seconds)
```

说明： 设置命令执行的超时时间（秒），仅对后续执行的命令生效。

禁用超时（长命令专用）

```java
public void setDisableTimeout(boolean disable)
```

说明： 
- 设为 `true` 后，下一次命令执行将禁用超时监控
- 适用于 `logcat`、`top -n 1` 等可能长时间运行的命令
- 每次执行后自动重置为 `false`，需每次执行前重新设置

长命令示例：

```java
// 捕获 5 秒 logcat 日志（禁用超时避免被中断）
adbTool.setDisableTimeout(true);
adbTool.executeNonInteractive("logcat -d -t 5000", callback);
```

---

3.6 连接状态管理

检查连接活性

```java
public boolean isAdbConnectionAlive()
```

说明： 检测当前 ADB 连接是否可用（通过发送测试命令 `echo test` 验证）。

返回值：
- `true` - 连接正常
- `false` - 连接已断开或管理器未初始化

断开连接

```java
public void disconnectAdb()
```

说明： 异步断开 ADB 连接，释放资源。

销毁资源

```java
public void destroy()
```

说明： 
- 关闭所有线程池（立即关闭，不等待任务完成）
- 清空连接管理器引用
- 适用场景： 应用退出或不再需要 ADB 功能时调用

---

4. 完整使用示例

场景：连接设备并获取系统信息

```java
public class MainActivity extends AppCompatActivity {
    private AdbToolSingleton adbTool;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // 1. 初始化
        adbTool = AdbToolSingleton.getInstance(this);
        
        // 2. 设置超时（可选）
        adbTool.setDefaultTimeoutSeconds(15);
        
        // 3. 建立连接（假设已完成配对或端口已开放）
        connectAndExecute();
    }
    
    private void connectAndExecute() {
        adbTool.connectAdb("192.168.1.100", 5555, new AdbToolSingleton.AdbConnectCallback() {
            @Override
            public void onConnectSuccess() {
                executeCommands();
            }
            
            @Override
            public void onConnectFailed(String errorMsg) {
                Toast.makeText(MainActivity.this, "连接失败: " + errorMsg, Toast.LENGTH_LONG).show();
            }
        });
    }
    
    private void executeCommands() {
        // 获取设备型号
        adbTool.executeNonInteractive("getprop ro.product.model", new AdbToolSingleton.AdbNonInteractiveCallback() {
            @Override
            public void onCommandCompleted(String output) {
                TextView tv = findViewById(R.id.device_info);
                tv.setText("设备型号: " + output);
                
                // 链式调用：获取 Android 版本
                getAndroidVersion();
            }
            
            @Override
            public void onCommandFailed(String errorMsg) {
                Log.e("ADB", "获取型号失败: " + errorMsg);
            }
        });
    }
    
    private void getAndroidVersion() {
        adbTool.executeNonInteractive("getprop ro.build.version.release", new AdbToolSingleton.AdbNonInteractiveCallback() {
            @Override
            public void onCommandCompleted(String output) {
                Log.d("ADB", "Android 版本: " + output);
            }
            
            @Override
            public void onCommandFailed(String errorMsg) {
                Log.e("ADB", "获取版本失败: " + errorMsg);
            }
        });
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 清理资源
        adbTool.disconnectAdb();
        adbTool.destroy();
    }
}
```

---

5. 注意事项与最佳实践

5.1 线程安全
- 所有回调默认在主线程（UI 线程）执行，可直接更新界面
- 内部使用线程池处理网络操作，无需手动开线程

5.2 命令限制
- 不支持交互式命令： `top`（无参数）、`vim`、`su`（进入 root shell）等会阻塞
- 引号处理： 避免使用嵌套双引号，如需传递带空格的参数，使用单引号包裹：
  
```java
  // 错误：executeNonInteractive("echo \"hello world\"", callback);
  // 正确：
  adbTool.executeNonInteractive("echo 'hello world'", callback);
  ```

5.3 超时策略
- 网络命令建议保持默认 10 秒超时
- 文件传输、日志采集类命令执行前调用 `setDisableTimeout(true)`
- 禁用超时的命令需自行确保有结束条件（如 `logcat -d` 而非 `logcat`）

5.4 生命周期管理
- 建议在 `Application` 中初始化单例，在 `Activity` 中仅获取引用
- 应用退出前务必调用 `destroy()` 避免线程泄漏
- 临时断开使用 `disconnectAdb()`，完全销毁使用 `destroy()`

5.5 异常处理
- 所有网络操作已内部捕获异常，通过回调返回错误信息
- 连接断开后再执行命令会收到 `"请先连接 ADB 再执行命令"` 错误
- 建议在执行命令前调用 `isAdbConnectionAlive()` 预检连接状态

---

6. 错误码速查

错误信息	原因	解决方案	
`当前系统版本低于 Android 11`	调用了 `pairAdb` 但系统 < API 30	使用有线调试或升级系统	
`配对码格式错误`	配对码非 6 位数字	检查开发者选项中的 6 位配对码	
`请先连接 ADB 再执行命令`	未调用 `connectAdb` 或连接已断开	先建立连接	
`命令执行超时`	命令执行超过设定时间	检查网络或增加超时时间	
`包含非法嵌套/未闭合双引号`	命令字符串引号不匹配	修正命令语法，使用单引号替代	
`ADB 连接已失效`	中间设备断开或网络变化	重新调用 `connectAdb`	

---

如需进一步了解特定方法的实现细节或遇到连接问题，请检查设备是否正确开启无线调试模式，并确保防火墙未拦截 ADB 端口（默认 5555）。