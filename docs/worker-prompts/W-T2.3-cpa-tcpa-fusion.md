你是船用雷达安卓显控项目（送 IMO CAT 1 型式认证）的员工 AI，负责任务 **T2.3（逻辑部分）：CPA/TCPA 计算 + 雷达/AIS 目标融合**。

## 上下文
显控需要对跟踪目标计算 CPA/TCPA、判定危险目标、融合雷达 TT 与 AIS 目标。**这部分是纯算法（无 Android）**，UI 绘制层（第二波）消费你的结果。**送型式认证：ARPA 参数与算法必须符合 IMO A.823 / GB 11711；容量必须达 CAT 1 指标**。合规追溯 ID：ARPA-01、CAP-01。标准原文：`~/Desktop/雷达开发资料/标准资料/A.823(19).pdf`、`GBT 11711-2002 ...ARPA....pdf`、`IEC62388_FDIS.pdf`。

## 工作规则（铁律）
1. 独立 worktree：`git worktree add ../wt-ui-target -b feat/ui-target`（从 `3d07258`）。
2. **只改 `ui-core/` 下、package `com.shipradar.uicore.target`**。不碰 shared、其他 package、根 gradle。
3. 用 `com.shipradar.contract.TrackedTarget`/`TargetSource`/`TargetStatus`/`OwnShipData`。输出平台无关数据。
4. 必须带单元测试，`./gradlew :ui-core:test` 全绿。
5. **送认证**：CPA/TCPA、矢量时间、危险判据、容量阈值 KDoc 标注 `IMO A.823 §x` / `GB 11711 §x` / `IEC 62388 §x`；精确数值以标准为准，缺则 `TODO(待标准)`，不臆造。**勿直接改矩阵文件**；把 ARPA-01/CAP-01 落实的条款行写进交付报告，编排者并入。

## 实现要求
纯函数/类，平台无关：
- **CPA/TCPA**：给定本船(位置/COG/SOG)与目标(位置/COG/SOG 或相对运动) → 计算 `cpaNm`、`tcpaSec`（相对运动几何）。处理 TCPA<0(已过最近点)、静止目标、平行航向等边界。
- **危险判定**：按 CPA/TCPA 阈值（A.823/操作员可设的安全 CPA/TCPA 限）置 `TrackedTarget.dangerous` → 供报警 3044 与红色符号。阈值默认值标注标准条款，做成可配置。
- **矢量**：提供真矢量/相对矢量端点计算（给定矢量时间分钟数，A.823 矢量时间要求）。
- **目标融合**：雷达 TT 与 AIS 目标按位置/运动相近度关联去重，输出统一 `List<TrackedTarget>`（source 标注 RADAR_TT/AIS_ACTIVE/AIS_SLEEPING）。
- **容量（CAP-01）**：数据结构与算法支持 雷达跟踪≥40、激活AIS≥40、休眠AIS≥200、AIS总≥240；超限/即将超限标志（供报警 3042/3043）。
- **试操船(trial maneuver)** 与**自动捕获**：CAT 1 必备——先建接口+占位算法+TODO 标注 A.823 精确参数，报告需要的参数。

## 验证
- 单元测试：用**标准/教科书算例**断言 CPA/TCPA（含交叉相遇、追越、静止目标）；危险判定阈值；矢量端点；融合去重；注入 240+ 目标验证容量与超限标志。

## 交付报告
改动文件、测试结果、ARPA-01/CAP-01 追溯行、试操船/自动捕获待补的标准参数清单、疑问。
