package com.example.aihackathon.codeanalysis;

import java.util.Optional;

import com.example.aihackathon.codeanalysis.GitRepoFetcher.GitCredentials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test phần xác thực và kiểm tra URL. Không có test nào chạm mạng: mọi thứ ở đây là quyết định
 * cấu hình thuần, và đó chính là chỗ dễ sai nhất (chọn nhầm cơ chế, hoặc để secret rơi vào log).
 */
class GitRepoFetcherAuthTests {

    private AnalysisProperties properties;

    private GitRepoFetcher fetcher;

    @BeforeEach
    void setUp() {
        this.properties = new AnalysisProperties();
        this.fetcher = new GitRepoFetcher(this.properties);
    }

    // ------------------------------------------------------------------
    // Chọn cơ chế xác thực
    // ------------------------------------------------------------------

    @Test
    void khongCauHinhGiThiKhongXacThuc() {
        assertThat(this.fetcher.resolveCredentials()).isEmpty();
    }

    @Test
    void tokenDuocGuiVoiUsernameOauth2() {
        this.properties.getGit().setToken("glpat-abc123");

        Optional<GitCredentials> credentials = this.fetcher.resolveCredentials();

        assertThat(credentials).isPresent();
        assertThat(credentials.get().username()).isEqualTo("oauth2");
        assertThat(credentials.get().secret()).isEqualTo("glpat-abc123");
        assertThat(credentials.get().source()).isEqualTo("analysis.git.token");
    }

    @Test
    void dungDuocTaiKhoanVaMatKhau() {
        this.properties.getGit().setUsername("nguyen.van.a");
        this.properties.getGit().setPassword("mat-khau-cua-toi");

        Optional<GitCredentials> credentials = this.fetcher.resolveCredentials();

        assertThat(credentials).isPresent();
        assertThat(credentials.get().username()).isEqualTo("nguyen.van.a");
        assertThat(credentials.get().secret()).isEqualTo("mat-khau-cua-toi");
    }

    @Test
    void dungDuocDeployTokenCuaGitLab() {
        this.properties.getGit().setUsername("gitlab+deploy-token-123");
        this.properties.getGit().setPassword("deploy-secret");

        assertThat(this.fetcher.resolveCredentials())
                .get()
                .extracting(GitCredentials::username)
                .isEqualTo("gitlab+deploy-token-123");
    }

    @Test
    void tokenUuTienHonTaiKhoanMatKhau() {
        this.properties.getGit().setToken("glpat-abc123");
        this.properties.getGit().setUsername("nguyen.van.a");
        this.properties.getGit().setPassword("mat-khau-cua-toi");

        assertThat(this.fetcher.resolveCredentials())
                .get()
                .extracting(GitCredentials::username)
                .isEqualTo("oauth2");
    }

    @Test
    void baoLoiKhiCauHinhNuaVoi() {
        this.properties.getGit().setUsername("nguyen.van.a");

        assertThatThrownBy(() -> this.fetcher.resolveCredentials())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("thiếu password");

        this.properties.getGit().setUsername("");
        this.properties.getGit().setPassword("mat-khau-cua-toi");

        assertThatThrownBy(() -> this.fetcher.resolveCredentials())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("thiếu username");
    }

    @Test
    void coiChuoiTrangLaKhongCauHinh() {
        this.properties.getGit().setToken("   ");
        this.properties.getGit().setUsername("  ");
        this.properties.getGit().setPassword("  ");

        assertThat(this.fetcher.resolveCredentials()).isEmpty();
    }

    @Test
    void khongInSecretRaToString() {
        this.properties.getGit().setToken("glpat-secret-that");

        String rendered = this.fetcher.resolveCredentials().orElseThrow().toString();

        assertThat(rendered).doesNotContain("glpat-secret-that").contains("secret=***");
    }

    @Test
    void boDauNhayLotVaoTokenTuCmdExe() {
        // cmd.exe: set GITLAB_TOKEN="glpat-abc" luu ca dau nhay vao gia tri
        this.properties.getGit().setToken("\"glpat-abc123\"");

        assertThat(this.fetcher.resolveCredentials())
                .get()
                .extracting(GitCredentials::secret)
                .isEqualTo("glpat-abc123");
    }

