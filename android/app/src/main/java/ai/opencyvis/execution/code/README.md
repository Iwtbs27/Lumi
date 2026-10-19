# Android 代码执行底座

采用 Chaquopy 17.1.0、CPython 3.11 和 openpyxl 3.1.5。Python 3.11 同时支持 armeabi-v7a、arm64-v8a、x86、x86_64；3.12 及更新版本只支持 64 位，因此本次明确选择 3.11，保留现有设备覆盖。当前 AGP 8.7.3 与 minSdk 30 落在官方支持范围内，实际构建和运行仍以测试结果为准。

构建机需要 Python 3.11。非标准安装位置通过 `-Pchaquopy.buildPython=<解释器绝对路径>` 指定，不将机器路径写入仓库；未指定时采用插件的标准解释器发现规则。仅打包标准库、openpyxl 及其依赖，首次构建需要下载运行时，APK 也会增加体积。

程序定义同步 `main(ctx, params)`，返回可序列化为 JSON 的值。`ctx.workspace` 为任务工作目录，`ctx.path(name)` 为工作区路径，`ctx.call(capability, arguments)` 调用 Kotlin 宿主。文件输入由宿主先复制到工作区；产物归档也由宿主管理。openpyxl 支持普通数据表读写，不承诺公式重算或任意 Office 格式保真。

每次执行启动一个非导出的 `:code_worker` 服务进程。执行完成、失败、取消、超时后，都停止该 execution 的宿主调用并结束 worker；Binder 死亡、进程退出和已开始的宿主调用退出全部确认后，才释放进程级唯一代码槽。下一次执行重建解释器。这个进程用于结束计算和恢复故障，权限仍与应用相同；已经发生的文件、GUI、API 效果不回滚。

stdout、stderr、JSON 结果写入工作区 `.executions/<executionId>/`。64 KiB 以内的结果内联返回，更大的结果只返回文件引用；日志按字符分页读取。原始日志不经 Binder 传输。

应用入口需在 `App.onCreate` 的 `super.onCreate()` 后跳过 `:code_worker` 的主进程初始化；Manifest 注册 `CodeWorkerService`，指定 `android:exported="false"` 与 `android:process=":code_worker"`。

官方依据：[Chaquopy 构建与 ABI](https://chaquo.com/chaquopy/doc/current/android.html)、[Chaquopy Java 桥](https://chaquo.com/chaquopy/doc/current/java.html)、[解释器生命周期](https://chaquo.com/chaquopy/doc/current/java/com/chaquo/python/Python.html)、[Android 进程结束 API](https://developer.android.com/reference/android/os/Process#killProcess(int))。
