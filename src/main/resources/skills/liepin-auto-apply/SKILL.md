---
name: liepin-auto-apply
description: 当用户要求在猎聘搜索岗位、匹配简历、创建自动投递计划，或查询、取消、暂停、停止投递计划与任务状态时使用此技能。
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
  - exitLiepinSkill
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
3. 搜索任务本身只生成候选岗位，不会自动投递。
4. 后台任务完成后向用户返回候选岗位。
5. 用户从候选列表明确确认某个岗位（如“投递第1个”），调用 `confirmLiepinJobApplication`，
   系统会点击该岗位的“聊一聊”按钮发起投递，猎聘会自动发送预置招呼语。

### 四、手动确认投递

1. 用户要求投递某个候选岗位前，必须先调用 `listLiepinJobCandidates` 获取最新候选列表，以最新返回的序号和岗位信息为准，不得凭对话记忆中的旧列表判断序号。
2. 用户明确说出要投递的岗位（如"投递第2个"）后，调用 `confirmLiepinJobApplication` 传入最新列表中的序号。
3. 只有成功调用 `confirmLiepinJobApplication` 并拿到返回结果后，才能回复"已确认投递/已开始投递"。禁止只凭候选列表自行宣布投递成功，禁止模仿历史回复中的"已确认投递"话术。
4. 回复用户时，岗位名称、公司、薪资等细节必须以 `confirmLiepinJobApplication` 的返回内容为准，不得自行补充或凭记忆播报。
5. "已确认投递"只代表后台开始执行，不代表简历已发送成功。最终结果以系统主动推送为准；如果工具返回失败或系统推送失败结果，必须如实告知用户。
6. 确认后是后台异步投递，完成后系统会主动推送结果。

### 五、创建自动投递计划

1. 确认用户已经保存简历。
2. 收集完整求职条件。
3. 调用 `createLiepinAutoApplyCampaign` 创建计划。
4. 创建计划后必须向用户展示计划内容。
5. 创建计划不等于启动计划。

### 六、启动自动投递

1. 只有用户明确说“确认启动”“开始自动投递”等意思时，才允许调用 `startLiepinAutoApplyCampaign`。
2. “好的”“知道了”“看看”等模糊表达不得视为启动授权。
3. 启动后按照每日限额、匹配分数、去重规则和执行间隔运行。

### 七、计划管理

- 用户要求暂停时，调用 `pauseLiepinAutoApplyCampaign`。
- 用户要求永久停止时，调用 `stopLiepinAutoApplyCampaign`。
- 用户查询计划状态时，调用 `getLiepinAutoApplyCampaignStatus`。
- 用户查询投递记录时，调用 `listLiepinAutoApplications`。

### 八、退出技能模式

1. 当用户明确表示退出、关闭或结束求职技能（如"退出技能""退出猎聘""退出简历""退出求职模式"），必须先调用 `exitLiepinSkill` 退出技能模式，再回复用户。
2. 只有实际调用 `exitLiepinSkill` 并拿到返回，或工具返回中明确提到"已自动退出"时，才能回复用户"技能模式已退出"。禁止凭对话历史推断技能状态并凭空声称已退出。
3. 取消任务（`cancelLiepinJobTask`）或永久停止计划（`stopLiepinAutoApplyCampaign`）成功后会自动退出技能模式，工具返回中会提示用户。
4. 退出后用户可进行普通对话（天气、图片等）；用户再次表达求职意图时会重新激活本技能。
5. 退出成功后，如果本条消息中还包含其他需求（如天气、提醒、图片生成等），不得在同一轮内继续执行或编造结果；应如实告知用户已退出技能模式，并请用户重新发送需求，系统会以普通对话模式处理。

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
