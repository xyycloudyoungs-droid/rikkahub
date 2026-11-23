# CHANGES

## 总管助手：最小可用版本（`delegate_task`）

实现“总管”助手调用 App 内其他助手的最小版本：总管通过 `delegate_task` 工具把子任务分派给指定助手，子任务在后台子会话中同步执行，完成后把结果返回给总管。

本次范围：

- 新增 `delegate_task` 工具
- 新增 `AssistantDelegationService`
- 子任务使用目标助手的完整配置（模型、系统提示词、工具、记忆、输入/输出变换器）
- 暂不做异步 `postNotice`、任务看板、设置页开关
- 未修改 `Assistant.kt`

### 改动文件

| 文件 | 类型 | 作用 |
|---|---|---|
| `app/src/main/java/me/rerere/rikkahub/data/ai/delegation/AssistantDelegationService.kt` | 新增 | 子任务创建、同步执行、结果汇总、子会话落库 |
| `app/src/main/java/me/rerere/rikkahub/data/ai/tools/ChatToolFactory.kt` | 修改 | 注册 `delegate_task` 工具 |
| `app/src/main/java/me/rerere/rikkahub/di/AppModule.kt` | 修改 | Koin 接线，注入服务并打破循环依赖 |

### 1. 新增 `AssistantDelegationService.kt`

路径：`app/src/main/java/me/rerere/rikkahub/data/ai/delegation/AssistantDelegationService.kt`

包含：

- `AssistantDelegationScope`
  - 记录当前正在执行的子会话 ID。
  - `ChatToolFactory` 据此不给子会话注册 `delegate_task`，避免“总管 → 子助手 → 子助手”无限套娃。
  - 放在单独的单例对象里，让 `ChatToolFactory` 构建工具时不需要解析服务，避免 Koin 循环依赖。

- `DelegationResult` / `toToolOutput()`
  - 把子任务结果转成 JSON 交给总管：
    - `status`：`completed` / `awaiting_approval` / `paused` / `step_limit_reached` / `failed`
    - `assistant_id`
    - `assistant_name`
    - `conversation_id`
    - `result`：子助手最终正文
    - `note`：非正常结束时的说明

- `AssistantDelegationService.delegate(...)`
  1. 校验 `task` 非空、目标助手存在、当前不是子会话。
  2. 用目标助手创建真实 `Conversation`，调用 `bindConfig(settings)` 固定模型/推理/搜索等配置。
  3. 通过 `ChatRequestFactory.createContext` + `createAgent` + `runAgent` **同步**执行子任务。
  4. 收集 `AgentEvent.Finished`，只取最终助手消息里的 `Text` part。
  5. 把“用户任务 + 子助手回复”作为子会话落库，标题为 `子任务：...`。
  6. 处理超时、取消、异常、等待审批、步数上限。
  7. 限制：最多 32 步、单次 10 分钟、返回正文最多 12,000 字符。

### 2. 修改 `ChatToolFactory.kt`

路径：`app/src/main/java/me/rerere/rikkahub/data/ai/tools/ChatToolFactory.kt`

- 构造函数新增：

  ```kotlin
  private val assistantDelegationService: Lazy<AssistantDelegationService>
  ```

- `createTools` 中新增注册逻辑：

  ```kotlin
  if (!AssistantDelegationScope.isDelegatedSubConversation(conversationId)) {
      addAll(createAssistantDelegationTool(settings, assistant, conversationId))
  }
  ```

  - 普通对话/总管会话会得到 `delegate_task`。
  - 后台子会话不会得到该工具。

- 新增 `createAssistantDelegationTool(...)`：
  - 工具名：`delegate_task`
  - 参数：
    - `assistant_id`（必填）：目标助手 UUID
    - `task`（必填）：完整、自包含的任务描述
    - `context`（可选）：背景/约束/风格要求
    - `expected_output`（可选）：期望输出格式
  - `systemPrompt`：把除当前助手外的所有助手以 `<available_assistants><assistant><id>...` 的形式注入系统提示词，模型据此选择目标助手。
  - `execute`：调用 `AssistantDelegationService.delegate(...)`，返回 JSON 文本。
  - 增加 `escapeXml()`，防止助手名称里的特殊字符破坏系统提示词结构。

- 工具描述中明确要求：
  - 只在用户要求协调其他助手，或任务明显需要专门助手时使用；
  - 子助手看不到当前会话，所以 `task` 必须写完整。

### 3. 修改 `AppModule.kt`

路径：`app/src/main/java/me/rerere/rikkahub/di/AppModule.kt`

Koin 接线（这是让 `ChatToolFactory` 能拿到服务所必需的第三个文件）：

