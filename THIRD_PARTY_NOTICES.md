# 代码与第三方边界

主应用、自有路线算法、可选模块及测试代码使用根目录MIT许可证，保留原版权声明。Android framework由设备提供，android.jar仅用于编译，不打入主APK。OpenJDK和Android SDK构建工具由开发者另行安装，其许可证不因本项目MIT而改变。

主APK不集成高德SDK、Google Play Services、AndroidX或第三方网络库，不需要高德Key。开发阶段的高德诊断SDK、诊断APK、商业目标APK及重打包副本不随公开包分发。

`tools/pedometer-module/stubs`是编译用最小接口，不作为运行时打入模块；实际相关接口需相应框架提供。LSPatch为独立第三方项目，其GPL等许可须单独遵守，本包不含其二进制、不把其运行时标成MIT，也不分发目标改包APK。上游：https://github.com/JingMatrix/LSPatch 。

`examples/pku_yanyuan_5km`保留其OpenStreetMap来源和ODbL归属说明。地图路线数据不自动变为本项目MIT代码，现实通行情况需要另行确认。合成结构示例仅用于解析与编辑演示。

图标为项目已有图稿。目标应用、其商标、地图底图和设备系统不属于本项目，本公开包不包含这些商业安装包或地图截图。
