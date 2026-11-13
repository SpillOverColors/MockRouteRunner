# 更新GitHub与发布正式APK

仓库：https://github.com/SpillOverColors/MockRouteRunner

## 源码放在哪里

把 `01_公开源码` 里的内容放到仓库根目录，不要再套一层“01_公开源码”文件夹。README.md、CHANGELOG.md、BUILDING.md、LICENSE与app/应在根目录。没有变化的文件可以不重新上传，路径相同的文件会更新；目录移除或改名需要另行删除旧路径，上传不能自动处理删除。

本次建议使用配套“增量更新清单”核对修改和需要移除的旧文件。最稳妥的方式是先同步最新仓库，再把新源码内容覆盖到本地工作副本，查看git diff，只暂存确认的文件；不要把旧版遗留构建脚本与新版混用。

## 更新日志放在哪里

**完整日志放根目录 `CHANGELOG.md`。** README已经链接它。每次Release的说明再复制该版本对应的一节，不必把所有历史粘过去。当前正式版建议tag `v1.3.1`，标题 `MockRouteRunner 1.3.1 正式版`，不要把沙盒名称当作新版展示名称。

## APK放在哪里

进入仓库的Releases → Draft a new release/Create a new release，新建tag v1.3.1并指向本次源码提交。上传 `MockRouteRunner-1.3.1.apk` 和对应 `SHA256SUMS.txt`，填写1.3.1日志，核对后发布。正式版不要勾选pre-release。

APK不用放进源码文件列表。`源码.zip`可作为额外附件；GitHub也会自动生成tag对应的源码下载。整个自用交付包、原始截图、历史实测APK、签名私钥和Key不能上传。

用户重编译生成的不同证书APK不能覆盖官方发布签名版本。不要公开私钥来追求“可覆盖”；开发者保管签名，公开包提供证书指纹和校验值即可。

如果误上传了含Key的旧AMapDiag或构建目录，应停止分发并更换该Key；只删除当前文件不保证旧Git历史已消失。当前新公开包不包含这些文件。
