# Pitch deck — API Flow Agent

Tài liệu này là **nội dung từng slide** để đưa vào PowerPoint/Google Slides. Mỗi slide có:

- **Phần lên slide** — chữ ngắn, người nghe đọc kịp trong 10 giây.
- **Ghi chú thuyết trình** — những gì bạn *nói*, không in lên màn.

Mọi con số trong deck này đã được **đo trên chính repo** (lệnh tái lập ở Phụ lục C). Chỉ còn 3 chỗ
`[CẦN ĐIỀN]` là thông tin đội và số liệu nội bộ của tổ chức bạn — không ai đo hộ được.

---

## Slide 1 — Bìa

# API Flow Agent

### Đọc source code Java/Spring, trả về đặc tả chức năng và sequence diagram — mỗi câu đều chỉ được tới `file:line` chứng minh

- Đội: `[CẦN ĐIỀN tên đội / thành viên]`
- Chạy trên: **GreenNode MaaS** (LLM) + **GreenNode AgentBase** (runtime) — đã deploy, đang ACTIVE
- Demo: `[CẦN ĐIỀN link/QR]`

> **Ghi chú (20 giây, nói chậm):** "Chúng tôi không làm chatbot trả lời câu hỏi về code. Chúng tôi
> làm công cụ *đọc code thay con người*. Và điểm khác biệt duy nhất đáng nhớ trong 5 phút tới: mỗi
> câu nó viết ra đều bị bắt buộc phải chỉ được về dòng code chứng minh — nếu không chỉ được, câu đó
> bị **xoá**, không phải bị gắn cảnh báo."

---

## Slide 2 — Vấn đề

# Không còn ai biết API đó thực sự làm gì

**Cảnh này xảy ra mỗi tuần:**

BA cần mô tả chức năng để viết yêu cầu thay đổi → tài liệu mới nhất là 3 năm trước → đi hỏi dev →
dev senior dừng việc, mở IDE, lần tay qua 10+ lớp, qua service → repository → stored procedure → trả
lời bằng miệng trong 2 giờ → **không ai ghi lại**. Tháng sau, người khác hỏi lại câu đó.

Ba hệ quả:

- **Thời gian của người giỏi nhất** bị tiêu vào việc kể lại code.
- **Impact analysis trước release** không ai dám kết luận: "sửa chỗ này ảnh hưởng gì?"
- **Onboarding** phụ thuộc vào việc có người rảnh để kèm.

`[CẦN ĐIỀN: 1 số liệu nội bộ — ví dụ "trung bình 4–6 giờ/API để BA lấy được mô tả luồng"]`

> **Ghi chú:** đây không phải nỗi đau "thiếu tài liệu". Nó là nỗi đau **kiến thức hệ thống bị khoá
> trong đầu 1–2 người**, và người đó cũng chính là người đang gánh việc quan trọng nhất. Đừng nói
> chung chung "legacy khó hiểu" — kể đúng một cảnh cụ thể, giám khảo nào làm nghề cũng thấy mình
> trong đó.

---

## Slide 3 — Vì sao các cách hiện có không giải quyết được

| Cách làm | Vì sao thất bại |
|---|---|
| Dán code vào ChatGPT/Copilot | Model **bịa** tên class, bịa thứ tự gọi, bịa cả tầng database. Sai mà nghe rất hợp lý → BA tin và mang đi viết yêu cầu |
| Viết tài liệu tay | Đúng vào ngày viết. Lạc hậu sau 2 sprint. Không ai bảo trì |
| Đọc code tay | Chính xác, nhưng tốn giờ dev senior và **không lặp lại được** |
| Công cụ vẽ diagram tự động | Vẽ được class diagram / dependency graph. Không trả lời được "luồng nghiệp vụ của API này là gì, ghi vào bảng nào, trả lỗi gì" |

> **Ghi chú — câu chốt của slide này:** "**Hallucination trong tài liệu kỹ thuật độc hơn là không có
> tài liệu.**" Không có tài liệu thì người ta đi hỏi. Có tài liệu sai thì người ta tin và làm theo.
> Đây là lý do toàn bộ kiến trúc của chúng tôi được thiết kế quanh một câu hỏi: *làm sao chặn model
> bịa bằng code chạy được, chứ không bằng lời nhắc trong prompt.*

