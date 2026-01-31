package com.AdbService;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.AdbStream;

/** ADB 工具单例类：非交互式 shell:完整命令 执行，修复 Lambda 变量 final 问题 */
public class AdbToolSingleton {
    // 单例实例
    private static volatile AdbToolSingleton INSTANCE;
    // 主线程 Handler：用于将回调切换到主线程（方便更新 UI）
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // ADB 连接管理器
    private AbsAdbConnectionManager adbConnectionManager;
    // 连接状态标记（volatile 保证多线程可见性），默认未连接
    private volatile boolean isAdbConnected = false;
    // 固定线程池：支持并行处理连接/配对/命令执行
    private final ExecutorService executorService = Executors.newFixedThreadPool(3);
    // 超时监控定时线程池：全局复用
    private final ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
    // 超时任务句柄：用于控制当前命令的超时监控
    private ScheduledFuture<?> timeoutFuture;

    // 全局超时配置（默认10秒，可自定义）
    private int defaultTimeoutSeconds = 10;
    // 标记是否禁用超时（用于长命令如 logcat/top，非交互式场景慎用）
    private final AtomicBoolean disableTimeout = new AtomicBoolean(false);

    // ============== 定义回调接口 ==============
    public interface AdbConnectCallback {
        void onConnectSuccess();
        void onConnectFailed(String errorMsg);
    }

    public interface AdbPairCallback {
        void onPairSuccess();
        void onPairFailed(String errorMsg);
    }

    /**
     * 非交互式命令执行回调（返回命令输出：包含正常结果+错误信息）
     */
    public interface AdbNonInteractiveCallback {
        /**
         * 命令执行完成（无论是否有错误，只要流正常读取完成）
         * @param output 命令输出（包含 stdout 正常结果 和 stderr 错误信息）
         */
        void onCommandCompleted(String output);

        /**
         * 命令执行失败（超时、连接失效、参数非法等）
         * @param errorMsg 失败原因
         */
        void onCommandFailed(String errorMsg);
    }

    // ============== 单例构造 ==============
    private AdbToolSingleton(@NonNull Context context) {
        try {
            adbConnectionManager = AdbConnectionManager.getInstance(context.getApplicationContext());
        } catch (Exception e) {
            e.printStackTrace();
            isAdbConnected = false;
        }
    }

    public static AdbToolSingleton getInstance(@NonNull Context context) {
        if (INSTANCE == null) {
            synchronized (AdbToolSingleton.class) {
                if (INSTANCE == null) {
                    INSTANCE = new AdbToolSingleton(context);
                }
            }
        }
        return INSTANCE;
    }

    // ============== 超时配置接口 ==============
    public void setDefaultTimeoutSeconds(int seconds) {
        if (seconds > 0) {
            this.defaultTimeoutSeconds = seconds;
        }
    }

    public void setDisableTimeout(boolean disable) {
        this.disableTimeout.set(disable);
    }

    // ============== ADB 配对 ==============
    public void pairAdb(@NonNull String host, int port, @NonNull String code, @NonNull AdbPairCallback callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            postCallbackToMainThread(() -> callback.onPairFailed("当前系统版本低于 Android 11，不支持 ADB 配对功能"));
            return;
        }
        if (adbConnectionManager == null) {
            postCallbackToMainThread(() -> callback.onPairFailed("ADB 连接管理器初始化失败"));
            return;
        }
        if (code == null || !code.matches("\\d{6}")) {
            postCallbackToMainThread(() -> callback.onPairFailed("配对码格式错误，必须是 6 位数字"));
            return;
        }
        if (port <= 0 || port > 65535) {
            postCallbackToMainThread(() -> callback.onPairFailed("配对端口格式错误，必须是 1-65535 之间的整数"));
            return;
        }

