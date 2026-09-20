package com.example.aihackathon.codeanalysis;

import java.nio.file.Path;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình cho phần phân tích source code (prefix {@code analysis} trong application.yml).
 *
 * <p>Các hạn mức ở đây là hàng rào chống nổ context/bộ nhớ khi gặp monolith lớn: thà trả
 * một diagram bị cắt và ghi rõ "đã đạt giới hạn" còn hơn treo server.
 */
@ConfigurationProperties(prefix = "analysis")
public class AnalysisProperties {

    /** Thư mục chứa các bản clone tạm. Mỗi repo+branch một thư mục con. */
    private Path workspace = Path.of(System.getProperty("java.io.tmpdir"), "agent-code-analysis");

    /** Nơi ghi file .puml sinh ra, để trả về đường dẫn cho người dùng. */
    private Path outputDir = Path.of(System.getProperty("java.io.tmpdir"), "agent-code-analysis", "diagrams");

    /** Số file .java tối đa được parse trong một repo. */
    private int maxSourceFiles = 4000;

    /** Độ sâu tối đa khi lần theo call graph (controller = 0). */
    private int maxDepth = 6;

    /** Số node tối đa trên diagram, tính cả node điều khiển (alt/loop/...). */
    private int maxNodes = 400;

    /** Số endpoint tối đa trả về khi liệt kê. */
    private int maxEndpointsListed = 200;

    /**
     * Có đi sâu vào các lớp phụ trợ (Mapper, Validator, Util, Converter) hay không.
     * Mặc định false: hiện lời gọi nhưng không mở rộng bên trong, để diagram còn đọc được.
     */
    private boolean expandSupportTypes = false;

    /** Hiện cả mũi tên trả về của những lời gọi không có lời gọi con. */
    private boolean showLeafReturns = false;

    private final Git git = new Git();

    private final Report report = new Report();

    private final Ai ai = new Ai();

    private final Database database = new Database();

    /**
     * Hệ quản trị dữ liệu mà tầng repository nói chuyện với.
     *
     * <p>Phân tích tĩnh chỉ thấy được "gọi vào OrderRepository.save()"; việc repository đó thực ra
     * đọc/ghi vào database nào là kiến thức của hệ thống, không nằm trong code. Khai báo ở đây để
     * tài liệu nói đúng ngôn ngữ nghiệp vụ thay vì chỉ nêu tên class.
     */
    public static class Database {

        /** Tên database hiện trong tài liệu và sơ đồ. Đổi theo hệ thống của bạn. */
        private String name = "Way4";

        /** Có vẽ database thành một participant riêng trên sequence diagram hay không. */
        private boolean showInDiagram = true;

        public String getName() {
            return this.name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public boolean isShowInDiagram() {
            return this.showInDiagram;
        }

        public void setShowInDiagram(boolean showInDiagram) {
            this.showInDiagram = showInDiagram;
        }
    }

    public static class Ai {

        /**
         * Hạn mức ký tự source gửi cho LLM ở bước đối chiếu chéo.
         *
         * <p>Vừa là hàng rào token, vừa là hàng rào rủi ro: đây là lượng source code thật sẽ rời
         * khỏi hạ tầng của bạn mỗi lần chạy. Vượt hạn mức thì cắt và ghi rõ đã cắt bao nhiêu
         * method, để không ai tưởng LLM đã đọc hết luồng.
         */
        private int maxSourceChars = 40000;

        /**
         * Cache kết quả gọi LLM xuống đĩa, khoá theo commit.
         *
         * <p>Bật mặc định vì thứ được cache là <b>tiền</b>, không phải thời gian: hỏi lại cùng một
         * endpoint trên cùng commit mà không cache là trả tiền token lần nữa, và bước đối chiếu còn
         * gửi lại nguyên văn source ra ngoài hạ tầng lần nữa.
         */
        private boolean cache = true;

        /**
         * Model id đưa vào khoá cache.
         *
         * <p>PHẢI nằm trong khoá: đổi model mà vẫn trả bản cache cũ là trả sai nguồn gốc. Mặc định
         * lấy thẳng từ {@code spring.ai.openai.chat.model} nên không cần khai báo lại.
         */
        private String model = "";

        public int getMaxSourceChars() {
            return this.maxSourceChars;
        }

        public void setMaxSourceChars(int maxSourceChars) {
            this.maxSourceChars = maxSourceChars;
        }

        public boolean isCache() {
            return this.cache;
        }

        public void setCache(boolean cache) {
            this.cache = cache;
        }

        public String getModel() {
            return this.model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }

    public static class Report {