---

## Slide 4 — Giải pháp

# Phân tích tĩnh làm phần **sự thật**. LLM chỉ làm phần **diễn giải**, và bị kiểm tra

**Đưa vào:** link repo GitLab + path API (`POST /api/orders`)

**Nhận về trong một lần gọi:**

1. **Sequence diagram** thật của luồng — PlantUML + Mermaid, dựng từ AST
2. **Tài liệu đặc tả hiện trạng** — dữ liệu bị tác động, quy tắc validation, điều kiện rẽ nhánh,
   nhánh lỗi, stored procedure, mã lỗi, phạm vi transaction
3. **Bảng dẫn chứng** — mỗi phát biểu → `file.java:dòng` + nguyên văn snippet
4. **Danh sách câu hỏi phải xác nhận với dev** — những chỗ đọc code **không kết luận được**, chia 3
   mức: *Phải xác nhận trước khi dùng tài liệu* / *Nên xác nhận* / *Làm rõ thêm*

> **Ghi chú:** dừng lại ở điểm 4. "Đây là thứ khác biệt nhất và cũng phản trực giác nhất: chúng tôi
> đầu tư công sức để công cụ **biết mình không biết gì**, thay vì lấp chỗ trống bằng văn hay. Một
> tài liệu tự động nói 'chỗ này tôi không kết luận được, hỏi dev' có giá trị hơn một tài liệu trôi
> chảy mà 5% là bịa — vì 5% đó bạn không biết nó nằm ở đâu."

---

## Slide 5 — Một request → bốn định dạng cho bốn người đọc

```bash
curl -X POST https://<agent>/api/analyze/spec.html \
  -H "Content-Type: application/json" \
  -d '{"repoUrl":"https://gitlab.com/team/core.git","branch":"master",
       "httpMethod":"POST","path":"/api/orders",
       "useAi":true,"crossCheckWithAi":true,"gitToken":"glpat-..."}'
```

| Định dạng | Người dùng | Vì sao cần |
|---|---|---|
| `spec.html` | BA | Mở bằng browser, **không cần cài gì** |
| `spec.md` | BA / PM | Dán thẳng vào GitLab wiki hoặc mô tả MR (GitLab tự render Mermaid) |
| `api-flow.puml` | Dev | Tải về file `.puml`, mở bằng plugin PlantUML |
| `evidence` (JSON) | Hệ thống khác | Dùng lại dẫn chứng có cấu trúc |

Chưa biết path? → `POST /api/analyze/endpoints` liệt kê toàn bộ endpoint của repo, hoặc tìm bằng
**mô tả nghiệp vụ** (`summary`) thay vì path.

> **Ghi chú:** nhấn rằng đây là cùng một nhân phân tích, chỉ khác renderer. "Chúng tôi không bắt BA
> học công cụ mới. Chúng tôi giao sản phẩm ở định dạng mà từng người **đã** dùng hằng ngày."

---

## Slide 6 — SLIDE QUAN TRỌNG NHẤT: bốn cơ chế chống bịa

**Đều là code chạy được và có test, không phải lời hứa trong prompt.**

**1. Tất định — call graph do parser dựng, không do model đoán**
JavaParser + symbol solver dựng call graph, kèm resolve interface → lớp hiện thực. Cùng một commit
luôn cho ra cùng một kết quả → **dùng được để diff diagram giữa hai lần release**.

**2. Model bị bịt mắt — nó không được xem source code khi viết tài liệu**
`SpecNarrator` chỉ nhận **danh sách dữ kiện** đã rút tất định, mỗi dữ kiện có mã `[E1]`, `[E2]`.
Model **không thấy source, không thấy `file:line`**. Ánh xạ `id → file:line` thuộc hoàn toàn về phía
tất định. Không có gì để bịa ngoài những gì được đưa.

