package com.example.aihackathon.codeanalysis;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;

import com.example.aihackathon.codeanalysis.model.ApiEndpoint;

/**
 * Nạp repo Java/Spring mẫu trong test resources.
 *
 * <p>Fixture là file .java thật (không được biên dịch vì nằm trong resources), nên test đi qua
 * đúng đường dẫn parse như khi phân tích repo GitLab thật - chỉ bỏ bước clone.
 */
public final class FixtureRepo {

    public static final String REPO_URL = "https://gitlab.example.com/shop/backend.git";

    public static final String BRANCH = "master";

    public static final String COMMIT_SHA = "0123456789abcdef0123456789abcdef01234567";

    private FixtureRepo() {
    }

    public static Path root() {
        URL resource = FixtureRepo.class.getResource("/fixture-repo");
        if (resource == null) {
            throw new IllegalStateException("Thiếu fixture-repo trong test resources");
        }
        try {
            return Path.of(resource.toURI());
        }
        catch (URISyntaxException ex) {
            throw new IllegalStateException("Đường dẫn fixture không hợp lệ", ex);
        }
    }

    public static AnalysisProperties properties() {
        AnalysisProperties properties = new AnalysisProperties();
        properties.setMaxDepth(6);
        properties.setMaxNodes(400);
        properties.setOutputDir(Path.of("target", "test-diagrams"));
        // Tắt cache LLM cho mọi test dùng fixture này: phần lớn chúng đếm số lần model được gọi, và
        // output-dir ở đây là thư mục DÙNG CHUNG nên bản cache của test này sẽ rơi vào test khác.
        // AiResultCacheTests tự bật lại với @TempDir riêng.
        properties.getAi().setCache(false);
        return properties;
    }

    static JavaSourceIndex index() {
        return JavaSourceIndex.build(root(), properties());
    }

    static List<ApiEndpoint> endpoints() {
        return EndpointScanner.scan(index());
    }

    /** Analyzer đã nối sẵn vào repo mẫu, dùng cho test ở package khác. */
    public static ApiFlowAnalyzer analyzer() {
        AnalysisProperties properties = properties();
        return new ApiFlowAnalyzer(new StubFetcher(properties), properties);
    }

    /** Thay bước clone GitLab: trả thẳng repo mẫu trên đĩa, không cần mạng. */
    public static final class StubFetcher extends GitRepoFetcher {

        public String commitSha = COMMIT_SHA;

        public int calls;

        /** Token mà lớp trên truyền xuống ở lần fetch gần nhất - để test kiểm tra đường đi. */
        public String lastToken;

        public StubFetcher(AnalysisProperties properties) {
            super(properties);
        }

        @Override
        public FetchedRepo fetch(String repoUrl, String branch, String requestToken) {
            this.calls++;
            this.lastToken = requestToken;
            return new FetchedRepo(REPO_URL, BRANCH, this.commitSha, root(), true);
        }
    }
}
