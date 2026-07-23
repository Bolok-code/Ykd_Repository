# YKD 项目 Git 与 GitHub 团队协作规范

> 适用仓库：`Bolok-code/Ykd_Repository`  
> 适用对象：项目负责人、开发成员、代码审核人  
> 文档版本：v1.0  
> 更新日期：2026-07-22

## 1. 规范目标

本规范用于保证团队成员可以并行开发，同时避免以下问题：

- 直接向 `main` 推送未经验证的代码；
- 解决冲突时用一方代码覆盖另一方功能；
- 误操作回滚已经合并的功能；
- 把 API Key、Session、构建文件等敏感或临时内容提交到仓库；
- PR 方向选反，导致错误分支被合并；
- 主分支代码无法编译或无法运行。

核心原则：

```text
main 始终保持可编译、可测试、可运行
所有功能都在独立分支开发
所有改动都通过 Pull Request 进入 main
冲突必须理解双方代码后手动合并
未经审核不得回滚或覆盖他人的功能
```

## 2. 分支职责与命名

### 2.1 主分支

`main` 是团队唯一的稳定基线：

- 禁止直接开发；
- 禁止直接 push；
- 禁止强制推送；
- 禁止随意删除；
- 只能通过经过审核的 PR 更新；
- 合并前必须通过编译和测试。

### 2.2 开发分支

每项功能或修复必须创建独立分支，不要多人长期共用同一个开发分支。

推荐格式：

```text
dev-{成员缩写}-{功能名称}
```

示例：

```text
dev-czh-location
dev-lr-reminder
dev-xy-link-reader
dev-dzw-log-fix
```

不要继续使用含义不清的 `v2`、`new`、`test2` 作为唯一说明。确实需要版本号时，应同时包含功能名，例如：

```text
dev-czh-location-v2
```

## 3. 标准开发流程

### 3.1 开始开发前

任何新功能都必须从最新的远程 `main` 创建分支：

```bash
git switch main
git pull --ff-only origin main
git switch -c dev-成员缩写-功能名称
```

禁止从很久没有同步的旧分支继续创建新分支，否则会把旧代码和已经解决的问题重新带回来。

### 3.2 开发过程中

每次提交只完成一类明确改动，并使用统一提交格式：

```text
feat: 新增功能
fix: 修复问题
refactor: 重构但不改变业务行为
docs: 修改文档
test: 新增或调整测试
chore: 构建、依赖或工程配置调整
```

示例：

```bash
git add src/main/java/ykd/ykd/location
git commit -m "feat: 增加高德路线规划"
git push -u origin dev-czh-location
```

提交前必须检查：

```bash
git status
git diff --check
.\mvnw.cmd test
```

### 3.3 同步主分支

开发期间如果 `main` 出现新提交，应在自己的分支同步：

```bash
git fetch origin
git merge origin/main
```

同步后重新运行：

```bash
.\mvnw.cmd test
```

团队共享分支默认使用 `merge` 同步，不要随意使用会改写提交历史的 `rebase` 和强制推送。

## 4. Pull Request 规范

### 4.1 正确的 PR 方向

```text
base: main
compare: 个人开发分支
```

含义是：把个人开发分支的改动合并到 `main`。

创建 PR 前必须确认 `Files changed` 中只包含本次任务相关文件。如果出现大量无关文件，应停止合并并检查分支基线。

### 4.2 PR 标题

PR 标题使用与提交相同的类型：

```text
feat: 增加高德附近搜索和路线规划
fix: 修复机器人重复回复
docs: 增加团队 Git 协作规范
```

### 4.3 PR 描述模板

```markdown
## 改动内容

- 
- 

## 修改原因


## 影响范围

- [ ] 普通聊天
- [ ] 微信登录与消息接收
- [ ] 天气/位置
- [ ] 图片/语音/视频
- [ ] 提醒
- [ ] 配置或依赖

## 验证情况

- [ ] 本地编译通过
- [ ] `mvn test` 通过
- [ ] 微信实际功能验证通过
- [ ] 未提交 API Key、Session 或临时文件

## 需要审核人重点检查


```

### 4.4 审核规则

- PR 至少需要 1 名其他成员批准；
- PR 作者不能批准自己的 PR；
- 审核人必须检查业务逻辑，不能只看是否“没有红色报错”；
- 审核意见没有解决前不得合并；
- PR 在审批后又有新提交，必须重新审核；
- 合并前再次确认目标分支是 `main`；
- 默认推荐使用 `Squash and merge`，让一个 PR 在 `main` 中对应一条清晰提交。