**3. Kiểm tra truy vết — bản nháp sai bị LOẠI BỎ, không phải gắn cảnh báo**
`CitationValidator` bắt hai loại gian lận: (a) dẫn mã `[E99]` không tồn tại, (b) tự gõ
`Abc.java:42` — cả hai đều bị chặn bằng regex. Không đạt → model được đưa **đúng lỗi của nó** để
viết lại **một lần**. Lần hai vẫn sai → **phần văn xuôi bị xoá khỏi tài liệu**, chỉ còn phần tất
định. Cả bản đã cache cũng bị **kiểm tra lại**, nên cache không thể hạ chuẩn.

**4. Đối chiếu chéo — cho LLM đọc code độc lập rồi so với parser**
Tuỳ chọn `crossCheckWithAi`: `LlmFlowProposer` tự dựng call graph **mà không được xem kết quả
parser** (thấy trước là nó sẽ xác nhận lại parser, bước này mất hết giá trị). Sau đó báo **% đồng
thuận**, bảng "chỉ parser thấy", bảng "chỉ AI thấy".

> **Ghi chú:** "AI ở đây là **trợ lý bị giám sát**, không phải nguồn sự thật." Nếu giám khảo chỉ nhớ
> một slide, hãy để họ nhớ slide này. Chi tiết đáng nói thêm nếu có thời gian: % đồng thuận **không
> phải** "độ chính xác của AI" — cả hai bên đều có thể sai; nó là mức độ hai phương pháp độc lập cho
> ra cùng kết luận, và chỗ lệch là chỗ cần người xem.

---

## Slide 7 — Kiến trúc

```
Người dùng / BA
   │  REST (JSON · HTML · Markdown · PlantUML)   hoặc   chat tiếng Việt (10 tool)
   ▼
┌──────────────── Agent (Java 21 · Spring Boot 4.1.1 · Spring AI 2.0.1) ────────────────┐
│  ApiFlowController ─── ApiFlowAnalyzer                                                 │
│                            │                                                            │
│         ┌──────────────────┴──────────────────────┐                                    │
│   PHẦN TẤT ĐỊNH (luôn chạy)            PHẦN AI (tuỳ chọn, MẶC ĐỊNH TẮT)                │
│   GitRepoFetcher  (JGit shallow)       SpecNarrator    — viết văn xuôi, KHÔNG thấy code│
│   EndpointScanner                      LlmFlowProposer — đọc code, KHÔNG thấy parser   │
│   JavaSourceIndex (symbol solver)              │                                        │
│   CallFlowBuilder                      CitationValidator — chặn trích dẫn bịa          │
│   EvidenceCollector   (12 loại dữ kiện)  FlowComparator   — % đồng thuận               │
│   StoredProcedureScanner               AiResultCache      — khoá: commit+endpoint+model │
│   ErrorCodeScanner · UnresolvedLinker          │                                        │
│         └──────────────────┬──────────────────────┘                                    │
│              Renderers: HTML · Markdown · PlantUML · Mermaid                            │
└────────────────────────────┬───────────────────────────────────────────────────────────┘
                             ▼  OpenAI-compatible API
                   GreenNode MaaS — z-ai/glm-5.2-hackathon
```

Pin cứng: JGit `7.8.0` · JavaParser symbol-solver `3.28.2`

> **Ghi chú:** "Hai nhánh tách đôi **có chủ đích**. Tắt hẳn nhánh AI thì sản phẩm **vẫn dùng được** —
> mất phần văn xuôi, còn nguyên diagram, bảng dẫn chứng, bảng procedure, bảng mã lỗi và danh sách
> câu hỏi cho dev. Không có chỗ nào LLM là mắt xích bắt buộc. Đó cũng là lý do lỗi gọi model không
> làm sập request: nó được bắt riêng và tài liệu vẫn xuất bản."

---

## Slide 8 — Chiều sâu domain: thứ công cụ generic không có

- **Stored procedure & package database** — quét lời gọi procedure, gom theo package, vẽ database
  thành **participant riêng** trên sequence diagram. Với core bank (Way4), logic thật nằm trong
  procedure chứ không chỉ trong Java. Procedure gọi bằng **tên trần** không xác định được package
  thì **không bị đoán** vào package nào — nó thành một câu hỏi cho dev.