    @Test
    void boDauNhayDonVaKhoangTrangDuThua() {
        this.properties.getGit().setUsername("  'gitlab+deploy-token-9'  ");
        this.properties.getGit().setPassword("  \"secret-cua-toi\"  ");

        assertThat(this.fetcher.resolveCredentials())
                .get()
                .extracting(GitCredentials::username, GitCredentials::secret)
                .containsExactly("gitlab+deploy-token-9", "secret-cua-toi");
    }

    @Test
    void chiBoCapDauNhayBaoNgoaiChuKhongBoDauNhayGiuaToken() {
        this.properties.getGit().setToken("glpat-co\"dau-nhay-giua");

        assertThat(this.fetcher.resolveCredentials())
                .get()
                .extracting(GitCredentials::secret)
                .isEqualTo("glpat-co\"dau-nhay-giua");
    }

    @Test
    void chuoiChiGomHaiDauNhayCoiNhuTrong() {
        this.properties.getGit().setToken("\"\"");

        assertThat(this.fetcher.resolveCredentials()).isEmpty();
    }

    @Test
    void moiLoaiTokenHoGlpatDeuDungChungMotDuong() {
        // personal, project, group, impersonation token đều dùng prefix glpat- và đều
        // xác thực Git over HTTPS giống nhau -> không cần cấu hình riêng cho từng loại
        for (String token : java.util.List.of("glpat-personal", "glpat-project", "glpat-group")) {
            this.properties.getGit().setToken(token);

            assertThat(this.fetcher.resolveCredentials())
                    .get()
                    .extracting(GitCredentials::username, GitCredentials::secret)
                    .containsExactly("oauth2", token);
        }
    }

    @Test
    void tokenSaiLoaiVanChayNhungKhongLamHongCauHinh() {
        // chỉ cảnh báo, không chặn: prefix là quy ước của GitLab và có thể đổi
        this.properties.getGit().setToken("gldt-deploy-token-secret");

        assertThat(this.fetcher.resolveCredentials()).isPresent();
    }

    @Test
    void kiemTraPrefixKhongNemLoiVoiMoiDangToken() {
        // hàm cảnh báo phải chịu được mọi input, kể cả token ngắn hơn prefix
        for (String token : java.util.List.of("gldt-", "x", "glpat-", "gloas-abc", "token-khong-prefix")) {
            GitRepoFetcher.warnIfWrongTokenType(token);
        }
    }

    // ------------------------------------------------------------------
    // Kiểm tra URL
    // ------------------------------------------------------------------

    @Test
    void tuChoiUrlNhungTaiKhoanMatKhauDeSecretKhongRoiVaoLog() {
        assertThatThrownBy(() -> this.fetcher
                .requireHttpsUrl("https://nguyen.van.a:mat-khau@gitlab.com/team/shop.git"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("không được chứa tài khoản/mật khẩu")
                .hasMessageNotContaining("mat-khau");
    }

    @Test
    void chapNhanUrlHttpsBinhThuong() {
        assertThat(this.fetcher.requireHttpsUrl("https://gitlab.com/team/shop.git"))
                .isEqualTo("https://gitlab.com/team/shop.git");
    }

    @Test
    void tuChoiSshVaHttp() {
        assertThatThrownBy(() -> this.fetcher.requireHttpsUrl("git@gitlab.com:team/shop.git"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> this.fetcher.requireHttpsUrl("http://gitlab.com/team/shop.git"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Chỉ hỗ trợ URL HTTPS");
    }

    @Test
    void chanHostNgoaiAllowList() {
        this.properties.getGit().setAllowedHosts(java.util.List.of("gitlab.company.vn"));

        assertThatThrownBy(() -> this.fetcher.requireHttpsUrl("https://gitlab.com/team/shop.git"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowed-hosts");

        assertThat(this.fetcher.requireHttpsUrl("https://gitlab.company.vn/team/shop.git"))
                .isEqualTo("https://gitlab.company.vn/team/shop.git");
    }
}