        executorService.execute(() -> {
            try {
                boolean pairResult = adbConnectionManager.pair(host, port, code);
                if (pairResult) {
                    postCallbackToMainThread(callback::onPairSuccess);
                } else {
                    postCallbackToMainThread(() -> callback.onPairFailed("配对失败：请检查 IP、配对端口和配对码是否正确"));
                }
            } catch (Exception e) {
                e.printStackTrace();
                String errorMsg = "配对异常：" + (e.getMessage() == null ? "未知错误" : e.getMessage());
                postCallbackToMainThread(() -> callback.onPairFailed(errorMsg));
            }
        });
    }

    // ============== ADB 连接 ==============
    public void connectAdb(@NonNull String host, int port, @NonNull AdbConnectCallback callback) {
        if (adbConnectionManager == null) {
            isAdbConnected = false;
            postCallbackToMainThread(() -> callback.onConnectFailed("ADB 连接管理器初始化失败"));
            return;
        }

        executorService.execute(() -> {
            try {
                boolean connectResult = adbConnectionManager.connect(host, port);
                if (connectResult) {
                    isAdbConnected = true;
                    postCallbackToMainThread(callback::onConnectSuccess);
                } else {
                    isAdbConnected = false;
                    postCallbackToMainThread(() -> callback.onConnectFailed("连接拒绝：请检查设备是否开启无线调试，IP 和端口是否正确"));
                }
            } catch (Exception e) {
                e.printStackTrace();
                isAdbConnected = false;
                postCallbackToMainThread(() -> callback.onConnectFailed("连接异常：" + (e.getMessage() == null ? "未知错误" : e.getMessage())));
            }
        });
    }

    // ========== 核心：非交互式命令执行（修复 Lambda 变量 final 问题） ==========
    public void executeNonInteractive(@NonNull String fullCommand, @NonNull AdbNonInteractiveCallback callback) {
        // 前置校验
        if (adbConnectionManager == null) {
            postCallbackToMainThread(() -> callback.onCommandFailed("ADB 连接管理器初始化失败"));
            return;
        }
        if (!isAdbConnected) {
            postCallbackToMainThread(() -> callback.onCommandFailed("请先连接 ADB 再执行命令"));
            return;
        }

        String trimmedFullCommand = fullCommand.trim();
        if (trimmedFullCommand.isEmpty()) {
            postCallbackToMainThread(() -> callback.onCommandFailed("完整命令不能为空"));
            return;
        }

        if (isContainNestedQuotes(trimmedFullCommand)) {
            postCallbackToMainThread(() -> callback.onCommandFailed("命令 [" + trimmedFullCommand + "] 包含非法嵌套/未闭合双引号，请避免"));
            return;
        }

        String fullServiceName = "shell:" + trimmedFullCommand;
        final boolean isTimeoutDisabled = disableTimeout.get();
        disableTimeout.set(false);

        executorService.execute(() -> {
            AdbStream commandStream = null;
            // 修复点1：将 outputReader 声明为 final（赋值后不再修改，符合 Lambda 引用要求）
            final BufferedReader[] outputReaderHolder = new BufferedReader[1]; // 用数组包装，实现「有效 final」
            AtomicBoolean isTimeout = new AtomicBoolean(false);
            // 修复点2：outputBuffer 用 AtomicReference 包装，保证多线程安全（避免 Lambda 中引用非 final 变量）
            AtomicReference<StringBuilder> outputBufferRef = new AtomicReference<>(new StringBuilder());
            AtomicBoolean isStreamReadCompleted = new AtomicBoolean(false);

            try {
                if (isAdbConnected && !isAdbConnectionAlive()) {
                    isAdbConnected = false;
                    postCallbackToMainThread(() -> callback.onCommandFailed("ADB 连接已失效，请重新连接"));
                    return;
                }

                commandStream = adbConnectionManager.openStream(fullServiceName);
                if (commandStream == null || commandStream.isClosed()) {
                    postCallbackToMainThread(() -> callback.onCommandFailed("无法打开非交互式 ADB 命令流"));
                    return;
                }

                InputStream inputStream = commandStream.openInputStream();
                // 给包装数组赋值（数组本身是 final，符合要求）
                outputReaderHolder[0] = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.ISO_8859_1));
                final BufferedReader outputReader = outputReaderHolder[0]; // 提取为 final 变量，供 Lambda 使用

                if (!isTimeoutDisabled) {
                    resetTimeoutMonitor(() -> {
                        if (isStreamReadCompleted.get()) return;
                        isTimeout.set(true);
                        isStreamReadCompleted.set(true);
                        postCallbackToMainThread(() -> callback.onCommandFailed(
                                String.format("命令执行超时（%d秒无输出），请检查命令或网络状态", defaultTimeoutSeconds)));
                    }, defaultTimeoutSeconds);
                }

                // 修复点3：Lambda 中引用的 outputReader 是 final 变量，编译通过
                new Thread(() -> {
                    try {
                        String line;
                        StringBuilder outputBuffer = outputBufferRef.get();
                        while ((line = outputReader.readLine()) != null && !isStreamReadCompleted.get()) {
                            outputBuffer.append(line).append("\n");
                        }
                    } catch (IOException e) {
                        if (!isStreamReadCompleted.get() && !e.getMessage().contains("stream closed")) {
                            e.printStackTrace();
                        }
                    }
                }, "ADB_Command_Output_Reader").start();

                while (!commandStream.isClosed() && !isStreamReadCompleted.get() && !isTimeout.get()) {
                    TimeUnit.MILLISECONDS.sleep(100);
                }

                isStreamReadCompleted.set(true);
                cancelTimeoutMonitor();

                if (isTimeout.get()) return;

                final String finalOutput = outputBufferRef.get().toString().trim();
                postCallbackToMainThread(() -> callback.onCommandCompleted(finalOutput));

            } catch (Exception e) {
                cancelTimeoutMonitor();
                isStreamReadCompleted.set(true);
                e.printStackTrace();
                String errorMsg = "命令执行异常：" + (e.getMessage() == null ? "未知错误" : e.getMessage());
                postCallbackToMainThread(() -> callback.onCommandFailed(errorMsg));
            } finally {
                cancelTimeoutMonitor();
                isStreamReadCompleted.set(true);
                try {
                    // 关闭包装数组中的 BufferedReader
                    if (outputReaderHolder[0] != null) outputReaderHolder[0].close();
                    if (commandStream != null && !commandStream.isClosed()) commandStream.close();
                } catch (IOException e) {
                    if (!e.getMessage().contains("stream closed")) {
                        e.printStackTrace();
                    }
                }
            }
        });
    }

    /**
     * 辅助校验：判断命令是否包含嵌套/未闭合双引号
     */
    private boolean isContainNestedQuotes(@NonNull String command) {
        int quoteCount = 0;
        for (char c : command.toCharArray()) {
            if (c == '"') {
                quoteCount++;
            }
        }

        if (quoteCount % 2 != 0) {
            return true;
        }

        return command.contains("\"\"");
    }

    // ============== 工具方法（保留健壮性） ==============
    public boolean isAdbConnectionAlive() {
        if (adbConnectionManager == null) return false;
        try {
            if (adbConnectionManager.isConnected()) {
                return true;
            }
            AdbStream testStream = adbConnectionManager.openStream("shell:echo test");
            boolean isAlive = testStream != null && !testStream.isClosed();
            if (testStream != null) {
                try {
                    testStream.close();
                } catch (IOException e) {
                    if (!e.getMessage().contains("stream closed")) {
                        e.printStackTrace();
                    }
                }
            }
            return isAlive;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private void resetTimeoutMonitor(Runnable timeoutAction, int seconds) {
        if (disableTimeout.get()) {
            return;
        }
        cancelTimeoutMonitor();
        timeoutFuture = timeoutExecutor.schedule(timeoutAction, seconds, TimeUnit.SECONDS);
    }

    private void cancelTimeoutMonitor() {
        if (timeoutFuture != null && !timeoutFuture.isCancelled()) {
            timeoutFuture.cancel(false);
        }
    }

    /**
     * 断开 ADB 连接
     */
    public void disconnectAdb() {
        if (adbConnectionManager != null && isAdbConnected) {
            executorService.execute(() -> {
                try {
                    adbConnectionManager.disconnect();
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    isAdbConnected = false;
                }
            });
        }
    }

    /**
     * 销毁资源
     */
    public void destroy() {
        if (!executorService.isShutdown()) {
            executorService.shutdownNow();
        }
        if (!timeoutExecutor.isShutdown()) {
            timeoutExecutor.shutdownNow();
        }
        adbConnectionManager = null;
        isAdbConnected = false;
    }

    private void postCallbackToMainThread(@NonNull Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            mainHandler.post(runnable);
        }
    }
}