package com.example.aihackathon.codeanalysis;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;

/**
 * Repo mẫu thứ hai: hệ thống gọi thẳng package PL/SQL của Oracle.
 *
 * <p>Tách khỏi {@link FixtureRepo} thay vì thêm controller vào repo mẫu cũ, vì các test hiện có
 * khẳng định repo mẫu cũ có <b>đúng</b> hai endpoint. Thêm vào đó sẽ làm vỡ những assertion không
 * liên quan gì tới stored procedure - đúng kiểu test đổi màu vì lý do sai.
 */
public final class ProcedureFixtureRepo {

    public static final String REPO_URL = "https://gitlab.example.com/bank/settlement.git";

    public static final String BRANCH = "master";

    public static final String COMMIT_SHA = "fedcba9876543210fedcba9876543210fedcba98";

    private ProcedureFixtureRepo() {
    }

    public static Path root() {
        URL resource = ProcedureFixtureRepo.class.getResource("/fixture-procedures");
        if (resource == null) {
            throw new IllegalStateException("Thiếu fixture-procedures trong test resources");
        }
        try {
            return Path.of(resource.toURI());
        }
        catch (URISyntaxException ex) {
            throw new IllegalStateException("Đường dẫn fixture không hợp lệ", ex);
        }
    }

    static AnalysisProperties properties() {
        AnalysisProperties properties = FixtureRepo.properties();
        properties.getDatabase().setName("Way4");
        return properties;
    }

    static JavaSourceIndex index() {
        return JavaSourceIndex.build(root(), properties());
    }

    public static ApiFlowAnalyzer analyzer() {
        AnalysisProperties properties = properties();
        return new ApiFlowAnalyzer(new StubFetcher(properties), properties);
    }

    /** Thay bước clone GitLab: trả thẳng repo mẫu trên đĩa. */
    public static final class StubFetcher extends GitRepoFetcher {

        /** Đổi được để mô phỏng repo đã có commit mới - dùng cho test cache theo commit. */
        public String commitSha = COMMIT_SHA;

        public StubFetcher(AnalysisProperties properties) {
            super(properties);
        }

        @Override
        public FetchedRepo fetch(String repoUrl, String branch) {
            return new FetchedRepo(REPO_URL, BRANCH, this.commitSha, root(), true);
        }
    }
}
