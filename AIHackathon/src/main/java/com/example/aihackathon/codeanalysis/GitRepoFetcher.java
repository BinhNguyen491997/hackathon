package com.example.aihackathon.codeanalysis;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

/**
 * Lấy source của một repo GitLab về máy để phân tích.
 *
 * <p>Chiến lược: {@code ls-remote} lấy SHA của branch trên server (rẻ, không tải source).
 * Nếu bản clone đang có trong workspace đã đúng SHA đó thì dùng lại; nếu khác thì xoá thư
 * mục cache và shallow clone lại. Cách này tránh phải fetch/reset - vốn dễ để lại repo ở
 * trạng thái nửa vời khi mạng lỗi.
 *
 * <p>Chỉ ghi/xoá trong {@code analysis.workspace} (thư mục tạm do agent tự tạo), không bao
 * giờ chạm tới repo làm việc của người dùng.
 */
@Component
public class GitRepoFetcher {

    private static final Logger log = LoggerFactory.getLogger(GitRepoFetcher.class);

    /**
     * Prefix của các loại token GitLab KHÔNG dùng được ở ô {@code analysis.git.token}.
     *
     * <p>Cả nhóm này đều trả 401 giống nhau nên rất khó đoán nguyên nhân. Bắt sớm bằng prefix
     * rẻ hơn nhiều so với ngồi đọc log GitLab. Chỉ cảnh báo chứ không chặn: prefix là quy ước
     * của GitLab, có thể đổi, và không nên để một heuristic chặn đường một cấu hình hợp lệ.
     */
    private static final Map<String, String> WRONG_TOKEN_PREFIXES = Map.of(
            "gldt-", "deploy token - phải khai qua analysis.git.username="
                    + "gitlab+deploy-token-<id> và analysis.git.password=<secret>",
            "glcbt-", "CI/CD job token - chỉ sống trong thời gian một job, không dùng cho service chạy dài",
            "gloas-", "OAuth application secret - đây là secret của app, không phải access token",
            "glft-", "feed token - không có quyền đọc repository",
            "glrt-", "runner authentication token - không dùng cho Git over HTTPS",
            "glagent-", "GitLab agent for Kubernetes token - không dùng cho Git over HTTPS");

    private final AnalysisProperties properties;

    public GitRepoFetcher(AnalysisProperties properties) {
        this.properties = properties;
    }

    /**
     * @param repoUrl URL HTTPS của repo, ví dụ https://gitlab.com/team/shop.git
     * @param branch  branch cần đọc; null/blank thì lấy analysis.git.default-branch
     */
    public FetchedRepo fetch(String repoUrl, String branch) {
        String normalizedUrl = requireHttpsUrl(repoUrl);
        String targetBranch = (branch == null || branch.isBlank())
                ? this.properties.getGit().getDefaultBranch()
                : branch.trim();

        String remoteSha = remoteHeadSha(normalizedUrl, targetBranch);
        Path dir = cacheDir(normalizedUrl, targetBranch);

        String cachedSha = readCachedSha(dir);
        if (remoteSha.equals(cachedSha)) {
            log.info("dùng lại bản clone trong cache: {} @ {} ({})", normalizedUrl, targetBranch,
                    shortSha(remoteSha));
            return new FetchedRepo(normalizedUrl, targetBranch, remoteSha, dir, true);
        }

        log.info("clone {} branch {} -> {} ({}; cache {} khác remote {})", normalizedUrl, targetBranch,
                dir, describeAuth(), shortSha(cachedSha), shortSha(remoteSha));
        deleteRecursively(dir);
        String clonedSha = shallowClone(normalizedUrl, targetBranch, dir);
        writeCachedSha(dir, clonedSha);
        return new FetchedRepo(normalizedUrl, targetBranch, clonedSha, dir, false);
    }

    private String remoteHeadSha(String repoUrl, String branch) {
        try {
            Collection<Ref> refs = Git.lsRemoteRepository()
                    .setRemote(repoUrl)
                    .setHeads(true)
                    .setCredentialsProvider(credentials())
                    .setTimeout(this.properties.getGit().getTimeoutSeconds())
                    .call();

            String wanted = "refs/heads/" + branch;
            for (Ref ref : refs) {
                if (wanted.equals(ref.getName())) {
                    ObjectId id = ref.getObjectId();
                    if (id != null) {
                        return id.getName();
                    }
                }
            }
            List<String> available = refs.stream()
                    .map(Ref::getName)
                    .filter(name -> name.startsWith("refs/heads/"))
                    .map(name -> name.substring("refs/heads/".length()))
                    .limit(20)
                    .toList();
            throw new IllegalArgumentException("Repo không có branch '" + branch
                    + "'. Các branch đang có: " + String.join(", ", available));
        }
        catch (GitAPIException ex) {
            throw new IllegalArgumentException(explainFailure(repoUrl, branch, ex), ex);
        }
    }

