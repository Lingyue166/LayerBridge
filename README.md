# LayerBridge（lb）

Android 原生的 Linux 包管理器：把 bionic/aarch64 包安装到独立前缀直接执行，配套 root 模块与 `lb` 命令。

## 特性

- 支持 `.deb` 与 LBPA 两种包；签名校验、安装前冲突检查
- 安装事务：任意阶段中断都能被 `lb remove` 清干净
- 卸载默认保留数据，`lb remove --force` / `lb purge` 全清
- 任意版本升降级，不要求版本相邻
- 默认隐藏：前缀 `700`，命令只对 root 可见，不写 `/system/bin`

## 安装

```bash
adb install -r app-debug.apk
adb push lb-module.zip /sdcard/Download/
su -c 'ksud module install /sdcard/Download/lb-module.zip'   # 或 Magisk 管理器
# 重启后
su -c 'lb doctor'
```

## 使用

```bash
su -c 'lb install --allow-unsigned /sdcard/pkg.deb'
su -c 'lb list'
su -c 'hello'
su -c 'lb shell'
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

## 文档

- [架构设计](md/00-design/lb-architecture.md)
- [LBPA 包格式](md/00-design/lbpa-format.md)
- [ADR 索引](md/60-changes/README.md)

## 许可

[GPL-3.0](LICENSE)
