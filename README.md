
# 通知日历同步 (calsync)

通过监听来自工作和社交APP的通知，将事件登记到您的日历中。  

> [!WARNING] 
> 此项目包含部分**AI生成**（已经过人类审查）和人类编写修正的内容。  
> 此项目目前仅支持**简体中文 Simplified Chinese**。

尽管此应用申请了设备和通知访问权限，但是该程序**未申请网络访问权限**，您的数据不会被存储，也不会传出设备。  

需要 Android 6.0 或更高版本。

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
  添加或修改
- android.permission.FOREGROUND_SERVICE
  
- android.permission.FOREGROUND_SERVICE_DATA_SYNC
  
- android.permission.POST_NOTIFICATIONS
  
- android.permission.QUERY_ALL_PACKAGES
  
- top.stevezmt.calsync.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION

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
# lint尚未完善，./gradlew build 无法编译
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