    /**
     * GitLab trả đúng một câu "not authorized" cho rất nhiều nguyên nhân khác nhau, nên message
     * thô không giúp gì cho việc sửa. Ở đây liệt kê sẵn các nguyên nhân theo tần suất thực tế.
     */
    private String explainFailure(String repoUrl, String branch, GitAPIException ex) {
        String raw = String.valueOf(ex.getMessage());
        String lower = raw.toLowerCase(Locale.ROOT);
        StringBuilder message = new StringBuilder("Không đọc được repo ").append(repoUrl)
                .append(" branch ").append(branch)
                .append(" (").append(describeAuth()).append("). GitLab trả: ").append(raw);

        boolean unauthorized = lower.contains("not authorized") || lower.contains("401")
                || lower.contains("authentication is required");
        if (unauthorized) {
            message.append("""

                    Token đã được gửi đi nhưng GitLab từ chối. Kiểm tra theo thứ tự sau:
                    1. Scope: token phải có 'read_repository' (hoặc 'api'). Chỉ 'read_api' là KHÔNG đủ
                       cho Git over HTTPS - đây là nguyên nhân phổ biến nhất.
                    2. Fine-grained token: nếu token thuộc loại fine-grained, phải chọn tường minh
                       project/group này khi tạo token, không có sẵn quyền.
                    3. Hết hạn hoặc đã revoke: xem lại ở GitLab > Settings > Access tokens.
                    4. Sai tài khoản: token thuộc user không có quyền trên project này.
                    5. Dấu nháy lọt vào giá trị: trên cmd.exe, set VAR="x" lưu cả dấu nháy.
                       Dùng PowerShell $env:VAR = 'x'.
                    Kiểm tra nhanh scope và chủ token:
                      curl -H "PRIVATE-TOKEN: $env:GITLAB_TOKEN" \
                        https://gitlab.com/api/v4/personal_access_tokens/self""");
        }
        else if (lower.contains("not found") || lower.contains("repository not found")) {
            message.append("""

                    Không thấy repo. Kiểm tra: đường dẫn group/project có đúng chính tả không,
                    project có bị đổi tên/di chuyển không, và token có quyền nhìn thấy project
                    (repo private mà token không có quyền cũng báo not found).""");
        }
        return message.toString();
    }

    private String shallowClone(String repoUrl, String branch, Path dir) {
        try (Git git = Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(dir.toFile())
                .setBranch(branch)
                .setBranchesToClone(List.of("refs/heads/" + branch))
                .setCloneAllBranches(false)
                .setNoTags()
                .setDepth(1)
                .setCredentialsProvider(credentials())
                .setTimeout(this.properties.getGit().getTimeoutSeconds())
                .call()) {

            ObjectId head = git.getRepository().resolve("HEAD");
            if (head == null) {
                throw new IllegalStateException("Clone xong nhưng không resolve được HEAD: " + repoUrl);
            }
            return head.getName();
        }
        catch (GitAPIException ex) {
            deleteRecursively(dir);
            throw new IllegalArgumentException("Clone thất bại " + repoUrl + " branch " + branch
                    + ": " + ex.getMessage(), ex);
        }
        catch (IOException ex) {
            deleteRecursively(dir);
            throw new UncheckedIOException("Lỗi đọc repo sau khi clone: " + repoUrl, ex);
        }
    }

    /**
     * Thông tin đăng nhập đã chọn được, kèm nguồn để log mà không lộ secret.
     *
     * @param username tên dùng cho HTTP basic auth
     * @param secret   token hoặc password - KHÔNG được đưa vào log hay message lỗi
     * @param source   mô tả nguồn cấu hình, dùng cho log
     */
    record GitCredentials(String username, String secret, String source) {

        @Override
        public String toString() {
            // chặn sẵn trường hợp record bị nhỡ tay đưa vào log.info("{}", credentials)
            return "GitCredentials[username=" + this.username + ", secret=***, source=" + this.source + "]";
        }
    }

