package com.AdbService;

import android.content.Context;
import androidx.annotation.Nullable;
import com.AdbService.AdbConnectionManager;
import com.AdbService.AdbToolSingleton;
import com.white.ITerminal.TerminalCommandParser;
import com.white.ITerminal.TerminalItem;
import com.white.QuoteUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class AdbServiceCommandHandler implements TerminalCommandParser.CommandHandler {
  private static final String SOURCE = "拓展工具";

  @Override
  public void executeAsync(
      Context appContext,
      Map<String, String> params,
      TerminalCommandParser.CommandCallback callback) {

    // 1. 初始化可动态修改的标签列表（修复Arrays.asList的add异常）
    List<String> tagList = new ArrayList<>();
    tagList.add("拓展工具");
    tagList.add("AdbService");

    // 2. 校验上下文非空（安卓端必备）
    if (appContext == null) {
      TerminalCommandParser.ParseResult result =
          new TerminalCommandParser.ParseResult(
              false, "错误：上下文为空，无法执行ADB操作", TerminalItem.STATUS_FAILED, tagList, SOURCE);
      callback.onSuccess(result);
      return;
    }

    // 3. 优先处理help参数
    boolean showHelp = "true".equalsIgnoreCase(params.getOrDefault("help", "false"));
    if (showHelp) {
      tagList.add("帮助");
      String helpMsg =
          "=== AdbService 命令帮助 ===\n"
              + "功能：ADB服务连接与命令执行\n\n"
              + "参数：\n"
              + "  -help                 - 显示此帮助信息\n"
              + "  -p                    - 配对设备\n"
              + "    -host [IP地址]       - 可选：指定配对IP（默认：127.0.0.1）\n"
              + "    -port [端口号]       - 可选：指定配对端口\n"
              + "    -code [配对码]       - 可选：指定配对码\n"
              + "  -c                    - 连接到ADB服务\n"
              + "    -host [IP地址]       - 可选：指定ADB服务IP（默认：127.0.0.1）\n"
              + "    -port [端口号]       - 可选：指定ADB服务端口（默认：5555）\n"
              + "  -e                    - 执行ADB命令（需配合-c参数先连接）\n"
              + "  -command [命令字符串]  - 要执行的ADB命令（配合-e参数使用）\n\n"
              + "  -exit                    - 结束ADB桥服务，回收资源\n"
              + "示例：\n"
              + "  adbService -help                          → 显示此帮助\n"
              + "  adbService -p -host 127.1.1.1 -port 1145 -code 114514  → 配对设备\n"
              + "  adbService -c                             → 连接默认地址(127.0.0.1:5555)\n"
              + "  adbService -c -host 127.1.1.1         → 连接指定IP，默认端口5555\n"
              + "  adbService -c -port 5556                  → 连接默认IP，指定端口5556\n"
              + "  adbService -c -host 127.1.1.1 -port 5556  → 连接指定IP和端口\n"
              + "  adbService -e -command \"shell ls\" → 连接并执行命令\n\n"
              + "注意：\n"
              + "  1. 执行命令前需要先连接ADB服务\n"
              + "  2. -command参数值需要引号包裹（尤其是包含空格的命令）\n"
              + "  3. -host和-port可选，顺序任意，但必须跟在-c之后\n"
              + "  4. 仅支持本地设备链接，不支持外部设备链接";

      callback.onSuccess(
          new TerminalCommandParser.ParseResult(
              true, helpMsg, TerminalItem.STATUS_SUCCESS, tagList, SOURCE));
      return;
    }

    // 4. 处理配对ADB服务参数（-p）
    boolean pair = "true".equalsIgnoreCase(params.getOrDefault("p", "false"));
    String pairHost = params.get("host");
    String pairPort = params.get("port");
    String pairCode = params.get("code");

    if (pair) {
      // ============ 参数校验开始 ============

      // 处理 host：为 null 或空字符串时使用默认 IP
      if (pairHost == null || pairHost.trim().isEmpty()) {
        pairHost = "127.0.0.1";
      }

      // 校验 IP 格式
      if (!isValidIpAddress(pairHost)) {
        callback.onSuccess(
            new TerminalCommandParser.ParseResult(
                false,
                "无效的 host 格式: " + pairHost + "，应为有效的IP地址",
                TerminalItem.STATUS_FAILED,
                tagList,
                SOURCE));
        return; // ← 添加 return，阻止后续执行
      }

      // 处理 port：必需参数，无默认值
      if (pairPort == null || pairPort.trim().isEmpty()) {
        callback.onSuccess(
            new TerminalCommandParser.ParseResult(
                false, "配对模式需要提供 port 参数（配对端口号）", TerminalItem.STATUS_FAILED, tagList, SOURCE));
        return; // ← 添加 return
      }

      int port;
      try {
        port = Integer.parseInt(pairPort.trim());
        if (port < 1 || port > 65535) {
          callback.onSuccess(
              new TerminalCommandParser.ParseResult(
                  false,
                  "无效的 port 范围: " + port + "，端口号应在 1-65535 之间",
                  TerminalItem.STATUS_FAILED,
                  tagList,
                  SOURCE));
          return; // ← 添加 return
        }
      } catch (NumberFormatException e) {
        callback.onSuccess(
            new TerminalCommandParser.ParseResult(
                false,
                "无效的 port 格式: " + pairPort + "，应为整数",
                TerminalItem.STATUS_FAILED,
                tagList,
                SOURCE));
        return; // ← 添加 return
      }

      // 处理 code：必需参数，无默认值
      if (pairCode == null || pairCode.trim().isEmpty()) {
        callback.onSuccess(
            new TerminalCommandParser.ParseResult(
                false, "配对模式需要提供 code 参数（配对码）", TerminalItem.STATUS_FAILED, tagList, SOURCE));
        return; // ← 添加 return
      }

      String finalCode = pairCode.trim();
      // 校验配对码格式（6位数字，根据实际情况调整正则）
      if (!finalCode.matches("^[0-9]{6}$")) {
        callback.onSuccess(
            new TerminalCommandParser.ParseResult(
                false,
                "无效的 code 格式: " + pairCode + "，配对码应为6位数字",
                TerminalItem.STATUS_FAILED,
                tagList,
                SOURCE));
        return; // ← 添加 return
      }

      // ============ 参数校验结束 ============

      tagList.add("配对");

      final String finalHost = pairHost;

      AdbToolSingleton.getInstance(appContext)
          .pairAdb(
              finalHost,
              port,
              finalCode,
              new AdbToolSingleton.AdbPairCallback() {
                @Override
                public void onPairSuccess() {
                  callback.onSuccess(
                      new TerminalCommandParser.ParseResult(
                          true,
                          "ADB配对成功: " + finalHost + ":" + port,
                          TerminalItem.STATUS_SUCCESS,
                          tagList,
                          SOURCE));
                }

                @Override
                public void onPairFailed(String errorMsg) {
                  callback.onSuccess(
                      new TerminalCommandParser.ParseResult(
                          false,
                          "ADB配对失败: " + errorMsg,
                          TerminalItem.STATUS_FAILED,
                          tagList,
                          SOURCE));
                }
              });
      return;
    }

    // 4. 处理连接ADB服务参数（-c）
    boolean connection = "true".equalsIgnoreCase(params.getOrDefault("c", "false"));
    if (connection) {
      tagList.add("链接");

      String host = params.get("host");
      String portStr = params.get("port");

      // 处理 host：为 null 或空字符串时使用默认 IP
      if (host == null || host.trim().isEmpty()) {
        host = "127.0.0.1";
      }

      // 处理 port：默认为 5555
      int port = 5555;
      if (portStr != null && !portStr.trim().isEmpty()) {
        try {
          port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
          // 如果解析失败，保持默认端口 5555
        }
      }

      AdbToolSingleton.getInstance(appContext)
          .connectAdb(
              host,
              port,
              new AdbToolSingleton.AdbConnectCallback() {
                @Override
                public void onConnectSuccess() {
                  callback.onSuccess(
                      new TerminalCommandParser.ParseResult(
                          true, "ADB连接成功", TerminalItem.STATUS_SUCCESS, tagList, SOURCE));
                }

                @Override
                public void onConnectFailed(String errorMsg) {
                  callback.onSuccess(
                      new TerminalCommandParser.ParseResult(
                          false,
                          "ADB连接失败：" + errorMsg,
                          TerminalItem.STATUS_FAILED,
                          tagList,
                          SOURCE));
                }
              });

      return; // 连接操作后直接返回，避免后续逻辑执行
    }

    // 5. 处理执行ADB命令参数（-e）
    boolean execute = "true".equalsIgnoreCase(params.getOrDefault("e", "false"));
    if (execute) {
      tagList.add("执行命令");
      // 解析命令参数（修复参数名错误：从"c"改为"command"）
      String command = QuoteUtils.unwrapStrict(params.get("command"));
      if (command == null || command.trim().isEmpty()) {
        callback.onSuccess(
            new TerminalCommandParser.ParseResult(
                false, "错误：-command 参数不存在 或 不得为空", TerminalItem.STATUS_FAILED, tagList, SOURCE));
        return;
      }

      AdbToolSingleton adbTool = AdbToolSingleton.getInstance(appContext);

      // 执行ADB命令
      adbTool.executeNonInteractive(command, new AdbToolSingleton.AdbNonInteractiveCallback() {
            @Override
            public void onCommandCompleted(String output) {
                // 命令执行完成：处理返回结果（包含正常输出和错误信息）
                callback.onSuccess(
                      new TerminalCommandParser.ParseResult(
                          true, output, TerminalItem.STATUS_SUCCESS, tagList, SOURCE));
            }

            @Override
            public void onCommandFailed(String errorMsg) {
                // 命令执行失败：处理错误信息
                callback.onSuccess(
                      new TerminalCommandParser.ParseResult(
                          false,
                          "ADB执行失败：" + errorMsg,
                          TerminalItem.STATUS_FAILED,
                          tagList,
                          SOURCE));
            }
        });
      
      return; // 执行命令后直接返回
    }
    
    // 5. 处理执行ADB命令参数（-exit）
    boolean exit = "true".equalsIgnoreCase(params.getOrDefault("exit", "false"));
    if(exit) {
      tagList.add("结束服务");
      AdbToolSingleton.getInstance(appContext).disconnectAdb();
      callback.onSuccess(
                  new TerminalCommandParser.ParseResult(
                      true, "已断开ADB服务", TerminalItem.STATUS_SUCCESS, tagList, SOURCE));
      return;
    }

    // 6. 无有效参数时，显示提示并引导查看帮助
    tagList.add("帮助");
    String helpPrompt = "未指定有效参数！\n\n请输入 'adbService -help' 查看可用参数和示例。";
    callback.onSuccess(
        new TerminalCommandParser.ParseResult(
            true, helpPrompt, TerminalItem.STATUS_SUCCESS, tagList, SOURCE));
  }

  @Override
  public TerminalCommandParser.ParseResult execute(Context appContext, Map<String, String> params)
      throws Exception {
    // 同步执行时返回友好提示（而非直接抛异常）
    List<String> tagList = new ArrayList<>();
    tagList.add("拓展工具");
    tagList.add("AdbService");
    tagList.add("执行异常");
    return new TerminalCommandParser.ParseResult(
        false,
        "AdbService命令仅支持异步执行，请调用 parseAndExecuteAsync 方法",
        TerminalItem.STATUS_FAILED,
        tagList,
        SOURCE);
  }

  // 辅助方法 IP校验
  private boolean isValidIpAddress(String ip) {
    if (ip == null || ip.isEmpty()) return false;
    String ipPattern =
        "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$";
    return ip.matches(ipPattern);
  }
}
