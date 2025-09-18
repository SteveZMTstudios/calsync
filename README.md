

# 通知日历同步 (calsync)

通过监听来自工作和社交APP的通知，将事件登记到您的日历中。

## 


## 快速开始

依赖：
- JDK 21
- 


先决条件：
- JDK（本项目 gradle.properties 指定了 org.gradle.java.home 为 JDK 21 路径；确保你的机器安装了兼容的 JDK，或在 `gradle.properties` 中调整该路径）
- Android Studio（建议使用最新稳定版）或 Android SDK + Gradle

在项目根目录下打开终端并执行：

```pwsh
./gradlew assembleDebug
```

或在 Windows 下使用：

```pwsh
.\gradlew.bat assembleDebug
```

在 Android Studio 中直接打开项目（选择包含 `settings.gradle.kts` 的根目录），让 IDE 同步 Gradle 配置后即可运行或调试 `app` 模块。

## 构建变体与签名

当前 `app/build.gradle.kts` 定义了 `release` 与默认 `debug` 配置。release 默认未开启混淆（isMinifyEnabled=false），如需发布请调整混淆/签名配置并在 `buildTypes` 中添加签名配置。

构建产物会输出到 `app/build/` 与根 `build/` 目录（该仓库已包含部分 build 输出用于说明或调试，但通常不应将 build 产物提交到版本控制）。

## 依赖与插件管理

该项目使用 Gradle 的版本目录（Version Catalog），通过 `libs` 别名引用依赖与插件。查看 `gradle/libs.versions.toml` 获取确切的版本号和完整依赖列表。


## 贡献

欢迎以 issue 或 pull request 形式贡献。提交前请：

1. 创建 Issue 描述你要修复的 bug 或新增的功能。
2. 新建分支实现变更并保证项目能编译通过。
3. 提交包含简短说明的 Pull Request。

若要贡献代码风格或格式化改动，请先在 Issue 中讨论，以避免大型格式化提交占据审查流量。

## 许可证

GPLv3

