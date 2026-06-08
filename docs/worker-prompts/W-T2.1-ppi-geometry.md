你是船用雷达安卓显控项目（送 IMO CAT 1 型式认证）的员工 AI，负责任务 **T2.1（几何部分）：PPI 极坐标→屏幕变换与刻度几何**。

## 上下文
PPI 圆形画面需要把辐条的(方位, 量程)映射到屏幕像素，并生成距离环、方位刻度、船首线等几何。**这部分是纯数学（无 Android 绘制）**，Android 渲染面（GLSurface/Canvas）在第二波另派，会调用你的几何函数。**送型式认证：320mm 等效、量程档、刻度必须精确**。合规追溯 ID：DISP-01、DISP-02。标准原文：`~/Desktop/雷达开发资料/标准资料/IEC62388_FDIS.pdf`。

## 工作规则（铁律）
1. 独立 worktree：`git worktree add ../wt-ui-ppi -b feat/ui-ppi`（从 `3d07258`）。
2. **只改 `ui-core/` 下、package `com.shipradar.uicore.ppi`**。不碰 shared、其他 package、根 gradle。**不要做着色**（T2.2 负责）、不做目标符号（T2.3 负责）。
3. 用 `com.shipradar.contract.EchoSpoke`、`com.shipradar.constants.MANDATORY_RANGE_SCALES_NM`、`com.shipradar.util.Angles`。输出平台无关坐标（Float/Double，像素或归一化），不引入 Android。
4. 必须带单元测试，`./gradlew :ui-core:test` 全绿。
5. **送认证**：320mm 等效、量程档、刻度的实现 KDoc 标注 `IEC 62388 §x` / `IEC 62288 §x`；精确落实不近似，缺值 `TODO(待标准)`。**勿直接改矩阵文件**；把 DISP-01/02 落实的条款行写进交付报告，编排者并入。

## 实现要求
纯函数/数据类，平台无关：
- **坐标变换**：`fun polarToScreen(azimuthDeg, rangeFraction, center, radiusPx, orientation): PointF-like`。支持显示朝向：**船首向上 / 北向上 / 航向向上**（用本船 heading/COG 作参数旋转）。约定：方位0=船首，顺时针增。
- **量程映射**：当前量程档(NM) → 半径像素；采样索引→量程分数（注意 over-scan 1.8：采样0~569 为量程内，可裁剪过扫描）。提供 NM↔像素 互转。
- **强制量程档**：用 `MANDATORY_RANGE_SCALES_NM`（0.25/0.5/0.75/1.5/3/6/12/24），提供"下一档/上一档"与"换档时画面消隐≤1扫描周期"的几何支持（标注 DISP-02）。
- **距离环**：按当前量程生成 N 个等间距距离环半径 + 每环对应 NM 标注。
- **方位刻度**：圆周刻度（每30°粗/10°中/小刻度），随朝向旋转；船首线、电子方位线占位接口。
- **320mm 等效**（DISP-01）：给定屏幕物理 DPI/尺寸，计算有效显示区直径是否 ≥320mm，并提供把有效区缩放到 ≥320mm 等效的换算函数（多分辨率适配）。物理尺寸由调用方传入（设备型号待定，留参数）。

## 验证
- 单元测试：北向上/船首向上/航向向上三模式下若干 (方位,量程) → 已知屏幕坐标断言；量程档切换；距离环半径；320mm 等效换算（给定 DPI 断言 mm）。

## 交付报告
改动文件、测试结果、DISP-01/02 追溯行、对设备物理尺寸/DPI 的依赖说明（列为待张建输入）、疑问。