        /**
         * Nguồn mermaid.js cho báo cáo HTML.
         *
         * <p>Mặc định dùng CDN công khai: file HTML nhẹ, nhưng cần mạng để VẼ được sơ đồ. Báo cáo
         * vẫn đọc được khi offline vì toàn bộ chữ nằm sẵn trong file - chỉ mất phần hình.
         *
         * <p>Trong mạng nội bộ hoặc môi trường chặn CDN, trỏ sang bản self-host:
         * {@code ANALYSIS_MERMAID_JS_URL=https://intranet.company.vn/js/mermaid.min.js}.
         * Lưu ý CDN công khai chỉ tải thư viện xuống, KHÔNG gửi nội dung sơ đồ đi đâu -
         * mọi việc render diễn ra trong browser.
         */
        private String mermaidJsUrl = "https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.min.js";

        public String getMermaidJsUrl() {
            return this.mermaidJsUrl;
        }

        public void setMermaidJsUrl(String mermaidJsUrl) {
            this.mermaidJsUrl = mermaidJsUrl;
        }
    }

    public static class Git {

        /**
         * Personal access token của GitLab (scope read_repository). Ưu tiên dùng cách này.
         *
         * <p>Khi tài khoản bật 2FA, GitLab KHÔNG nhận password cho Git over HTTPS nữa - token là
         * cách duy nhất còn dùng được. Vì GitLab.com bật 2FA cho phần lớn tài khoản, coi như
         * đây là cách mặc định.
         *
         * <p>Chỉ truyền qua biến môi trường, không hardcode vào file cấu hình vì file này được commit.
         */
        private String token = "";

        /**
         * Username cho HTTP basic auth. Dùng cho ba trường hợp:
         * <ul>
         *   <li>Deploy token của GitLab: username dạng {@code gitlab+deploy-token-123} - cách
         *       GitLab khuyến nghị cho truy cập chỉ-đọc tự động, và không bị 2FA ảnh hưởng vì
         *       nó không phải tài khoản người dùng</li>
         *   <li>Username thật + PAT làm password: dùng khi instance từ chối username
         *       {@code oauth2} mà token vẫn hợp lệ</li>
         *   <li>Tài khoản/mật khẩu thường: CHỈ chạy với GitLab self-hosted không bật 2FA</li>
         * </ul>
         */
        private String username = "";

        /** Password, PAT, hoặc secret của deploy token. Chỉ truyền qua biến môi trường. */
        private String password = "";

        /** Branch mặc định khi người dùng không nói rõ. */
        private String defaultBranch = "master";

        /**
         * Danh sách host được phép clone. Để trống = cho phép tất cả, nhưng khi tool này
         * được LLM gọi thì nên khai báo tường minh để model không clone được host lạ.
         */
        private List<String> allowedHosts = List.of();

        private int timeoutSeconds = 180;

        public String getToken() {
            return this.token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getUsername() {
            return this.username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return this.password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getDefaultBranch() {
            return this.defaultBranch;
        }

        public void setDefaultBranch(String defaultBranch) {
            this.defaultBranch = defaultBranch;
        }

        public List<String> getAllowedHosts() {
            return this.allowedHosts;
        }

        public void setAllowedHosts(List<String> allowedHosts) {
            this.allowedHosts = allowedHosts;
        }

        public int getTimeoutSeconds() {
            return this.timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    public Path getWorkspace() {
        return this.workspace;
    }

    public void setWorkspace(Path workspace) {
        this.workspace = workspace;
    }

    public Path getOutputDir() {
        return this.outputDir;
    }

    public void setOutputDir(Path outputDir) {
        this.outputDir = outputDir;
    }

    public int getMaxSourceFiles() {
        return this.maxSourceFiles;
    }

    public void setMaxSourceFiles(int maxSourceFiles) {
        this.maxSourceFiles = maxSourceFiles;
    }

    public int getMaxDepth() {
        return this.maxDepth;
    }

    public void setMaxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    public int getMaxNodes() {
        return this.maxNodes;
    }

    public void setMaxNodes(int maxNodes) {
        this.maxNodes = maxNodes;
    }

    public int getMaxEndpointsListed() {
        return this.maxEndpointsListed;
    }

    public void setMaxEndpointsListed(int maxEndpointsListed) {
        this.maxEndpointsListed = maxEndpointsListed;
    }

    public boolean isExpandSupportTypes() {
        return this.expandSupportTypes;
    }

    public void setExpandSupportTypes(boolean expandSupportTypes) {
        this.expandSupportTypes = expandSupportTypes;
    }

    public boolean isShowLeafReturns() {
        return this.showLeafReturns;
    }

    public void setShowLeafReturns(boolean showLeafReturns) {
        this.showLeafReturns = showLeafReturns;
    }

    public Git getGit() {
        return this.git;
    }

    public Report getReport() {
        return this.report;
    }

    public Ai getAi() {
        return this.ai;
    }

    public Database getDatabase() {
        return this.database;
    }
}