- **Mã lỗi theo endpoint** — không phải dump bảng error code của cả source (thường vài chục hằng,
  hầu hết không liên quan), mà chỉ những mã **endpoint này đi tới được**, kèm đường đi.
- **12 loại dữ kiện** được rút riêng: điểm vào API · phân quyền · trường đầu vào · quy tắc validation
  · quy tắc nghiệp vụ · điều kiện rẽ nhánh · truy cập dữ liệu · stored procedure · gọi ra ngoài ·
  phạm vi transaction · mã lỗi trả về · nhánh lỗi.
- **Tìm theo nghiệp vụ** — chưa biết path thì tìm bằng `@Operation(summary)`: "chi tiết đơn hàng".
- **Hàng rào chống nổ** — giới hạn file/độ sâu/số node; vượt hạn mức thì diagram bị cắt và **ghi rõ
  lý do trong legend**, không treo server, không im lặng cắt bớt.

> **Ghi chú:** đây là slide chứng minh "chúng tôi làm cho hệ thống tài chính **thật**", không phải
> demo trên todo-app. Nhấn cái chi tiết "tên trần không bị đoán package" — nó nhỏ nhưng cho thấy
> nguyên tắc *không đoán* được áp dụng đến tận chi tiết.

---

## Slide 9 — Đã chạy thật trên GreenNode, không phải slide ý tưởng

**Deploy trên AgentBase — đang ACTIVE:**

| | |
|---|---|
| Runtime | `work-agent` — Custom Agent, status **ACTIVE** |
| Image | Container Registry của AgentBase, `linux/amd64`, 522 MB |
| Flavor | `runtime-s2-general-4x8` (4 vCPU / 8 GB) |
| LLM | GreenNode MaaS `z-ai/glm-5.2-hackathon` qua API tương thích OpenAI |
| Kiểm chứng | `GET /health` → **200**; `POST /api/chat` → **200**, tool calling hoạt động đúng |

**Chất lượng kỹ thuật:**

| | |
|---|---|
| Code chạy được | **62 file / 10.753 dòng** Java (riêng module phân tích: **42 file / 8.870 dòng**) |
| Kiểm thử | **312 test · 29 test class · pass 100%** (63 file test / 4.983 dòng) |
| REST API | **12 endpoint nghiệp vụ** (JSON · HTML · Markdown · PlantUML) + health |
| Agent hội thoại | `/api/chat`, `/api/chat/stream` (SSE), **10 tool**, bộ nhớ tự nén |

> **Ghi chú:** "312 test pass là bằng chứng mạnh nhất về chất lượng trong một hackathon — phần lớn
> đội demo bằng happy path." Nêu 3 test tiêu biểu, vì chúng thể hiện *chúng tôi test đúng chỗ nguy
> hiểm*: `CitationValidatorTests` (bản nháp AI trích dẫn sai bị loại), `GitRepoFetcherAuthTests`
> (23 test — token không được lọt vào log/response, từ chối URL nhúng credential),
> `FlowCrossCheckTests` (đối chiếu chéo, kể cả khi LLM trả output sai cú pháp).

---

## Slide 10 — Kinh tế token: chế độ rẻ nhất là mặc định

- **`useAi=false` là mặc định** → không gọi model, **không tốn một token nào**. Người dùng phải chủ
  động bật phần tốn tiền.
- **`crossCheckWithAi` là cờ riêng**, cũng mặc định tắt — vì nó là cờ *đắt* (gửi nguyên văn source)
  **và** là cờ *rủi ro dữ liệu*. Không gộp hai quyết định khác bản chất vào một công tắc.
- **Cache theo `commit + endpoint + model`** → hỏi lại cùng một API trên cùng commit **không trả tiền
  lần hai**. Đổi model thì cache miss, không trả bản cũ.
- **Fail fast lúc startup** — `LlmStartupCheck` kiểm endpoint/key/model ngay khi khởi động (bắt cả
  lỗi base-url thiếu `/v1`), thay vì chết giữa buổi demo.

> **Ghi chú:** "Token LLM là tiền thật, và tiền đó ra khỏi ví ngay cả khi câu trả lời sai. Chúng tôi
> thiết kế để **mặc định không tốn gì**, và mỗi lần tốn tiền đều là một quyết định tường minh của
> người dùng." Đây là slide ăn điểm với giám khảo phía vận hành.

