
<div align="center">

![](app\src\main\res\mipmap-xxxhdpi\ic_launcher_round.webp)

# 通知日历同步
通过监听来自工作和社交APP的通知，将事件登记到您的日历中。

[![GitHub repo size](https://img.shields.io/github/repo-size/stevezmtstudios/calsync?style=flat-square)](#)
[![GitHub release (release name instead of tag name)](https://img.shields.io/github/v/release/stevezmtstudios/calsync?style=flat-square)](https://github.com/stevezmtstudios/calsync/releases)
[![GitHub issues](https://img.shields.io/github/issues/stevezmtstudios/calsync?style=flat-square)](#)
[![GitHub license](https://img.shields.io/github/license/stevezmtstudios/calsync?style=flat-square)](LICENSE)

</div>

> [!WARNING] 
> 此项目包含部分**AI生成**（已经过人类审查）和人类编写修正的内容。  
> 此项目目前仅支持**简体中文 Simplified Chinese**。

尽管此应用申请了设备和通知访问权限，但是该程序**未申请网络访问权限**，您的数据不会被存储，也不会传出设备。  

需要 Android 6.0 或更高版本。

<div style="position:relative;padding-bottom:56.25%;height:0;overflow:hidden;max-width:100%;">
  <iframe 
    src="//player.bilibili.com/player.html?isOutside=true&aid=115399312086930&bvid=BV1srsFzQE3t&cid=33205783366&p=1"
    title="Bilibili 视频"
    scrolling="no"
    allowfullscreen="true"
    style="position:absolute;top:0;left:0;width:100%;height:100%;border:0;">
  </iframe>
</div>

<details>
<summary>详细信息</summary>

### 包名  
`top.stevezmt.calsync`

### SDK版本信息
minSDK: 23
tarSDK: 36

### 权限列表  
- android.permission.READ_CALENDAR
  读取日历活动和详情
- android.permission.WRITE_CALENDAR
  添加或修改日历活动
- android.permission.FOREGROUND_SERVICE
  允许在前台运行服务
- android.permission.FOREGROUND_SERVICE_DATA_SYNC
  允许在前台运行数据同步服务
- android.permission.POST_NOTIFICATIONS
  允许发送通知
- android.permission.QUERY_ALL_PACKAGES
  允许查询所有已安装的应用包
- top.stevezmt.calsync.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
  允许动态注册未导出的广播接收器

### 三方库
未引入任何三方库

</details>


## 使用指引
将该应用安装到您的设备，按照提示授予 `日历`（**需完全访问权限**）和`通知`权限后，前往设置授予`设备和通知权限`（通知访问权），当通知栏看到`正在监听后台通知`常驻提示时，程序即已在后台运行。

默认配置下会匹配通知中包含`通知``班级群`字样的所有来源的通知。


## 快速开始

依赖：
- JDK 21
- Gradle 8.10+
- Android SDK（API 21+）

### 克隆项目
```bash
git clone https://github.com/yourusername/calsync.git
cd calsync
```

### 构建项目
```bash
GRADLE_OPTS="-Xmx3g" ./gradlew assembleRelease
# or
./gradlew build
```

### 运行测试
```bash
./gradlew test
```

## 鸣谢

https://github.com/NagiYan/TimeNLP
https://github.com/xkzhangsan/xk-time
https://github.com/huaban/jieba-analysis

## 许可证

GPL-3.0 License. 详情见 [LICENSE](LICENSE) 文件。

```
Copyright (C) 2025  Steve ZMT me@stevezmt.top

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://gnu.ac.cn/licenses/>.
```
