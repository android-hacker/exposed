# 简介

exposed 致力于为App提供 Xposed 运行环境。基本思路是劫持APP进程启动的入口，加载 Xposed 插件以及 hook框架 [epic](https://github.com/tiann/epic) 。

exposed本质上是一个 Xposed 与 APP进程 之间的兼容层，它给Xposed模块提供运行环境（如插件加载、hook环境等）。

目前使用最广泛的实现是 VirtualXposed，它使用 [VirtualApp](https://github.com/asLody/VirtualApp) 来运行APP并提供进程入口劫持。但是，exposed 本身并不依赖双开宿主；甚至不需要双开。

另外，基于APP加固的思路，甚至直接修改APK，或者通过magisk注入进程的方式，都可以实现 Xposed。

目前有以下几种实现方式：

- [VirtualXposed](https://github.com/android-hacker/VirtualXposed) ：基于双开实现，通过 VirtualApp 运行目标APK，在进程启动入口加载 exposed。优势：免安装，无篡改签名问题。劣势：性能 & 稳定性受限于双开，无法与系统完全交互。
- [太极](https://www.coolapk.com/apk/me.weishu.exp)：基于修改APK实现。在 Application 类的入口织入 exposed 入口代码，从而加载 exposed。优势：可以与系统完全交互，性能好。劣势：签名改变，虽有独特技术可以绕过所有检测，但是依然有风险。部分APP调用其他会检测签名，使得所有APP必须被“太极化”，风险极高。
- [太极·Magisk](https://mp.weixin.qq.com/s?__biz=MjM5Njg5ODU2NA==&tempkey=OTkwX0JJa0I4ZW9qcmd5bGlJSXlwQjBJOTZsWGc0TllULXVXdGVicTQxcWRyWE9McnZFQVozRGpNS21OaHEySDNHbFlfMUVudk9wbHo0akE4c29hOTZhNGs5UENXQlFISlFvQjZFSS1CT1dCa1hSZWt4XzFKNV9abEZITTJNOEJkVkotVEdrN2owcmxzeU9WVF9oaVUxdlJwd3pkcHZDWXFPOTFNVEhBeUF%2Bfg%3D%3D&chksm=25983cf012efb5e6ac3fe06bd73883139a89912fa37aee74f3b3baca9e358b2c41a260cee682#rd)：通过 Magisk 修改系统文件，在 Zygote 进程启动的时候执行 exposed 入口代码，从而加载 exposed。优势：完全体，不存在上述所有问题。劣势：需要解锁Bootloader和刷机。

# 使用

Exposed 是一个library，是提供给开发者使用的；如果你需要在非ROOT环境下运行Xposed，请移步项目 [VAExposed](https://github.com/android-hacker/VAExposed)

如果需要在APP进程中中提供Xposed运行环境，在进程启动的入口，执行如下调用即可：

```java
ExposedBridge.initOnce(context, applicationInfo, appClassLoader);
```

同时，为了加载Xposed模块，需要在进程启动的时候执行模块加载：

```java
ExposedBridge.loadModule(moduleApk, apkOdexDir, moduleLibDir, applicationInfo, appClassLoader);
```