---

## Slide 11 — Bảo mật & quản trị dữ liệu

- **Source code không rời hạ tầng nếu bạn không cho.** Cờ gửi source cho model (`crossCheckWithAi`)
  **tách riêng** khỏi cờ viết văn xuôi — chế độ viết văn xuôi chỉ gửi *danh sách dữ kiện*, không gửi
  code. Mỗi lần gửi source đều ghi **log cảnh báo** kèm số ký tự.
- **Không có secret trong log.** Từ chối URL dạng `https://user:pass@host`; **không nhận token qua
  query param** (URL bị ghi vào access log); `toString()` của request và credential đều che giá trị —
  kể cả khi bean validation fail, vì message lỗi của Spring có chứa "rejected value".
- **Token GitLab theo từng người gọi** (`gitToken` trong body POST): server **không giữ credential
  nào**, quyền đọc repo đúng bằng quyền người gọi. Tắt được bằng cấu hình khi chuyển sang vault.
  Hỗ trợ cả PAT, deploy token, và tài khoản/mật khẩu.
- **Allow-list host** cho mọi lời gọi clone — áp dụng kể cả khi **LLM** là bên gọi tool.
- **Container chạy bằng user thường**, không phải root.

> **Ghi chú:** slide dành cho giám khảo phía doanh nghiệp. Với ngân hàng, câu hỏi đầu tiên luôn là
> "code của tôi có bị gửi ra ngoài không" — và câu trả lời phải là một **cờ cấu hình chỉ được ra**,
> không phải một lời cam kết. Nói thẳng phần chưa làm: bản demo đang bật chế độ mở để giám khảo thử
> không cần key; `ApiKeyFilter` (`X-API-Key`) đã có sẵn, production chỉ cần set `AGENT_API_KEY` và
> khai báo allow-list host — không phải build lại image.

---

## Slide 12 — Giá trị theo từng vai

| Vai | Trước | Sau |
|---|---|---|
| **BA** | Chờ dev 1–2 ngày để có mô tả luồng | Tự lấy đặc tả + sơ đồ trong vài phút |
| **Dev** | Bị ngắt việc để kể lại code | Chỉ trả lời đúng danh sách câu hỏi mà công cụ đã khoanh |
| **QA / Release** | "Không ai dám nói sửa chỗ này ảnh hưởng gì" | Diff diagram giữa hai commit để thấy luồng đã đổi ở đâu |
| **Người mới** | Phụ thuộc có người rảnh kèm | Đọc tài liệu sinh từ code, mỗi câu tra được về dòng gốc |
| **Tổ chức** | Tài liệu luôn lạc hậu | Tài liệu **sinh từ code**, nên không thể lạc hậu hơn code |

`[CẦN ĐIỀN: phép tính tiết kiệm — số API × số giờ/API × chi phí giờ, dùng số thật của hệ thống bạn]`

> **Ghi chú:** đừng bịa ROI. Một phép tính đơn giản dựa trên số API thật của hệ thống bạn đang làm sẽ
> thuyết phục hơn mọi biểu đồ. Và nhấn dòng "Dev": giá trị lớn nhất không phải *thay thế* dev, mà là
> **thu hẹp câu hỏi dành cho dev** từ "giải thích API này đi" xuống "xác nhận 4 điểm này".

---

## Slide 13 — Roadmap

**Ngắn hạn (đang làm được ngay)**
- `gitToken` chuyển vào vault/database thay vì truyền theo request
- Diff diagram giữa hai commit thành API chính thức (nhân tất định đã sẵn sàng cho việc này)
- Render ảnh diagram qua Kroki self-hosted, tuỳ chọn

**Trung hạn**
- Thêm ngôn ngữ: Kotlin, .NET — kiến trúc tách renderer khỏi scanner nên là **thêm một scanner**
- Tích hợp CI: mỗi MR tự đính kèm sơ đồ luồng **đã đổi**

**Dài hạn**
- Chỉ mục toàn hệ thống (cross-service): trả lời được "đổi bảng này thì API nào vỡ"

