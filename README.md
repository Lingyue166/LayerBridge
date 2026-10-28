# LayerBridge（lb）

Android 原生 Linux 包管理器。

- 解析并安装 `.deb` / LBPA 到独立前缀
- 直接执行 bionic/aarch64 二进制
- root 模块 + `lb` CLI + App

## 包格式

- `.deb`：Termux 官方、自编译、社区包。
- LBPA：lb 专属；能解析与安装；不是 deb、不是 zip、不是 Magisk/KernelSU 模块。

## 特性

- 签名校验；安装前冲突检查
- 安装事务：任意阶段中断，`lb remove` 清干净
- 卸载默认保留数据；`--force` / `purge` 全清
- 任意版本升降级
- 默认隐藏：前缀 `700`，命令只对 root 可见

## 安装

```bash
adb install -r app-debug.apk
adb push lb-module.zip /sdcard/Download/
su -c 'ksud module install /sdcard/Download/lb-module.zip'
# 重启
su -c 'lb doctor'
```

## 使用

```bash
su -c 'lb install --allow-unsigned /sdcard/pkg.deb'
su -c 'lb list'
su -c 'lb shell'
su -c 'hello'
```

## 构建

JDK 17+，Android SDK（platform 36）。

```bash
export GRADLE_USER_HOME="$PWD/.gradle-home"
export ANDROID_HOME="$HOME/Android/Sdk"

./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
bash scripts/build-module.sh
```

## 许可

[GPL-3.0](LICENSE)