    /**
     * Chọn cách xác thực theo thứ tự ưu tiên: token trước, rồi mới tới username/password.
     *
     * <p>Ưu tiên token vì nó thu hồi được độc lập, giới hạn được scope xuống chỉ-đọc, và không
     * bị 2FA chặn. Username/password để dành cho deploy token và GitLab self-hosted không 2FA.
     */
    Optional<GitCredentials> resolveCredentials() {
        AnalysisProperties.Git git = this.properties.getGit();
        String token = cleanSecret(git.getToken(), "analysis.git.token (GITLAB_TOKEN)");
        String username = cleanSecret(git.getUsername(), "analysis.git.username (GIT_USERNAME)");
        String password = cleanSecret(git.getPassword(), "analysis.git.password (GIT_PASSWORD)");

        if (token != null) {
            if (username != null) {
                log.warn("cấu hình có cả token và username - dùng token, bỏ qua username '{}'", username);
            }
            warnIfWrongTokenType(token);
            // GitLab nhận personal/project/group access token qua HTTPS với username cố định "oauth2"
            return Optional.of(new GitCredentials("oauth2", token, "analysis.git.token"));
        }
        if (username != null && password != null) {
            return Optional.of(new GitCredentials(username, password,
                    "analysis.git.username + password"));
        }
        if (username != null || password != null) {
            throw new IllegalStateException("Cấu hình xác thực Git không đầy đủ: cần cả "
                    + "analysis.git.username và analysis.git.password (thiếu "
                    + (username == null ? "username" : "password") + ").");
        }
        return Optional.empty();
    }

    private CredentialsProvider credentials() {
        return resolveCredentials()
                .map(credentials -> (CredentialsProvider) new UsernamePasswordCredentialsProvider(
                        credentials.username(), credentials.secret()))
                .orElse(null);
    }