---

## Slide 14 — Kết

# Tài liệu không nên được *viết*. Nó nên được *sinh ra* — và phải chứng minh được mình đúng

- Sản phẩm **chạy thật**: 312 test pass, đã deploy trên GreenNode AgentBase, endpoint đang ACTIVE
- Kiến trúc mà **AI là thành phần bị giám sát**, không phải nguồn sự thật
- `[CẦN ĐIỀN: điều bạn muốn xin — mentor, quyền thử trên repo thật, suất triển khai thí điểm]`
- Liên hệ: `[CẦN ĐIỀN]`

> **Ghi chú kết (nói chậm, rồi dừng):** "Nếu các anh chị chỉ nhớ một câu: công cụ này được thiết kế
> để **thà nói 'tôi không biết' còn hơn nói một câu không chứng minh được**. Trong tài liệu kỹ thuật,
> đó là khác biệt giữa hữu ích và nguy hiểm."

---

# Phụ lục A — Kịch bản demo 3 phút

Chuẩn bị trước: 2 tab browser đã mở sẵn kết quả (đừng chờ LLM chạy live — bước đối chiếu chéo mất
vài phút), 1 terminal, 1 tab IDE mở repo legacy.

| Thời gian | Việc làm | Câu nói chốt |
|---|---|---|
| 0:00–0:20 | Tab IDE: mở một API, cuộn qua 5 lớp, dừng ở repository | "Đây là câu hỏi mà mỗi tuần dev phải trả lời vài lần bằng miệng." |
| 0:20–0:50 | Terminal: chạy `curl` một dòng | "Một request. Không ai phải đọc code." |
| 0:50–1:30 | Tab 1: `spec.html` — cuộn phần "Hệ thống đang làm gì" | "Đây là văn xuôi nghiệp vụ. Nhưng chú ý mấy cái mã trong ngoặc vuông." |
| 1:30–2:05 | Cuộn tới bảng dẫn chứng, chỉ một mã `[E7]` → `file:line` + snippet | "Mỗi câu đều chỉ được về dòng code. Model **không được phép** tự viết số dòng — nếu nó thử, bản viết bị loại." |
| 2:05–2:35 | Mục "Cần xác nhận với developer" | "Và đây là chỗ nó nói thẳng: những điều này đọc code không kết luận được. Đây là danh sách BA mang đi họp với dev." |
| 2:35–3:00 | Tab 2: bật `crossCheckWithAi`, chỉ **% đồng thuận** + bảng lệch | "Khi cần chắc hơn, chúng tôi cho AI đọc code độc lập rồi đối chiếu với parser, và chỉ ra đúng chỗ hai bên không đồng ý." |

**Nếu chỉ có 90 giây:** bỏ 0:00–0:20 và 2:35–3:00. Giữ bằng mọi giá: bảng dẫn chứng và mục "Cần xác
nhận với developer".

**Kế hoạch dự phòng:** mạng/LLM chết → chạy với `useAi=false`. Sản phẩm vẫn ra diagram + dẫn chứng +
câu hỏi cho dev. Nói luôn ra: "phần vừa mất là phần văn xuôi; đây chính là lý do chúng tôi không để
LLM làm mắt xích bắt buộc."

---

# Phụ lục B — Câu hỏi khó và cách trả lời

**"Khác gì gọi ChatGPT dán code vào?"**
Ba khác biệt kiểm chứng được. (1) Call graph do JavaParser + symbol solver dựng, model không tham
gia. (2) Khi viết tài liệu, model **không được xem source code** — nó chỉ thấy danh sách dữ kiện có
mã, nên không có nguyên liệu để bịa. (3) Bản viết của nó bị validate; trích dẫn sai thì bị loại bỏ,
không phải được đăng kèm cảnh báo. Và bỏ hẳn model đi thì sản phẩm vẫn chạy.

