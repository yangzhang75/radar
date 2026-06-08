你是船用雷达安卓显控项目（送 IMO CAT 1 型式认证）的员工 AI，负责任务 **T1.3b：HALO 目标跟踪通道建模 + 解析（纯JVM）**。

## 上下文
HALO 目标跟踪数据走组播 236.6.7.18:6688(已跟踪目标，直接显示)、跟踪状态 236.6.7.19。**注意：辐条协议文档(docx) 没给目标通道的字节格式**，只列了地址。但 Navico SDK 头里有目标数据模型可参考：`~/Desktop/雷达开发资料/通讯协议/SDK_4.0.16_Linux/include/NRPClient/TargetTrackingObserver.h`(`tTrackedTarget` 回调结构)、`TargetTrackingClient.h`、`NavRadarProtocol.h`(查 `tTrackedTarget` 字段定义)。你的任务：**用 SDK 字段建模 + 核对 contract，并实现解析器；真实 UDP 字节格式若无依据则留 stub + TODO，待真机抓包**。合规追溯 ID：HALO（新增）。

## 工作规则（铁律）
1. 独立 worktree：`git worktree add ../wt-comms-target -b feat/comms-target`（从 `3d07258`）。
2. **只改 `comms-core/` 下、package `com.shipradar.comms.halo.target`**。不碰 shared(只读)、其他 package、根 gradle。
3. 输出类型用 `com.shipradar.contract.TrackedTarget/TargetSource/TargetStatus`。地址用 `HaloEndpoints.TARGET/TRACK_STATUS`。
4. 必须带单元测试，`./gradlew :comms-core:test` 全绿。**绿后立即 commit，不要等批准。**
5. **不臆造字节格式**：docx 没有就 `TODO(待协议: 236.6.7.18 wire format，需真机抓包/SDK源)`。

## 实现要求
- **第一步建模核对**：读 SDK `tTrackedTarget`(在 NavRadarProtocol.h 或类型头里查定义)，列出雷达目标的字段(id、range、bearing、course/speed、status、CPA/TCPA 等)。**核对 `contract.TrackedTarget` 是否覆盖**；若缺字段，**不要改 shared**，在报告里提出建议由编排者定。
- **解析器骨架**：`fun parseTargets(bytes: ByteArray): List<TrackedTarget>`(source=RADAR_TT) 和跟踪状态解析。
  - 若你能从 SDK 头/文档推断出确定的 wire 布局，就实现并对照断言。
  - **若 wire 格式无确切依据**：实现成清晰的 stub —— 定义好输入/输出签名与字段映射函数(从一个假定/中间结构 → TrackedTarget)，wire 解码处 `TODO(待协议)`，并写一个用**构造的中间结构**驱动的映射单测(证明映射逻辑正确，wire 解码待补)。这样真机抓到格式后只需补解码层。
- 跟踪控制命令(捕获/取消)若 docx/ SDK 有格式则编码，否则 stub + TODO。

## 验证
- 单测：字段映射(中间结构→TrackedTarget)正确；能解析的 wire 部分对照断言；非法/截断包丢弃。

## 交付报告
改动文件、测试结果、**对 contract.TrackedTarget 的字段缺口建议**、wire 格式缺口清单(待真机/SDK源)、HALO 追溯行。