    /**
     * Chuẩn hoá giá trị cấu hình bí mật: cắt khoảng trắng và bỏ cặp dấu nháy bao ngoài.
     *
     * <p>Trên Windows, {@code set GITLAB_TOKEN="glpat-abc"} trong cmd.exe lưu luôn cả dấu nháy
     * vào giá trị (PowerShell thì không). Token thành {@code "glpat-abc"} và GitLab trả
     * "not authorized" - không có cách nào đoán ra từ log. Cắt ở đây và cảnh báo để người dùng
     * biết mình đã gõ sai chỗ nào.
     */
    private static String cleanSecret(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 2
                && ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                        || (trimmed.startsWith("'") && trimmed.endsWith("'")))) {
            log.warn("{} có cặp dấu nháy bao ngoài - đã tự bỏ. Trên cmd.exe, "
                    + "set VAR=\"gia-tri\" lưu cả dấu nháy vào giá trị; dùng set VAR=gia-tri "
                    + "hoặc PowerShell $env:VAR = 'gia-tri'.", name);
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Cảnh báo khi token thuộc loại không dùng được cho Git over HTTPS.
     *
     * <p>Chỉ so prefix và chỉ log lại prefix, không bao giờ log phần thân token.
     */
    static void warnIfWrongTokenType(String token) {
        for (Map.Entry<String, String> entry : WRONG_TOKEN_PREFIXES.entrySet()) {
            if (token.startsWith(entry.getKey())) {
                log.warn("analysis.git.token bắt đầu bằng '{}' - trông như {}. "
                        + "Loại dùng được ở đây là personal/project/group access token (prefix 'glpat-').",
                        entry.getKey(), entry.getValue());
                return;
            }
        }
        if (!token.startsWith("glpat-")) {
            // OAuth access token, impersonation token, token của self-hosted phiên bản cũ (không có
            // prefix) đều rơi vào đây và đều có thể hợp lệ -> chỉ ghi debug, không làm ồn log.
            log.debug("analysis.git.token không có prefix 'glpat-'. Nếu đây là OAuth access token, "
                    + "lưu ý nó hết hạn sau 2 giờ và service này không tự refresh.");
        }
    }

    /** Mô tả cách xác thực đang dùng, an toàn để đưa vào log và message lỗi. */
    private String describeAuth() {
        try {
            return resolveCredentials()
                    .map(credentials -> "xác thực bằng " + credentials.source()
                            + ", username=" + credentials.username())
                    .orElse("không có xác thực - chỉ đọc được repo public");
        }
        catch (IllegalStateException ex) {
            return "cấu hình xác thực không hợp lệ";
        }
    }

    /**
     * Chỉ nhận HTTPS và (nếu có cấu hình) chỉ nhận host trong allow-list. Tool này có thể
     * do LLM gọi với URL lấy từ câu chat, nên phải chặn ở đây thay vì tin vào input.
     */
    String requireHttpsUrl(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) {
            throw new IllegalArgumentException("Thiếu URL repo GitLab.");
        }
        String trimmed = repoUrl.trim();
        URI uri;
        try {
            uri = URI.create(trimmed);
        }
        catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("URL repo không hợp lệ: " + trimmed, ex);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("https")) {
            throw new IllegalArgumentException(
                    "Chỉ hỗ trợ URL HTTPS (dạng https://gitlab.../group/project.git), nhận được: " + trimmed);
        }
        // Từ chối dạng https://user:pass@host/... : URL này được ghi vào log và trả lại trong
        // response, nên nhúng secret vào đó là lộ secret. Message lỗi cũng không in lại URL.
        if (uri.getUserInfo() != null || trimmed.contains("@")) {
            throw new IllegalArgumentException("URL repo không được chứa tài khoản/mật khẩu "
                    + "(dạng https://user:pass@host/...) vì URL sẽ bị ghi vào log. "
                    + "Hãy cấu hình qua biến môi trường GITLAB_TOKEN, hoặc "
                    + "GIT_USERNAME + GIT_PASSWORD.");
        }
        String host = uri.getHost();
        if (host == null) {
            throw new IllegalArgumentException("URL repo thiếu host: " + trimmed);
        }
        List<String> allowed = this.properties.getGit().getAllowedHosts();
        if (!allowed.isEmpty()) {
            boolean ok = allowed.stream().anyMatch(candidate -> candidate.equalsIgnoreCase(host));
            if (!ok) {
                throw new IllegalArgumentException("Host '" + host
                        + "' không nằm trong analysis.git.allowed-hosts.");
            }
        }
        else {
            log.warn("analysis.git.allowed-hosts để trống - cho phép clone từ mọi host. "
                    + "Nên khai báo tường minh khi mở endpoint này ra ngoài localhost.");
        }
        return trimmed;
    }

    private Path cacheDir(String repoUrl, String branch) {
        String slug = repoUrl.replaceAll("^https://", "")
                .replaceAll("\\.git$", "")
                .replaceAll("[^A-Za-z0-9]+", "-")
                .toLowerCase(Locale.ROOT);
        if (slug.length() > 60) {
            slug = slug.substring(slug.length() - 60);
        }
        String branchSlug = branch.replaceAll("[^A-Za-z0-9]+", "-").toLowerCase(Locale.ROOT);
        String hash = sha1Hex(repoUrl + "#" + branch).substring(0, 8);
        return this.properties.getWorkspace().resolve(slug + "__" + branchSlug + "__" + hash);
    }

    /** SHA của bản clone được ghi ra file riêng: đọc nhanh, không cần mở repo. */
    private static Path shaMarker(Path dir) {
        return dir.resolve(".agent-commit-sha");
    }

    private static String readCachedSha(Path dir) {
        Path marker = shaMarker(dir);
        if (!Files.isRegularFile(marker)) {
            return null;
        }
        try {
            return Files.readString(marker, StandardCharsets.UTF_8).trim();
        }
        catch (IOException ex) {
            log.warn("không đọc được {}: {}", marker, ex.getMessage());
            return null;
        }
    }

    private static void writeCachedSha(Path dir, String sha) {
        try {
            Files.writeString(shaMarker(dir), sha, StandardCharsets.UTF_8);
        }
        catch (IOException ex) {
            log.warn("không ghi được marker SHA vào {}: {}", dir, ex.getMessage());
        }
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                }
                catch (IOException ex) {
                    // file .pack của git hay bị lock trên Windows - không dừng cả tiến trình vì việc này
                    log.debug("không xoá được {}: {}", path, ex.getMessage());
                }
            });
        }
        catch (IOException ex) {
            log.warn("không dọn được thư mục cache {}: {}", dir, ex.getMessage());
        }
    }

    private static String sha1Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM không có SHA-1", ex);
        }
    }

    private static String shortSha(String sha) {
        if (sha == null || sha.length() < 8) {
            return String.valueOf(sha);
        }
        return sha.substring(0, 8);
    }

    /**
     * @param fromCache true = dùng lại bản clone cũ, hữu ích khi log thời gian phản hồi
     */
    public record FetchedRepo(String repoUrl, String branch, String commitSha, Path root, boolean fromCache) {
    }
}
