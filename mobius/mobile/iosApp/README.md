# iOS shell

This folder contains the Swift entry used by the Compose Multiplatform iOS app.

The Xcode project is generated with [XcodeGen](https://github.com/yonaskolb/XcodeGen)
from `project.yml`. A "Compile Kotlin Framework" build phase runs
`:shared:embedAndSignAppleFrameworkForXcode` before compiling Swift, which:

- links the `MomoShared` static framework for the current SDK/architecture into
  `shared/build/xcode-frameworks/<CONFIGURATION>/<SDK_NAME>/`
- syncs Compose resources into the app bundle
  (`<app>/compose-resources/composeResources/...`)

Regenerate the Xcode project after changing `project.yml`:

```bash
cd iosApp
xcodegen generate --spec project.yml
```

Build and run on a concrete simulator device (the shared framework only contains
`arm64`, so use a specific destination rather than `generic`):

```bash
xcodebuild -project iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,name=iPhone 16 Pro,OS=18.5' \
  ONLY_ACTIVE_ARCH=YES \
  CODE_SIGNING_ALLOWED=NO \
  build
```

Notes:

- `ENABLE_USER_SCRIPT_SANDBOXING` must stay `NO` (Compose resource sync writes
  into `BUILT_PRODUCTS_DIR`).
- No manual framework copying is needed anymore; `iosApp/Frameworks/` is
  obsolete and gitignored.

## iOS 构建失败排查

Xcode Archive 报 `PhaseScriptExecution Compile Kotlin Framework` 失败时，先在仓库根目录直接跑
gradle 拿到真实 Kotlin 错误（Xcode 只显示任务失败不显示编译器输出）：

```bash
./gradlew :shared:compileKotlinIphoneOS 2>&1 | grep "^e:" | head -20
```

常见错误对照：
- `Unresolved reference: UTTypeItem` → UniformTypeIdentifiers binding 缺失，改用 `UIDocumentPickerViewController(documentTypes:...)` 旧 API（iOS 14 前）
- `None of the following candidates is applicable` (setCategory) → 位置参数已修正；若仍报错删掉 options 参数退回 `setCategory(category, error=null)`
- ObjC 泛型 cast 错误 (didPickDocumentsAtURLs) → 已改为 `as? List<NSURL>` 防御式
