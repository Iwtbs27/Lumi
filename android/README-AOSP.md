# Lumi 系统版构建与部署

系统版适用于可使用设备平台签名、集成系统镜像的开发环境。普通 Android 手机请使用 [Lumi 标准版](../README.md#开始使用)。

## 构建系统版 APK

构建环境见 [项目首页](../README.md#本地构建)。在仓库的 `android` 目录执行：

```bash
./gradlew :app:assembleSystemDebug
```

APK 输出为 `app/build/outputs/apk/system/debug/app-system-debug.apk`。系统版使用 `ai.opencyvis` 包名，并声明 `android.uid.system` 共享用户，需要与目标系统匹配的平台签名。

## 平台签名

将目标系统的 `platform.pk8` 和 `platform.x509.pem` 放入本地 `android/platform-key/` 目录；也可以通过 `AOSP_ROOT` 指定包含平台密钥的 AOSP 源码树。签名文件不提交到仓库。

在已配置 Android SDK Build Tools 的环境中执行：

```bash
./scripts/sign-apk.sh \
  app/build/outputs/apk/system/debug/app-system-debug.apk \
  app/build/outputs/apk/system/debug/lumi-system-platform.apk
```

脚本会调用 `apksigner` 签名并验证安装包。

## 集成到系统

将签名后的 APK 作为特权应用纳入目标系统镜像，并安装 [权限白名单](privapp-permissions-opencyvis.xml)。安装位置和镜像构建步骤以目标设备的 AOSP 配置为准。

仓库保留了 `app/Android.bp` 和 `product.mk` 供设备集成参考；其中 `OpenCyvis` 是沿用的构建模块名，权限白名单中的包名也与应用保持一致。当前 Lumi 的完整依赖由 Gradle 管理，建议先通过 Gradle 构建 APK，再纳入设备镜像。

部署后可启动当前首页：

```bash
adb shell am start -n ai.opencyvis/.ui.ControlPanelActivity
```
