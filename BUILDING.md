# 从源码构建

主应用无需Gradle、地图SDK或API Key。使用Windows PowerShell、JDK17和Android SDK：build-tools36.0.0、platforms/android-37.0。

请先把源码放在纯英文路径，例如 `C:\Projects\MockRouteRunner`。本机aapt2无法可靠处理包含中文的资源目录；中文文件夹里的源码可阅读，但应复制到英文路径构建。不要把完整交付包或桌面父目录当作源码根目录。

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot'
$env:ANDROID_SDK_ROOT = "$env:LOCALAPPDATA\Android\Sdk"
Set-Location C:\Projects\MockRouteRunner
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

输出 `dist/MockRouteRunner-source-build.apk`。脚本先运行核心、断点和provider归属测试，再编译资源、Java和DEX，完成对齐、签名及签名验证。版本信息来自唯一的 `app/AndroidManifest.xml`。不会安装应用，不连接平板，不读取任何Key。

未指定 `MOCKROUTE_KEYSTORE` 时，本地生成开发签名。自编译证书不同，通常不能覆盖本项目发布的同包名APK；请在独立测试环境安装，不卸载现有应用来硬凑成功。正式发行仍沿用已有的持久化开发证书，本包不含签名私钥。要发布自己的版本，应自行管理签名与包名。

为了保留原实测逻辑，1.3.1没有修改运行核心。源码是依据当时工作树与实测DEX比对复原，旧基线307个方法的反汇编指令对照一致（忽略DEX偏移、索引和调试行号）；这不是历史APK字节级重建承诺。1.3.1仅在此基础上调整界面、帮助入口和版本资源。签名时间、ZIP元数据、SDK工具及编译差异均可改变APK哈希。

开发模块测试：
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\pedometer-module\test.ps1
```
模块40项、真实loopback协议9项；主构建核心104项、断点25项、ownership13项。测试通过不等于目标应用每项功能已实测。可选模块编译见其目录build.ps1，第三方LSPatch运行时不随本包分发；本包不提供或承诺商业目标APK改包结果。
