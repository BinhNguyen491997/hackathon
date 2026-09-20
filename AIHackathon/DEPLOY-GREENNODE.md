# Work Agent — build & deploy lên GreenNode

Agent hỏi đáp hỗ trợ công việc: Spring Boot + Spring AI, có tool calling (quản lý task, ngày giờ,
đếm ngày làm việc), bộ nhớ hội thoại, HTTP API và client gọi từ máy cá nhân.

```
┌─ Máy cá nhân ─┐        HTTPS + X-API-Key        ┌─ GreenNode ────────────────┐
│  client/       │ ─────────────────────────────► │  Work Agent (container)    │
│  agent.ps1     │        POST /api/chat          │  Spring Boot + Spring AI   │
└────────────────┘ ◄───────────────────────────── │        │ OpenAI-compatible │
                          reply JSON              │        ▼                  │
                                                  │  GreenNode MaaS (LLM)     │
                                                  └────────────────────────────┘
```

## 0. Thành phần trong repo

| File | Vai trò |
|---|---|
| `config/AgentConfig.java` | Lắp agent: model + system prompt + memory + tool |
| `config/LlmStartupCheck.java` | Kiểm tra endpoint/key/model lúc startup, fail fast nếu base-url thiếu `/v1` |
| `memory/SummarizingChatMemory.java` | Bộ nhớ hội thoại: strip `reasoning_content`, nén phần cũ thành 1 tóm tắt |
| `memory/ChatModelSummarizer.java` | Gọi model tóm tắt phần lịch sử bị nén |
| `agent/AgentService.java` | Gắn `conversationId` vào memory và vào `ToolContext` |
| `tools/WorkTools.java` | Các `@Tool` model được gọi (task, ngày giờ, ngày làm việc) |
| `web/ChatController.java` | `POST /api/chat`, `POST /api/chat/stream` (SSE), `GET /api/health` |
| `web/ApiKeyFilter.java` | Chặn `/api/**` bằng header `X-API-Key` |
| `client/agent.ps1` | Client hỏi đáp từ Windows |
| `Dockerfile` | Đóng gói để deploy |

## 1. Chạy local trước khi lên cloud

```powershell
$env:LLM_BASE_URL = "https://maas-llm-aiplatform-hcm.api.vngcloud.vn/v1"   # PHẢI có "/v1"
$env:LLM_API_KEY  = "<greennode-maas-api-key>"
$env:LLM_MODEL    = "z-ai/glm-5.2-hackathon"
$env:AGENT_API_KEY = "secret123"                      # secret client phải gửi kèm

mvn -DskipTests package
java -jar target\AIHackathon-0.0.1-SNAPSHOT.jar
```

Cửa sổ khác:

```powershell
.\client\agent.ps1 -ApiKey secret123
```

Chạy test: `mvn test`.

**Chạy từ IDE:** biến `$env:` của PowerShell không truyền vào IntelliJ. Hai cách:
`Run → Edit Configurations → Environment variables`, hoặc tạo file `config/application.yml` ở gốc
project (Spring Boot tự nạp, `.gitignore` đã bỏ qua `config/`):

```yaml
spring:
  ai:
    openai:
      api-key: "<key>"
agent:
  api-key: "secret123"
```

Thiếu `LLM_API_KEY` thì app **fail fast** với `IllegalStateException` từ `LlmStartupCheck` — đó là
hành vi cố ý, không phải bug.

## 2. Lấy LLM từ GreenNode MaaS

GreenNode Model-as-a-Service expose **API tương thích OpenAI**, nên project này không cần sửa code —
`application.yml` đã trỏ sẵn vào MaaS, bạn chỉ cần key (và model id nếu muốn đổi).

1. Đăng nhập GreenNode portal → phần **Model as a Service** → tạo **API key**.
2. Base URL: `LLM_BASE_URL=https://maas-llm-aiplatform-hcm.api.vngcloud.vn/v1` — **phải có `/v1`**.
   (MaaS được serve trên domain VNG Cloud; region khác thì đổi phần `-hcm`.)
   Spring AI 2.x gọi model qua OpenAI Java SDK chính thức; SDK nối thẳng `chat/completions` vào
   base-url (default của nó là `https://api.openai.com/v1`). Thiếu `/v1` là 404 — app sẽ
   **fail fast** ngay lúc startup (`LlmStartupCheck`) thay vì để lỗi lộ ra khi user hỏi.
   Dùng `https`, không dùng `http`, vì `http` truyền API key dạng plaintext.
