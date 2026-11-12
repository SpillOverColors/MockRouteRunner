# 从源码构建

推荐 Windows PowerShell；准备 JDK 17（javac/java/keytool）、Android SDK build-tools 36.0.0、平台 android-37.0。
设置 JAVA_HOME 为 JDK 根目录，ANDROID_SDK_ROOT 为 SDK 根目录。
在源码根目录运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build-release.ps1
```

该脚本运行核心与断点测试，构建主 App，输出 dist/release/apk/MockRouteRunner-1.2.1-debug.apk。
初次构建会生成本地 .debug-signing/debug.keystore；不含作者私钥，因此签名与作者 APK 不同。
再次构建保留自己的密钥，不能将它加入公共仓库。默认调试口令不是生产密钥方案。
构建包含当前图标资源，不必重新运行图标生成脚本。若主动重新生成，另需 Python 和 Pillow。

## 模块、StepDemo 和改包

模块源码位于 tools/pedometer-module，StepDemo 位于 tools/stepdemo，均附 build.ps1。
这些辅助脚本和 patch_tool 中仍有原开发机的默认绝对路径，不能宣称跨机一键可用。
在另一台机器上，先检查各脚本开头的 SDK/JDK/Java 路径并配置到实际安装位置；再运行对应脚本。
模块和 StepDemo 构建可传 -Out、-Keystore；先运行主构建生成自己的调试密钥。
patch_tool 支持 -Apk/-OutDir/-Java/-Lspatch/-Module/-Keystore 等参数，详见教程。
从官方对应版本取回 LSPatch JAR并核对哈希后，才可运行改包流程；运行该JAR需要 Java21+。
本源码包不附作者构建机、JDK、SDK或第三方JAR。输入目标APK也需自行提供。
未知消费者、分包和目标业务兼容性不属于一键保证范围。

## 辅助脚本的状态

build.ps1、build-p2.ps1、build-acceptance.ps1 保留为开发历史入口，不推荐用来替代本次 build-release.ps1。
test.ps1 和 patch_tool_tests.ps1 也需先检查本机路径配置。不要在唯一一份已发布文件夹中运行会清理 dist 的构建命令。