## 5. 冲突处理规范

出现冲突并不代表必须二选一。冲突的真实含义是：两条分支修改了同一位置，Git 无法替团队判断最终代码。

标准处理流程：

```bash
git fetch origin
git merge origin/main
```

打开冲突文件后会看到：

```text
<<<<<<< HEAD
当前开发分支代码
=======
main 分支代码
>>>>>>> origin/main
```

处理要求：

1. 先理解两边分别实现了什么功能；
2. 判断是否需要同时保留；
3. 手动整理成最终代码；
4. 删除全部冲突标记；
5. 检查构造器参数、Spring Bean、工具注册和配置项是否完整；
6. 运行完整测试；
7. 再提交冲突解决结果。

```bash
git add 冲突文件
git commit -m "chore: 同步 main 并解决冲突"
.\mvnw.cmd test
git push
```

严禁以下操作：

- 不看代码直接选择 `Accept All Current`；
- 不看代码直接选择 `Accept All Incoming`；
- 用某个旧分支覆盖整个项目；
- 为了解决冲突而删除不属于自己的功能；
- 冲突未解决、测试未通过就提交 PR。

如果无法确定双方代码如何组合，必须联系相关功能作者共同确认。

## 6. 回滚规范

回滚会在历史中产生新的反向提交，不会真正删除原提交记录。错误回滚可能导致“提交历史里有功能，但最终文件已经被删除”，从而出现 GitHub 显示没有可比较内容的问题。

因此，任何已进入 `main` 的回滚都必须遵循：

1. 先确认问题来自哪个 PR 或提交；
2. 在团队群说明回滚原因和影响范围；
3. 创建单独的回滚分支或 GitHub Revert PR；
4. 检查回滚是否会删除其他功能；
5. 通过审核和测试后再合并；
6. 禁止直接在 `main` 上执行回滚并推送。

如果需要恢复已经被回滚的功能，应采用“撤销回滚提交”或从最新 `main` 选择性恢复代码，不要把整个仓库重置到旧提交。

## 7. 主分支保护规则

GitHub 中针对 `main` 的 Ruleset 应设置为：

```text
Enforcement status: Active
Target branch: main 或 Default branch
Require a pull request before merging: 开启
Required approvals: 1
Dismiss stale approvals when new commits are pushed: 开启
Require approval of the most recent reviewable push: 开启
Require conversation resolution before merging: 开启
Restrict deletions: 开启
Block force pushes: 开启
Bypass list: 默认留空
```

项目配置 GitHub Actions 后，再开启 `Require status checks to pass` 并绑定 Maven 测试任务。

## 8. 配置和密钥安全

以下内容禁止提交到 GitHub：

- DeepSeek、Agnes、ElevenLabs、高德等 API Key；
- `config/application-local.yml`；
- `.env`；
- `work/ilink-session.json`；
- 微信登录凭证；
- 临时音频、图片、视频和构建产物。

真实密钥只写入本地 `config/application-local.yml`。仓库中只维护不含真实密钥的示例文件：

```text
config/application-local.yml.example
```

如果密钥曾经被提交、截图公开或发送到公共渠道，应立即在对应平台作废并重新生成，仅仅删除文件不能消除 Git 历史中的泄漏。

## 9. 合并后的处理

PR 合并后，每位成员应更新本地基线：

```bash
git switch main
git pull --ff-only origin main
```

已经完成且不再需要的本地分支可以删除：

```bash
git branch -d dev-成员缩写-功能名称
```

远程分支由功能负责人在确认 PR 已合并后删除。不要删除仍在开发或仍被其他成员使用的分支。

## 10. 团队每日检查清单

### 开发者提交前

- [ ] 当前不在 `main` 分支开发；
- [ ] 分支基于最新 `main`；
- [ ] `git status` 中没有无关文件；
- [ ] 没有密钥和 Session；
- [ ] 编译、测试通过；
- [ ] 已说明改动内容和影响范围。

### 审核人合并前

- [ ] PR 方向为开发分支 → `main`；
- [ ] 没有大范围无关修改；
- [ ] 冲突解决没有覆盖其他功能；
- [ ] 测试结果可信；
- [ ] 所有讨论已经解决；
- [ ] 至少一名非作者成员批准；
- [ ] 确认不存在敏感信息。

## 11. 一句话工作流程

```text
更新 main → 创建个人功能分支 → 开发并测试 → 推送分支
→ 创建 PR → 同行审核 → 解决问题 → 合并 main → 删除已完成分支
```

