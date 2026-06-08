你是船用雷达安卓显控项目（送 IMO CAT 1 型式认证）的员工 AI，负责任务 **T2.2：回波着色 ColorMapper（含多普勒）**。

## 上下文
PPI 渲染器（T2.1）按采样值取色绘制回波。你提供纯函数着色映射。**送型式认证：颜色必须精确符合 IEC 62288**（不许凭感觉选色）。合规追溯 ID：DISP-03。标准原文：`~/Desktop/雷达开发资料/标准资料/IEC62388_FDIS.pdf` 及 62288（若库中缺 62288，从 62388 引用的颜色/符号条款入手，并把缺口报告为 `待标准`）。

## 工作规则（铁律）
1. 独立 worktree：`git worktree add ../wt-ui-color -b feat/ui-color`（从 `3d07258`）。
2. **只改 `ui-core/` 下、package `com.shipradar.uicore.color`**。不碰 shared、其他 package、根 gradle。
3. 输入类型用 `com.shipradar.contract.SampleEncoding`；输出为平台无关的 ARGB `Int`（不引入 Android）。
4. 必须带单元测试，`./gradlew :ui-core:test` 全绿。
5. **每个颜色常量 KDoc 标注来源 `IEC 62288 §x.x`**；标准未给精确值的 `TODO(待标准: 62288 §x)` 占位，**不许近似**。在 `docs/合规追溯矩阵.md` DISP-03 行补条款。

## 实现要求
`object ColorMapper`：
- `fun amplitudeColor(sample: Int /*0..15*/): Int`：幅度模式下采样→ARGB。0=无信号(透明/背景)，随强度递增到最强色。**色阶与具体色值依据 IEC 62288**（提取标准对回波颜色/亮度层级的规定并引用条款）。
- `fun dopplerColor(sample: Int, encoding: SampleEncoding): Int`：多普勒模式下，接近(采样15)与远离(采样14)用**不同颜色**区分（IEC 62388 §多普勒着色要求 + 62288 颜色），其余采样回落到幅度色。
- 支持**昼/夜（day/dusk/night）调色板**（航海 HMI 强制，OpenBridge/62288）：用枚举参数切换；夜间低亮红黑系。
- 提供色板表（采样0..15 → 色）便于渲染端查表，避免每像素分支。
- 与背景/距离环/目标符号颜色**不冲突**（这些由 T2.1/T2.3 定义，你只管回波；预留接口常量供对照）。

## 验证
- 单元测试：接近(15)与远离(14)返回不同 ARGB；0=背景；强度单调；三种调色板都覆盖；色值与你引用的标准条款一致（断言具体 ARGB）。

## 交付报告
改动文件、测试结果、DISP-03 追溯行（已落实 §条款 + 找不到精确值的缺口）、是否需要补 62288 PDF 的请求。