```kotlin
single {
    AssistantDelegationService(
        settingsStore = get(),
        conversationRepository = get(),
        requestFactory = lazy { get() },
    )
}

single {
    ChatToolFactory(
        ...
        assistantDelegationService = lazy { get() },
    )
}
```

两处 `lazy { get() }` 用来打破：

```text
ChatService -> ChatRequestFactory -> ChatToolFactory
     -> AssistantDelegationService -> ChatRequestFactory
```

的循环依赖。

---

## 当前行为

- 总管的模型必须支持工具（`ModelAbility.TOOL`），否则模型不会调用 `delegate_task`。
- 因为按本次要求没有给 `Assistant` 加字段，**所有助手都会看到 `delegate_task`**；后台子会话不会。
- 子任务使用目标助手的完整配置：独立模型、系统提示词、工具、记忆、输入/输出变换器。
- 总管这一轮会**同步阻塞**，直到子任务结束并返回结果。
- 子会话完成后落库，可以在目标助手的历史里看到一条 `子任务：...` 的会话。
- 子任务如果遇到需要审批的工具（例如目标助手启用了 `ask_user`）：
  - 返回 `status = awaiting_approval`
  - 子会话已经落库，用户可以打开该子会话手动审批
  - 总管会收到状态说明，但不会自动继续
- 超时/异常返回 `status = failed`，不会中断总管这一轮。

返回给总管的 JSON 示例：

```json
{
  "status": "completed",
  "assistant_id": "....",
  "assistant_name": "写手",
  "conversation_id": "....",
  "result": "子助手最终输出正文"
}
```

---

## 验证

### 1. 编译

在具备 Android SDK 的开发机上：

```bash
./gradlew :app:compileGithubDebugKotlin
# 或
./gradlew assembleGithubDebug
```

需要设置 `ANDROID_HOME`，或在项目根目录创建 `local.properties`：

```properties
sdk.dir=/path/to/Android/Sdk
```

### 2. 功能验证

1. 创建两个助手：
   - **总管**：选一个支持工具的模型，系统提示词里写清楚“你是协调者，需要把子任务分派给其他助手，并汇总结果”。
   - **写手**：独立系统提示词/模型，例如“你擅长写科幻小说开头”。
2. 在总管会话中发送：

   > 请让写手写一段 300 字左右的科幻小说开头，然后把写手的结果原样返回给我。

3. 预期：
   - 总管发起 `delegate_task` 工具调用，参数里带写手的 `assistant_id`。
   - 工具返回 JSON，`status` 为 `completed`，`result` 是写手正文。
   - 总管继续回复，汇总写手结果。
4. 打开 **写手** 助手的历史，应该能看到一条标题为 `子任务：...` 的会话，里面包含：
   - 总管写的子任务 prompt
   - 写手的完整回复

### 3. 边界验证

- 传不存在的 `assistant_id`：工具返回错误输出。
- 目标助手没有配置聊天模型：返回 `status = failed`。
- 在子会话里再要求它分派：子会话没有 `delegate_task` 工具，不会递归。
- 目标助手启用了需要审批的工具：返回 `status = awaiting_approval`，打开子会话可继续审批。
- 子任务过长/模型死循环：达到 32 步或 10 分钟后返回 `step_limit_reached` / `failed`。

### 4. 本次环境的验证情况

- **Android 编译无法在当前容器完成**：
  - 环境没有安装 Android SDK，`:app` 配置阶段报 `SDK location not found`。
  - 容器内存约 8GB，`org.gradle.jvmargs=-Xmx8g` 的 Gradle daemon 被系统杀掉；降到 2GB 仍被 OOM。
- 已做替代校验：
  - 用 Gradle 自带的 Kotlin 编译器对 `AssistantDelegationService.kt` 和 `ChatToolFactory.kt` 做语法解析：**没有 `expecting` / `syntax` / `unexpected token` 等语法错误**；只有缺少项目 classpath/Android SDK 导致的 `unresolved reference`。
  - 单独编译并运行了 `JsonInstantPretty.encodeToString(buildJsonObject { ... })` 的等价代码，输出符合预期。
  - `git diff --check` 通过。

---

## 已知限制 / 后续可扩展

- 工具目前对**所有助手**可见，没有助手级开关；下一步可以给 `Assistant` 加字段，或增加 `LocalToolOption` + 设置页开关。
- 同步串行执行：一次工具调用里多个 `delegate_task` 会依次执行；暂不支持并行、实时进度、`postNotice` 异步通知和任务看板。
- 子任务失败时不落库；只有正常完成或等待审批时才保存子会话。
- 子会话不继承总管会话的 `workspaceCwd`，只按目标助手自己的 workspace 配置工作。
- 子任务需要工具审批时，总管只能拿到 `awaiting_approval` 状态，需要用户打开子会话手动审批。