**"Độ chính xác bao nhiêu phần trăm?"**
Phần tất định không có khái niệm "độ chính xác %" — nó đọc AST: hoặc resolve được, hoặc báo là chưa
resolve được. Những chỗ không resolve được (thiếu jar, gọi qua reflection, interface có nhiều
implementation) đều được **liệt kê tường minh** chứ không bị lấp. Con số % duy nhất chúng tôi báo là
**mức đồng thuận** giữa parser và LLM ở bước đối chiếu, và nó không phải "độ chính xác của AI" — cả
hai bên đều có thể sai; nó chỉ cho biết còn bao nhiêu chỗ cần người xem.
`[CẦN ĐIỀN: kết quả chạy trên 1 repo thật — số endpoint quét được, số điểm chưa resolve]`

**"Repo private / repo nội bộ thì sao?"**
Token của chính người gọi truyền trong body POST (không bao giờ qua URL hay query param), hoặc cấu
hình phía server, hoặc deploy token chỉ-đọc phạm vi 1 project. Có allow-list host để chặn clone ra
ngoài phạm vi cho phép. Bản deploy nội bộ thì không có gì rời khỏi mạng của bạn ngoài phần gọi LLM —
và phần đó cũng tắt được.

**"Sao không render ảnh diagram luôn cho tiện?"**
Ba lựa chọn đều có giá, nên chúng tôi để người dùng chọn thay vì quyết hộ: nhúng thư viện PlantUML
kéo theo ràng buộc GPL; gọi `plantuml.com` là gửi cấu trúc code nội bộ ra server bên thứ ba; dựng
Kroki riêng là thêm một thành phần phải vận hành. Bản HTML nhúng nguyên văn mã sơ đồ kèm hướng dẫn
xem. Kroki self-hosted đã nằm trong roadmap ngắn hạn.

**"Mở rộng sang ngôn ngữ/hệ thống khác được không?"**
Hiện tối ưu cho Java/Spring vì đó là nơi nỗi đau lớn nhất trong hệ thống core. Kiến trúc tách
renderer khỏi scanner, nên thêm ngôn ngữ là thêm một scanner chứ không phải viết lại.

**"Monolith 5.000 file thì có treo không?"**
Không. Có hàng rào cứng về số file, độ sâu đệ quy và số node. Vượt hạn mức thì diagram bị cắt và
**ghi rõ lý do trong legend** — nguyên tắc là thà trả về kết quả không đầy đủ *và nói rõ là không
đầy đủ*, hơn là treo hoặc im lặng cắt bớt.

**"Tại sao đội dùng format `STEP A.m -> B.n` thay vì JSON cho output của LLM?"**
(Câu này giám khảo kỹ thuật hay hỏi.) Vì LLM rất hay làm sai cấu trúc JSON — thiếu dấu phẩy, bọc
trong markdown fence, thêm lời dẫn — và **một ký tự sai là mất toàn bộ kết quả**. Với định dạng theo
dòng, dòng nào hỏng thì bỏ đúng dòng đó và đếm lại để biết chất lượng output. Đây là kiểu quyết định
thiết kế mà chỉ ai từng chạy LLM trong production mới gặp.

---

# Phụ lục C — Lệnh tái lập mọi số liệu trong deck

Chạy ở gốc project. Đừng để số nào lên slide mà bạn không tự chạy lại được — giám khảo hỏi lại là
mất điểm.

```powershell
# 62 file / 10.753 dòng main; 63 file / 4.983 dòng test
Get-ChildItem -Recurse -Filter *.java src\main | Get-Content | Measure-Object -Line
Get-ChildItem -Recurse -Filter *.java src\test | Get-Content | Measure-Object -Line

# 42 file / 8.870 dòng module phân tích
Get-ChildItem -Recurse -Filter *.java src\main\java\com\example\aihackathon\codeanalysis |
  Get-Content | Measure-Object -Line

# 312 test, 0 failure
mvn -B test        # → [INFO] Tests run: 312, Failures: 0, Errors: 0, Skipped: 0

# Runtime đang ACTIVE trên AgentBase + endpoint URL
bash .kiro/skills/agentbase/scripts/runtime.sh list
bash .kiro/skills/agentbase/scripts/runtime.sh endpoints list <runtime-id>

# Health của bản đã deploy
curl -s -o /dev/null -w "%{http_code}" https://<endpoint>/health    # → 200
```
