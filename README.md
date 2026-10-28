# LayerBridge（lb）

Android 原生 Linux 包管理器。

- 解析并安装 `.deb` / LBPA 到独立前缀
- 直接执行 bionic/aarch64 二进制
- root 模块 + `lb` CLI + App

## 包格式

- `.deb`：Termux 官方、自编译、社区包。
- LBPA：自有格式。

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

## 打包

```bash
# deb -> LBPA（保留脚本、Conffiles、依赖关系）
scripts/lb-pack.py from-deb pkg.deb -o pkg.lbpa

# 前缀目录 -> LBPA
scripts/lb-pack.py from-dir ./root --name foo --version 1.0 -o foo.lbpa

# 不签名
scripts/lb-pack.py from-dir ./root --name foo --version 1.0 -o foo.lbpa --no-sign
```

## 许可

[GPL-3.0](LICENSE)