3. Model id — MaaS hỗ trợ các họ GLM, DeepSeek, Llama, Qwen:

```powershell
curl.exe -H "Authorization: Bearer $env:LLM_API_KEY" "$env:LLM_BASE_URL/models"
```

4. Đặt `LLM_MODEL` = id lấy được (mặc định trong repo: `z-ai/glm-5.2-hackathon`).
   Api-key để trống sẽ bật "no-auth mode" của SDK (bỏ header `Authorization`) → 401, nên app cũng
   fail fast trong trường hợp đó.

Các biến tinh chỉnh model (đều có default trong `application.yml`): `LLM_TEMPERATURE` (0.3),
`LLM_TOP_P` (0.95), `LLM_MAX_TOKENS` (4096 — GLM là model reasoning, token suy luận cũng tính vào
`completion_tokens` nên đừng đặt quá thấp).

Test nhanh endpoint trước khi chạy app:

```powershell
$body = @{
  model    = $env:LLM_MODEL
  messages = @(@{ role = "user"; content = "ping" })
} | ConvertTo-Json -Depth 5
Invoke-RestMethod -Method Post -Uri "$env:LLM_BASE_URL/chat/completions" `
  -Headers @{ Authorization = "Bearer $env:LLM_API_KEY" } `
  -ContentType "application/json; charset=utf-8" -Body $body |
  ForEach-Object { $_.choices[0].message.content }
```

**Quan trọng:** agent này dựa vào *function calling*. Hãy chọn model có hỗ trợ tool calling
(MaaS có tài liệu riêng về function calling). Nếu model không hỗ trợ, agent vẫn trả lời được
nhưng sẽ không gọi được tool.

GLM trả thêm field `reasoning_content` (không có trong API OpenAI). Spring AI 2.x đọc field này và
đưa vào metadata `reasoningContent` của response, nên không gây lỗi parse —
`GreenNodeApiCompatibilityTests` khẳng định điều đó bằng một server giả trả đúng mẫu response của
MaaS.

## 3. Đóng gói image

```powershell
docker build -t work-agent:1.0 .
docker run --rm -p 8080:8080 `
  -e LLM_BASE_URL="https://maas-llm-aiplatform-hcm.api.vngcloud.vn/v1" `
  -e LLM_API_KEY="<key>" -e LLM_MODEL="z-ai/glm-5.2-hackathon" `
  -e AGENT_API_KEY="secret123" work-agent:1.0
```

Push lên registry mà GreenNode đọc được (Docker Hub hoặc private container registry của GreenNode):

```powershell
docker tag work-agent:1.0 <registry>/<namespace>/work-agent:1.0
docker push <registry>/<namespace>/work-agent:1.0
```

## 4. Deploy trên GreenNode — chọn 1 trong 3

### A. AgentBase Runtime (đúng mục đích nhất cho cuộc thi)

AgentBase là runtime container chuyên cho AI agent: bạn đưa Docker image, nó chạy và expose HTTP
endpoint, kèm RBAC/observability sẵn.

1. Portal → **AgentBase** → tạo Agent Runtime mới.
2. Nguồn: container image đã push ở bước 3.
3. Port: `8080`. Health check: `/api/health`.
4. Env: `LLM_BASE_URL`, `LLM_API_KEY`, `LLM_MODEL`, `AGENT_API_KEY`.
5. Deploy → lấy public endpoint dạng `https://<ten>.<domain-agentbase>`.

### B. Cloud Server (VM) + Docker — kiểm soát nhiều nhất

