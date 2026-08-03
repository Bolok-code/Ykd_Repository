---
name: liepin-auto-apply
description: 当用户要求在猎聘搜索岗位、匹配简历、创建自动投递计划或查询投递结果时使用此技能。
version: 1.0.0
enabled: true
tools:
  - openLiepinLogin
  - checkLiepinLoginStatus
  - saveCurrentDocumentAsLiepinResume
  - searchLiepinJobs
  - createLiepinAutoApplyCampaign
  - startLiepinAutoApplyCampaign
  - pauseLiepinAutoApplyCampaign
  - stopLiepinAutoApplyCampaign
  - getLiepinAutoApplyCampaignStatus
  - listLiepinAutoApplications
  - listLiepinJobCandidates
  - confirmLiepinJobApplication
  - getLiepinJobTaskStatus
  - cancelLiepinJobTask
---

# 猎聘自动求职技能

## 技能目标

帮助用户在猎聘平台完成简历保存、岗位搜索、岗位匹配、自动投递计划创建、计划执行和投递结果查询。

## 触发条件

当用户表达以下意图时使用本技能：

- 保存或设置求职简历
- 登录猎聘
- 搜索猎聘岗位
- 根据简历匹配岗位
- 创建自动投递计划
- 启动、暂停或停止投递计划
- 查看候选岗位
- 查看投递状态或投递记录

普通聊天、天气查询、图片生成等请求不得使用本技能。

## 必要信息

创建岗位搜索任务至少需要：

- 岗位关键词
- 目标城市

创建自动投递计划需要：

- 用户简历
- 岗位关键词
- 目标城市
- 最低和最高薪资
- 最低匹配分数
- 每日投递上限
- 执行间隔
- 是否排除外包
- 简历投递方式

如果必要信息不完整，应先询问用户，不得自行编造。

## 标准执行流程

### 一、保存简历

1. 检查用户是否刚刚上传PDF或Word文件。
2. 用户明确表示该文件是求职简历时，调用 `saveCurrentDocumentAsLiepinResume`。
3. 保存成功后告知用户支持在线简历、附件简历或自动选择。

### 二、猎聘登录

1. 执行任务前调用 `checkLiepinLoginStatus` 检查登录状态。
2. 如果没有登录，调用 `openLiepinLogin` 打开登录页面。
3. 提醒用户完成扫码、验证码或其他人工登录操作。
4. 登录恢复后继续原任务。
5. 不得尝试绕过验证码、风控或猎聘安全机制。

### 三、搜索岗位

1. 收集岗位关键词、城市、薪资范围和外包筛选条件。
2. 调用 `searchLiepinJobs` 创建岗位搜索任务。
3. 搜索任务本身只生成候选岗位，不会自动发送简历。
4. 后台任务完成后向用户返回候选岗位。
5. 用户从候选列表明确确认某个岗位（如“投递第1个”），调用 `confirmLiepinJobApplication`，
   系统会真正向该岗位发送简历（在线简历优先，附件简历兜底），不是只打招呼。

### 四、创建自动投递计划

1. 确认用户已经保存简历。
2. 收集完整求职条件。
3. 调用 `createLiepinAutoApplyCampaign` 创建计划。
4. 创建计划后必须向用户展示计划内容。
5. 创建计划不等于启动计划。

### 五、启动自动投递

1. 只有用户明确说“确认启动”“开始自动投递”等意思时，才允许调用 `startLiepinAutoApplyCampaign`。
2. “好的”“知道了”“看看”等模糊表达不得视为启动授权。
3. 启动后按照每日限额、匹配分数、去重规则和执行间隔运行。

### 六、计划管理

- 用户要求暂停时，调用 `pauseLiepinAutoApplyCampaign`。
- 用户要求永久停止时，调用 `stopLiepinAutoApplyCampaign`。
- 用户查询计划状态时，调用 `getLiepinAutoApplyCampaignStatus`。
- 用户查询投递记录时，调用 `listLiepinAutoApplications`。

## 安全规则

1. 未经用户明确确认，不得启动自动投递。
2. 不得绕过验证码、登录验证和平台风控。
3. 不得超过用户设置的每日投递上限。
4. 不得对同一用户和同一岗位重复投递。
5. 登录失效时暂停任务并提醒用户重新登录。
6. 连续失败达到系统上限时暂停计划。
7. 工具返回失败时必须如实告知用户，不得虚构投递成功。
8. 简历及求职信息必须按照用户ID隔离。

## 输出要求

每次回复应明确说明：

- 当前执行了什么操作
- 当前计划或任务状态
- 是否需要用户继续操作
- 如果失败，失败原因是什么
- 下一步用户可以做什么