1. Tạo Cloud Server (Ubuntu, 2 vCPU / 4 GB là đủ cho agent Java).
2. Gắn floating IP, security group **chỉ** mở 22 (SSH) và 443.
3. SSH vào, cài Docker, `docker run` như bước 3 (thêm `--restart unless-stopped`).
4. Đặt Nginx/Caddy phía trước để có HTTPS (Let's Encrypt), proxy về `127.0.0.1:8080`.
   Đừng expose 8080 trực tiếp ra internet.

### C. Notebook + Expose ports — nhanh nhất để demo

AI Platform Notebook có chức năng expose port ra ngoài; port public thường **khác** port trong
container, nhớ lấy đúng URL/port mà portal cấp. Cách này phù hợp demo, không phù hợp production.

## 5. Gọi từ máy cá nhân

```powershell
# hỏi 1 câu
.\client\agent.ps1 -Url https://<endpoint-greennode> -ApiKey "secret123" -Message "Tuần này tôi còn việc gì chưa xong?"

# hội thoại liên tục (nhớ ngữ cảnh)
$env:AGENT_URL = "https://<endpoint-greennode>"
$env:AGENT_API_KEY = "secret123"
.\client\agent.ps1
```

Hoặc curl:

```powershell
curl.exe -X POST https://<endpoint>/api/chat -H "Content-Type: application/json" -H "X-API-Key: secret123" -d "{\"message\":\"tao task viet slide, han 2026-09-12, uu tien HIGH\"}"
```

`conversationId` là chìa khoá của bộ nhớ: giữ nguyên giá trị để agent nhớ ngữ cảnh, đổi giá trị
để bắt đầu hội thoại mới. `agent.ps1` tự xử lý việc này.

### Bộ nhớ hội thoại và chi phí token

API chat/completions là stateless: mỗi lượt phải gửi lại lịch sử, nên `prompt_tokens` tăng theo số
lượt. `SummarizingChatMemory` cắt hai nguồn phí lớn nhất:

| Property | Default | Ý nghĩa |
|---|---|---|
| `agent.memory.strip-reasoning` | `true` | Bỏ `reasoning_content` của GLM khỏi history. Spring AI mặc định **gửi lại** field này ở lượt sau; response mẫu của MaaS tốn 699 reasoning token/lượt, gửi lại là trả tiền cho thứ vô ích |
| `agent.memory.window` | `40` | Vượt số message này thì nén |
| `agent.memory.keep-recent` | `12` | Số message gần nhất giữ nguyên văn; phần cũ hơn thành 1 message tóm tắt |
| `agent.memory.summarize` | `true` | `false` = chỉ cắt cửa sổ trượt, không tốn thêm 1 lần gọi model |
| `agent.memory.summary-max-words` | `200` | Độ dài tối đa của bản tóm tắt |

Đánh đổi: mỗi lần nén tốn thêm một lần gọi model (rẻ hơn nhiều so với việc gửi lại toàn bộ lịch sử
ở mọi lượt sau đó). Tóm tắt lỗi thì tự rơi về cắt cửa sổ, không làm gãy lượt hỏi đáp.

Prompt cache: system prompt là hằng số và bản tóm tắt được lưu dưới dạng user message, nên system
prompt luôn nằm đầu prompt — prefix ổn định để MaaS ăn cache (`prompt_tokens_details.cached_tokens`).

## 6. Bảo mật — đừng bỏ qua khi lên internet

- **Luôn đặt `AGENT_API_KEY`.** Để trống là API mở cho mọi người, ai biết URL cũng tiêu được quota LLM của bạn. App sẽ log warning khi để trống.
- Chỉ chạy sau HTTPS. `X-API-Key` qua HTTP là gửi secret dạng rõ.
- Không commit API key. Truyền qua env/secret của GreenNode.
- Nếu dùng Model Endpoint của GreenNode, bật thêm **Whitelist IP** (CIDR) cho chắc.
- Bộ nhớ hội thoại và task hiện lưu **in-memory** → restart là mất. Muốn giữ, đổi `TaskStore` sang JPA/Redis và dùng `spring-ai-starter-model-chat-memory-repository-jdbc`.

## 7. Nâng cấp để ăn điểm thi

| Hướng | Việc cần làm |
|---|---|
| RAG trên tài liệu nội bộ | Thêm vector store (PGVector/Qdrant) + `QuestionAnswerAdvisor`, nạp tài liệu công ty |
| Kết nối hệ thống thật | Viết thêm `@Tool` gọi Jira/Google Calendar/email nội bộ |
| MCP | Thêm `spring-ai-starter-mcp-client` để agent dùng tool từ MCP server ngoài |
| Streaming UI | Dùng `/api/chat/stream` (SSE) cho web UI chữ chạy dần |
| Quan sát & chi phí | Actuator + Micrometer: đếm token, latency, số lần gọi tool |
| Đa người dùng | Thay `X-API-Key` bằng OIDC/JWT, `conversationId` sinh từ user id |

---

# 8. Sổ tay phiên làm việc — 13/09/2026

Phần này ghi lại toàn bộ nội dung đã làm và đã bàn trong ngày, để mai mở ra là tiếp tục được ngay.

## 8.1. Trạng thái: agent đã chạy thật với GreenNode MaaS

Đã verify bằng curl vào instance chạy ở `localhost:8080`:

```powershell
curl.exe http://localhost:8080/api/health
# {"status":"UP"}
```

Lượt 1 — `{"conversationId":"conv-demo","message":"Tạo task viết slide, hạn 2026-09-15, ưu tiên cao"}`:

```json
{"conversationId":"conv-demo","reply":"Đã tạo xong! ✅\n\n- **Task #1**: Viết slide\n- **Ưu tiên**: Cao (HIGH)\n- **Hạn chót**: 15/09/2026\n- **Trạng thái**: Chưa xong (OPEN)"}
```

Lượt 2 — cùng `conversationId`, `"Còn mấy ngày làm việc nữa tới hạn của nó?"`:

```json
{"conversationId":"conv-demo","reply":"Hôm nay là **Chủ nhật 13/09/2026**, hạn chót là **15/09/2026**.\n\nCòn **2 ngày làm việc** tới hạn (Thứ Hai 14/09 và Thứ Ba 15/09). Gấp rồi, nên bắt tay vào làm sớm nhé!"}
```

Ba kết luận từ 2 lượt này:

- `z-ai/glm-5.2-hackathon` **có hỗ trợ function calling** (gọi `createTask`, rồi `getCurrentDateTime` + `countWorkingDays`).
- Bộ nhớ hội thoại hoạt động: hiểu "nó" = task #1 của lượt trước.
- Ngày giờ lấy từ tool, không bịa.

## 8.2. Phát hiện quan trọng nhất: Spring AI 2.0 dùng OpenAI Java SDK chính thức

Đọc source `spring-ai-openai-2.0.1-sources.jar` trong `D:\Maven\repository` mới thấy 2.x **bỏ hẳn**
`OpenAiApi` của 1.x, chuyển sang `com.openai:openai-java`. Ba hệ quả:

| Điều | Chi tiết |
|---|---|
| `base-url` phải kèm `/v1` | `OpenAiSetup.OPENAI_URL = "https://api.openai.com/v1"`, và `calculateBaseUrl()` trả base-url y nguyên rồi SDK nối `chat/completions` vào sau |
| `spring.ai.openai.chat.completions-path` **không tồn tại** | `OpenAiChatProperties` không có field này (khác 1.x). Nếu khai vào yml thì là config chết |
| api-key rỗng = "no-auth mode" | `OpenAiSetup` cài interceptor **xoá hẳn** header `Authorization` → endpoint trả 401 chứ không phải lỗi rõ ràng |

Endpoint thật của MaaS: `https://maas-llm-aiplatform-hcm.api.vngcloud.vn/v1` (không phải
`maas.greennode.ai`). `LlmStartupCheck.MAAS_HOSTS` nhận diện cả hai domain.

Về `reasoning_content` của GLM: `OpenAiChatModel.getReasoningContent()` đọc nó từ
`_additionalProperties` và đưa vào metadata `reasoningContent`; ở lượt sau, dòng
`builder.putAdditionalProperty("reasoning_content", ...)` **gửi lại** field đó. Đây là lý do phải
strip trong memory.

Về thứ tự advisor: `Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER` = `HIGHEST_PRECEDENCE + 200`,
`ToolCallingAdvisor.DEFAULT_ORDER` = `+300` → **memory nằm ngoài vòng lặp tool**, đọc/ghi 1 lần cho
cả lượt. Vòng lặp tool nằm trong `ToolCallingAdvisor` (`do...while (isToolCall)`), gọi
`ToolCallingManager.executeToolCalls`.

## 8.3. Request body agent gửi lên MaaS

```json
{
  "messages": [
    { "role": "system",    "content": "SYSTEM_PROMPT trong AgentConfig (hằng số)" },
    { "role": "user",      "content": "[Bối cảnh đã tóm tắt từ các lượt trước]\n- ..." },
    { "role": "user",      "content": "câu hỏi lượt trước" },
    { "role": "assistant", "content": "trả lời lượt trước" },
    { "role": "user",      "content": "câu hỏi mới" }
  ],
  "model": "z-ai/glm-5.2-hackathon",
  "max_tokens": 4096, "temperature": 0.3, "top_p": 0.95,
  "tools": [ "5 JSON schema sinh từ @Tool: getCurrentDateTime, createTask, listTasks, completeTask, countWorkingDays" ]
}
```

Khi model gọi tool, request vòng sau có thêm `assistant{tool_calls}` và `tool{tool_call_id, kết quả}`.
Request tóm tắt của `ChatModelSummarizer` thì **không kèm `tools`**.

Không còn `reasoning_content` trong history (đã strip), và các field null (`n`, `stop`, `tool_choice`,
`parallel_tool_calls`, `prompt_cache_key`...) bị SDK bỏ khỏi body.

## 8.4. `tools` có tác dụng gì — model KHÔNG tự gọi được

`tools` chỉ là **mô tả bằng text** (tên + description + JSON schema). MaaS không có đường mạng tới
service này và không chạy code. Nó chỉ trả về ý định:

```json
{"choices":[{"message":{"role":"assistant","content":null,
  "tool_calls":[{"id":"call_1","type":"function",
    "function":{"name":"createTask","arguments":"{\"title\":\"viết slide\"}"}}]},
  "finish_reason":"tool_calls"}]}
```

Phần chạy thật nằm trong JVM của agent: `ToolCallingAdvisor` → `ToolCallingManager.executeToolCalls`
→ invoke method Java → gửi kết quả lên trong request tiếp theo. Ví dụ "còn mấy ngày tới deadline"
có thể tốn 4 request: `getCurrentDateTime` → `listTasks` → `countWorkingDays` → câu trả lời.

Ghi nhớ: `tools` = **thực đơn**, `tool_calls` = **gọi món**, **bếp là service của bạn**.
Model chỉ đề nghị; validate/từ chối/log đều ở phía mình. `owner` đi qua `ToolContext` nên model
không thấy và không sửa được → không đọc được dữ liệu người khác.

## 8.5. Context và token: cách tiết kiệm

API chat/completions **stateless** — muốn model nhớ gì thì phải gửi lại thứ đó trong `messages`.
Không có cách tham chiếu `id` của response cũ. Bốn lớp giải pháp mà các hệ thống lớn dùng:

| Lớp | Bản chất | Có giảm token gửi lên? |
|---|---|---|
| Prompt caching / KV cache | Prefix giống hệt thì server tái dùng KV state; OpenAI công bố giảm tới 90% giá input token và 80% TTFT, ngưỡng 1024 token prefix | **Không** — vẫn gửi đủ, chỉ rẻ hơn |
| State phía server (`previous_response_id`) | Server tự dựng lại history | **Không** — tài liệu Azure OpenAI ghi rõ vẫn tính đủ input token |
| Compaction / tóm tắt | OpenAI có `context_management: [{type: compaction, compact_threshold}]` và `/responses/compact`, nén lượt cũ thành item mã hoá | **Có** |
| Memory dạng retrieval (ChatGPT) | "Saved memories" + "reference chat history": chỉ lôi đoạn liên quan vào prompt | **Có** |

Agent này đang dùng lớp 3 (`SummarizingChatMemory`) + tận dụng lớp 1 (prefix ổn định: system prompt
và tools cố định ở đầu, tóm tắt lưu dưới dạng **user message** để không đẩy system prompt khỏi vị
trí đầu).

## 8.6. Hai mô hình lấy dữ liệu cho model

**Pre-fetch (service tổng hợp trước)** — đúng 1 request, kiểm soát hoàn toàn, nhưng phải đoán trước
model cần gì. Đây là dạng curl bạn đã test thành công:

```json
{
  "model": "z-ai/glm-5.2-hackathon",
  "messages": [
    { "role": "system", "content": "...Chỉ trả lời dựa trên phần BỐI CẢNH được cung cấp..." },
    { "role": "user",   "content": "câu hỏi lượt trước" },
    { "role": "assistant", "content": "trả lời lượt trước" },
    { "role": "user",   "content": "BỐI CẢNH (do hệ thống cung cấp, không phải người dùng nói):\n\n[Thời gian] Hôm nay: ...\n\n[Task đang mở]\n- #1 \"viết slide\" | HIGH | hạn 2026-09-12 | OPEN | quá hạn 1 ngày" },
    { "role": "user",   "content": "Tôi nên làm gì trước, và có việc nào quá hạn không?" }
  ],
  "max_tokens": 4096, "temperature": 0.3, "top_p": 0.95
}
```

Bốn quy tắc khi làm pre-fetch:

1. Bối cảnh là **message riêng**, đừng nhồi vào system prompt (data động sẽ phá prefix cache).
2. Đặt bối cảnh **sát trước câu hỏi**, sau history. Phần càng động càng phải về cuối.
3. Ghi rõ "do hệ thống cung cấp, không phải người dùng nói" — phòng tuyến chống prompt injection khi
   bối cảnh chứa nội dung từ nguồn ngoài.
4. Bỏ `tools` thì model không gây tác động được nữa → thực tế nên giữ tool **ghi**
   (`createTask`, `completeTask`) và chỉ pre-fetch phần **đọc**.

**Tool calling (model tự yêu cầu)** — không cần đoán, nhưng mỗi lần cần thêm data là một round-trip
và toàn bộ messages tích luỹ được gửi lại.

Tiêu chí chọn: **dữ liệu câu nào cũng cần → pre-fetch; dữ liệu tuỳ câu hỏi → tool.** Thực tế nên
hỗn hợp.

## 8.7. Log đã thêm (theo từng giai đoạn)

```
--> POST /api/chat conversationId=conv-demo chars=45                                  ChatController
[conv-demo] ask: 45 ký tự                                                             AgentService
TOOL createTask(title='viết slide', dueDate=2026-09-15, priority=cao) owner=conv-demo  WorkTools
TOOL createTask -> #1 "viết slide" [HIGH/OPEN] hạn 2026-09-15                          WorkTools
[conv-demo] memory vượt 40 message -> nén 30 message cũ thành 1 tóm tắt, giữ 12        SummarizingChatMemory
tóm tắt 30 message (4210 ký tự) -> 380 ký tự trong 1840 ms                             ChatModelSummarizer
[conv-demo] ask xong sau 8241 ms | finishReason=STOP | reply 180 ký tự | token: prompt=31 completion=1345 total=1376
<-- POST /api/chat conversationId=conv-demo 8241 ms reply=180 chars                    ChatController
```

`AgentService` giờ dùng `.call().chatResponse()` (thay cho `.content()`) để lấy được **usage đã cộng
dồn qua tất cả vòng tool calling**. Đường stream log thêm TTFT, số chunk, và **stack trace đầy đủ**
khi lỗi.

Hai công tắc DEBUG trong `application.yml`:

```yaml
logging:
  level:
    com.example.aihackathon.memory: DEBUG                                    # số message memory mỗi lượt
    org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor: DEBUG    # dump toàn bộ prompt + response
```

`SimpleLoggerAdvisor` đã được gắn trong `AgentConfig`. Chạy `java -jar` trên Windows console thì
`chcp 65001` trước để tiếng Việt không bị mangle.

## 8.8. Test suite — 38 test, `mvn test` BUILD SUCCESS

| Test | Chứng minh điều gì |
|---|---|
| `GreenNodeApiCompatibilityTests` | Path `/v1/chat/completions`, header `Bearer`, body có model/max_tokens/top_p/temperature/tools; parse được response có `reasoning_content`; lượt 2 mang context nhưng **không** mang `reasoning_content` |
| `MemoryCompactionIntegrationTests` | Nén memory end-to-end: có request tóm tắt riêng (không kèm `tools`), lượt sau chỉ mang bản tóm tắt |
| `StreamingCompatibilityTests` | SSE của GLM (delta có `reasoning_content`, chunk cuối có `usage`), gọi HTTP thật vào `/api/chat/stream` → 200, và stream vẫn chạy khi model gọi tool giữa đường |
| `SummarizingChatMemoryTests` | Strip reasoning, nén, tóm tắt dồn qua nhiều lần, summarizer lỗi → fallback cắt cửa sổ |
| `ChatModelSummarizerTests` | Prompt tóm tắt đúng, transcript rỗng thì không gọi model |
| `LlmStartupCheckTests` | Fail fast khi base-url thiếu `/v1` hoặc thiếu key (cả 2 domain MaaS) |
| `WorkToolsTests`, `ChatControllerTests` | Nghiệp vụ tool và HTTP layer |

Diagram luồng: `docs/sequence-hoi-dap.puml` (đã cập nhật khớp code, verify bằng
`plantuml -failfast2 -checkonly`).

## 8.9. VIỆC CÒN DỞ — làm tiếp ngày mai

1. **BUG: `/api/chat/stream` trả 500.** Non-streaming chạy tốt. Đã thử tái hiện bằng 3 test với SSE
   stub (có `reasoning_content`, có `usage`, có tool call giữa stream) — **đều pass**, nên lỗi nằm ở
   payload thật của MaaS. Log mới đã in stack trace: chạy lại rồi lấy phần `Caused by:` trong console
   IntelliJ.
2. **Revoke API key cũ.** Key đã lộ trong chat và từng bị hardcode làm default trong
   `application.yml` (đã bỏ ra; `git log -S` xác nhận chưa vào commit nào). Tạo key mới.
3. **`AGENT_API_KEY` đang trống** → `/api/**` đang mở. Bắt buộc đặt trước khi deploy ra internet.
4. **Chưa deploy.** Chưa cài Docker trên máy. Ba hướng không cần Docker local: (A) Cloud Server +
   jar + systemd + Caddy, (B) build image **trên VM** rồi push registry cho AgentBase, (C) CI build
   image. Chi tiết ở mục 4; hướng A đủ để demo, hướng B nếu cần AgentBase.
5. **Process cũ giữ file:** node pid từ 10/09 chạy `target\fake-llm.js` giữ `target\fake-llm.err` làm
   `mvn clean` fail. Tắt nó, và chuyển script ra khỏi `target/` (clean sẽ xoá).
6. **Đo token thật** trước/sau khi strip reasoning + nén, dùng log `token: prompt=... total=...`.
7. **Cân nhắc `ContextAdvisor` pre-fetch** (mục 8.6): chèn ngày giờ + danh sách task đang mở vào
   prompt, giữ tool ghi → cắt được 1-2 round-trip mỗi lượt.
8. Memory và task vẫn **in-memory** → restart là mất.

## 8.10. Lệnh hay dùng

```powershell
mvn -B test                                   # 38 test
mvn -B -DskipTests package                    # ra target\AIHackathon-0.0.1-SNAPSHOT.jar (108 MB)
java -jar target\AIHackathon-0.0.1-SNAPSHOT.jar

# Postman: Import -> Raw text
curl --location 'http://localhost:8080/api/chat' `
  --header 'Content-Type: application/json' `
  --data '{"conversationId":"conv-demo","message":"Liệt kê task của tôi"}'

# curl trên PowerShell: đưa body ra file UTF-8 không BOM để tiếng Việt không hỏng
[IO.File]::WriteAllText("$PWD\chat.json", '{"conversationId":"conv-1","message":"..."}',
  (New-Object Text.UTF8Encoding $false))
curl.exe -s -X POST http://localhost:8080/api/chat `
  -H "Content-Type: application/json; charset=utf-8" --data-binary "@chat.json"
```
