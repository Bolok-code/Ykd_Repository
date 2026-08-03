package ykd.ykd.job.browser;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import ykd.ykd.job.config.LiepinProperties;
import ykd.ykd.job.model.LiepinApplicationResult;
import ykd.ykd.job.model.LiepinApplicationStatus;
import ykd.ykd.job.model.LiepinJobPosting;
import ykd.ykd.job.model.LiepinResume;
import ykd.ykd.job.model.ResumeDeliveryMode;
import ykd.ykd.job.model.LiepinSearchRequest;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 猎聘浏览器网关。
 *
 * <p>核心页面操作改编自 GET JOBS 的猎聘适配，包括监听职位搜索接口、遍历职位卡片、
 * 悬停招聘者区域、点击“聊一聊”和关闭聊天窗口。原项目：
 * https://github.com/loks666/get_jobs ，Copyright (c) 2024 GET JOBS，
 * 按 GETJOBS-NC-1.0 仅用于非商业学习。</p>
 *
 * <p>本类在其基础上适配当前项目的 Spring Boot、微信确认、MyBatis/SQLite
 * 和后台任务结构，不包含验证码绕过或浏览器指纹伪装。</p>
 */
@Slf4j
@Lazy
@Component
public class PlaywrightLiepinAutomationGateway implements LiepinAutomationGateway {
    static final String SEARCH_API = "com.liepin.searchfront4c.pc-search-job";
    private static final Pattern SALARY_PATTERN = Pattern.compile("(\\d+)\\s*[-—]\\s*(\\d+)\\s*[Kk]");
    private static final Set<String> OUTSOURCE_WORDS = Set.of("外包", "人力资源", "劳务派遣", "驻场", "服务外包");
    private static final Map<String, String> CITY_CODES = Map.ofEntries(
            Map.entry("全国", "410"), Map.entry("北京", "010"), Map.entry("上海", "020"),
            Map.entry("天津", "030"), Map.entry("重庆", "040"),
            Map.entry("广州", "050020"), Map.entry("深圳", "050090"), Map.entry("杭州", "070020"),
            Map.entry("成都", "280020"), Map.entry("南京", "060020"), Map.entry("武汉", "170020"),
            Map.entry("苏州", "060080"), Map.entry("西安", "270020"), Map.entry("大连", "210040")
    );

    private final LiepinProperties properties;
    private final ObjectMapper objectMapper;
    private Playwright playwright;
    private BrowserContext context;
    private Page page;

    public PlaywrightLiepinAutomationGateway(LiepinProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public synchronized String openLogin() {
        requireEnabled();
        Page current = page();
        String loginUrl = properties.getBaseUrl() + "/login";
        log.info("[LiepinJob] 正在打开登录页面: {}", loginUrl);
        current.navigate(loginUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        clickIfVisible(current.locator(LiepinLocators.QR_SWITCH).first());
        current.bringToFront();
        return "已打开猎聘登录页面。请在电脑浏览器中使用猎聘 App 扫码登录；完成后在微信发送“检查猎聘登录状态”。";
    }

    @Override
    public synchronized boolean isLoggedIn() {
        requireEnabled();
        Page current = page();
        if ("about:blank".equals(current.url()) || !current.url().contains("liepin.com")) {
            current.navigate(properties.getBaseUrl(),
                    new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        }
        if (visible(current.locator(LiepinLocators.USER_INFO).first())) {
            return true;
        }
        if (current.url().contains("/login") || visible(current.locator(LiepinLocators.LOGIN_ENTRY).first())) {
            return false;
        }
        return false;
    }

    @Override
    public synchronized List<LiepinJobPosting> search(LiepinSearchRequest request, BooleanSupplier cancelled) {
        requireEnabled();
        if (!isLoggedIn()) {
            throw new LiepinLoginRequiredException("猎聘尚未登录或登录已失效，请先打开登录页面并扫码登录");
        }

        Page current = page();
        Map<String, LiepinJobPosting> captured = new LinkedHashMap<>();
        Consumer<Response> responseListener = response -> captureSearchResponse(response, captured, request.city());
        current.onResponse(responseListener);
        try {
            String url = searchUrl(request, 0);
            log.info("[LiepinJob] 打开搜索页: keyword={}, city={}, url={}", request.keyword(), request.city(), url);
            current.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            closeSubscriptionPopup(current);
            waitForJobCards(current);
            current.waitForTimeout(1200);
            mergeDomFallback(current, captured, request.city());

            for (int pageIndex = 1;
                 pageIndex < properties.getMaxSearchPages() && captured.size() < request.maxResults();
                 pageIndex++) {
                if (cancelled.getAsBoolean()) break;
                Locator next = current.locator(LiepinLocators.NEXT_PAGE).first();
                if (!visible(next)) break;
                next.click();
                current.waitForTimeout(1200);
                waitForJobCards(current);
                closeSubscriptionPopup(current);
                mergeDomFallback(current, captured, request.city());
            }
        } finally {
            current.offResponse(responseListener);
        }

        List<LiepinJobPosting> result = new ArrayList<>();
        for (LiepinJobPosting posting : captured.values()) {
            if (cancelled.getAsBoolean() || result.size() >= request.maxResults()) break;
            if (!salaryMatches(posting.getSalary(), request.minSalaryK(), request.maxSalaryK())) continue;
            if (request.excludeOutsourcing() && isOutsourcing(posting)) continue;
            result.add(posting);
        }
        log.info("[LiepinJob] 搜索完成: keyword={}, city={}, captured={}, candidates={}",
                request.keyword(), request.city(), captured.size(), result.size());
        return result;
    }

    @Override
    public synchronized LiepinApplicationResult apply(LiepinJobPosting posting, String ignoredGreeting) {
        requireEnabled();
        if (!isLoggedIn()) {
            return LiepinApplicationResult.needsUserAction("猎聘登录已失效，请重新扫码登录后再确认岗位");
        }
        Page current = page();
        LiepinApplicationResult detailResult = tryApplyFromDetail(current, posting);
        if (detailResult != null && detailResult.success()) {
            return detailResult;
        }

        // GET JOBS 的猎聘实现是在搜索结果卡片内悬停招聘者区域后点击“聊一聊”。
        // 详情页没有按钮时回落到相同策略，兼容猎聘不同页面版本。
        LiepinApplicationResult cardResult = tryApplyFromSearchCard(current, posting);
        if (cardResult != null) {
            return cardResult;
        }
        if (detailResult != null) {
            return detailResult;
        }
        return LiepinApplicationResult.needsUserAction(
                "没有找到对应职位的“聊一聊”按钮，岗位可能已关闭或猎聘页面结构已变化，请在电脑浏览器中检查");
    }

    @Override
    public synchronized LiepinApplicationResult applyAndSendResume(
            LiepinJobPosting posting,
            LiepinResume resume,
            ResumeDeliveryMode mode,
            String greeting) {
        requireEnabled();
        log.info("[LiepinJob] 投递+发简历: job=\"{}\", company=\"{}\", mode={}",
                posting.getJobName(), posting.getCompanyName(), mode);
        if (!isLoggedIn()) {
            return LiepinApplicationResult.needsUserAction(
                    LiepinApplicationStatus.LOGIN_EXPIRED,
                    "猎聘登录已失效，自动投递计划已暂停；请在电脑完成登录，系统会自动恢复");
        }
        if (resume == null) {
            return LiepinApplicationResult.failed(
                    LiepinApplicationStatus.RESUME_NOT_FOUND, "没有找到当前求职简历");
        }
        Page current = page();
        try {
            LiepinApplicationResult openResult = openChatForResume(current, posting);
            if (openResult != null) return openResult;
            return switch (mode) {
                case ONLINE -> sendOnlineResume(current, posting);
                case ATTACHMENT -> sendAttachmentResume(current, posting, resume);
                case AUTO -> {
                    LiepinApplicationResult online = sendOnlineResume(current, posting);
                    if (online.success() || online.status().pausesCampaign()) yield online;
                    if (online.status() != LiepinApplicationStatus.RESUME_BUTTON_NOT_FOUND) yield online;
                    yield sendAttachmentResume(current, posting, resume);
                }
            };
        } catch (LiepinLoginRequiredException e) {
            return LiepinApplicationResult.needsUserAction(
                    LiepinApplicationStatus.CAPTCHA_REQUIRED, e.getMessage());
        } catch (Exception e) {
            log.warn("[LiepinJob] 自动发送简历失败: jobId={}, mode={}, error={}",
                    posting.getExternalJobId(), mode, e.getMessage());
            savePageSource(current, "send_failed");
            return LiepinApplicationResult.failed("发送简历失败：" + defaultIfBlank(e.getMessage(), "未知错误"));
        }
    }

    private LiepinApplicationResult openChatForResume(Page current, LiepinJobPosting posting) {
        log.info("[LiepinJob] 打开聊天窗口: job=\"{}\", company=\"{}\"",
                posting.getJobName(), posting.getCompanyName());
        for (int retry = 0; retry < 3; retry++) {
            try {
                // 优先从职位详情页打开
                if (posting.getJobUrl() != null && !posting.getJobUrl().isBlank()) {
                    current.navigate(posting.getJobUrl(),
                            new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                    current.waitForTimeout(800);
                    ensureNoVerification(current);
                    Locator button = firstVisible(
                            current.locator(LiepinLocators.CONTINUE_CHAT_BUTTON).first(),
                            current.locator(LiepinLocators.CHAT_BUTTON).first());
                    if (button != null && openChatWindow(current, button)) {
                        log.info("[LiepinJob] 详情页聊天窗口已打开: job=\"{}\"", posting.getJobName());
                        return null;
                    }
                }

                // 回落搜索列表
                log.info("[LiepinJob] 详情页未找到按钮，回落搜索列表: job=\"{}\"", posting.getJobName());
                LiepinSearchRequest fallbackRequest = new LiepinSearchRequest(
                        posting.getJobName(), searchCityForPosting(posting.getCity()), null, null,
                        false, Math.max(20, properties.getMaxSearchResults()));
                current.navigate(searchUrl(fallbackRequest, 0),
                        new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                closeSubscriptionPopup(current);
                waitForJobCards(current);
                current.waitForTimeout(800);
                ensureNoVerification(current);
                Locator card = findMatchingJobCard(current, posting);
                if (card == null) {
                    return LiepinApplicationResult.failed(
                            LiepinApplicationStatus.JOB_EXPIRED, "岗位已关闭或搜索结果中已找不到该岗位");
                }
                scrollAndHoverRecruiter(current, card);
                Locator button = firstVisible(
                        card.locator(LiepinLocators.CONTINUE_CHAT_BUTTON).first(),
                        card.locator(LiepinLocators.CHAT_BUTTON).first());
                if (button != null && openChatWindow(current, button)) {
                    log.info("[LiepinJob] 搜索列表聊天窗口已打开: job=\"{}\"", posting.getJobName());
                    return null;
                }
                // 按钮存在但点击失败 → 抛异常进重试
                throw new IllegalStateException("聊天窗口未打开");
            } catch (LiepinLoginRequiredException e) {
                throw e;
            } catch (Exception e) {
                if (retry < 2) {
                    log.info("[LiepinJob] 打开聊天窗口重试 {}/3: jobId={}, error={}",
                            retry + 1, posting.getExternalJobId(), e.getMessage());
                    current.waitForTimeout(800);
                } else {
                    log.warn("[LiepinJob] 打开聊天窗口3次均失败: jobId={}",
                            posting.getExternalJobId());
                    savePageSource(current, "open_chat_failed");
                }
            }
        }
        return LiepinApplicationResult.needsUserAction(
                LiepinApplicationStatus.RESUME_BUTTON_NOT_FOUND, "无法打开沟通窗口，猎聘页面可能已变化");
    }

    private boolean openChatWindow(Page current, Locator button) {
        button.scrollIntoViewIfNeeded();
        button.click();
        current.waitForTimeout(2000);
        // 多选择器兜底验证聊天窗口（新 im-ui 版 + 旧 __im_basic__ 版兼容）
        for (String selector : new String[]{
                LiepinLocators.CHAT_HEADER,
                LiepinLocators.CHAT_INPUT,
                ".__im_basic__header-wrap",
                ".__im_basic__",
                "div.__im_basic__contacts-title svg"
        }) {
            try {
                if (visible(current.locator(selector).first())) return true;
            } catch (Exception ignored) {}
        }
        ensureNoVerification(current);
        return false;
    }

    private LiepinApplicationResult sendOnlineResume(Page current, LiepinJobPosting posting) {
        log.info("[LiepinJob] 发送在线简历: job=\"{}\"", posting.getJobName());
        String before = safeBodyText(current);
        if (before.contains("简历已发送") || before.contains("已投递简历")) {
            closeChatWindow(current);
            return LiepinApplicationResult.alreadySent("该岗位已发送过简历，本次已跳过去重");
        }
        Locator sendButton = current.locator(LiepinLocators.SEND_RESUME_BUTTON).first();
        if (!visible(sendButton)) {
            return LiepinApplicationResult.failed(
                    LiepinApplicationStatus.RESUME_BUTTON_NOT_FOUND, "沟通窗口中没有找到发送简历入口");
        }
        sendButton.click();
        current.waitForTimeout(800);
        // 诊断：打印点击发简历后页面出现的文本片段，便于排查弹窗结构
        String afterClick = safeBodyText(current);
        int resumeIdx = afterClick.indexOf("简历");
        String snippet = resumeIdx >= 0
                ? afterClick.substring(Math.max(0, resumeIdx - 100),
                Math.min(afterClick.length(), resumeIdx + 300))
                : afterClick;
        log.info("[LiepinJob] 点击发简历后可见文本片段: {}",
                snippet.length() > 400 ? snippet.substring(0, 400) : snippet);
        Locator online = current.locator(LiepinLocators.ONLINE_RESUME_OPTION).first();
        if (visible(online)) online.click();
        current.waitForTimeout(300);
        // 遍历所有候选确认按钮，只点击第一个可用的，避免误点到聊天输入框的禁用发送键
        Locator confirms = current.locator(LiepinLocators.ONLINE_RESUME_CONFIRM);
        boolean confirmClicked = false;
        for (int i = 0; i < confirms.count(); i++) {
            Locator one = confirms.nth(i);
            try {
                if (one.isVisible() && one.isEnabled()) {
                    one.click();
                    confirmClicked = true;
                    break;
                }
            } catch (Exception ignored) {
            }
        }
        if (!confirmClicked) {
            savePageSource(current, "resume_send_failed");
            return LiepinApplicationResult.failed(
                    LiepinApplicationStatus.RESUME_BUTTON_NOT_FOUND,
                    "发送简历面板没有可点击的确认按钮，猎聘页面可能已变化，已保存页面供排查");
        }
        // 发送后校验页面是否出现“已发送”标记，避免点击无效却误报成功。
        // 注意：已点击确认但标记不明时按普通失败处理（不再回退附件模式），
        // 因为简历可能已经发出，继续附件投递会造成重复发送。
        if (!sentMarkersPresent(current, 3)) {
            savePageSource(current, "resume_send_failed");
            return LiepinApplicationResult.failed(
                    "已点击发送简历，但未确认到发送成功标记；请到猎聘“我的沟通”中核对实际发送结果，已保存页面供排查");
        }
        ensureNoVerification(current);
        closeChatWindow(current);
        return LiepinApplicationResult.success("已向“" + posting.getCompanyName() + " - "
                + posting.getJobName() + "”发送猎聘在线简历");
    }

    /** 检查聊天窗口是否出现简历已发送标记，最多轮询 times 次，每次间隔 800ms。 */
    private boolean sentMarkersPresent(Page current, int times) {
        // 新 im-ui 版发送成功后聊天区会出现“这是我的简历，合适的话可以随时联系我～”的简历卡片消息
        String[] markers = {"简历已发送", "已投递简历", "已发送简历", "投递成功",
                "简历发送成功", "发送成功", "这是我的简历", "简历已投递"};
        for (int i = 0; i < times; i++) {
            current.waitForTimeout(800);
            String body = safeBodyText(current);
            for (String marker : markers) {
                if (body.contains(marker)) return true;
            }
        }
        return false;
    }

    private LiepinApplicationResult sendAttachmentResume(Page current,
                                                          LiepinJobPosting posting,
                                                          LiepinResume resume) {
        if (resume.getFilePath() == null || resume.getFilePath().isBlank()) {
            return LiepinApplicationResult.failed(
                    LiepinApplicationStatus.RESUME_NOT_FOUND, "当前简历没有保留PDF或Word原文件");
        }
        Path attachment = Path.of(resume.getFilePath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(attachment)) {
            return LiepinApplicationResult.failed(
                    LiepinApplicationStatus.RESUME_NOT_FOUND, "简历附件文件不存在，请重新发送并保存简历");
        }
        String fileType = defaultIfBlank(resume.getFileType(), "").toLowerCase(Locale.ROOT);
        if (!Set.of("pdf", "doc", "docx").contains(fileType)) {
            return LiepinApplicationResult.failed(
                    LiepinApplicationStatus.UPLOAD_NOT_SUPPORTED, "猎聘附件简历仅支持PDF、DOC或DOCX");
        }

        Locator resumeButton = current.locator(LiepinLocators.SEND_RESUME_BUTTON).first();
        if (visible(resumeButton)) {
            resumeButton.click();
            current.waitForTimeout(300);
        }
        Locator attachmentButton = current.locator(LiepinLocators.ATTACHMENT_BUTTON).first();
        if (visible(attachmentButton)) {
            attachmentButton.click();
            current.waitForTimeout(300);
        }
        Locator input = current.locator(LiepinLocators.ATTACHMENT_FILE_INPUT).first();
        if (input.count() == 0) {
            return LiepinApplicationResult.failed(
                    LiepinApplicationStatus.UPLOAD_NOT_SUPPORTED,
                    "当前猎聘沟通窗口没有附件上传入口，请改用在线简历模式");
        }
        input.setInputFiles(attachment);
        current.waitForTimeout(600);
        // 只点击可用的发送按钮，避免误点聊天输入框的禁用发送键导致 45 秒超时
        boolean sendClicked = false;
        Locator sends = current.locator(LiepinLocators.ATTACHMENT_SEND_BUTTON);
        for (int i = 0; i < sends.count(); i++) {
            Locator one = sends.nth(i);
            try {
                if (one.isVisible() && one.isEnabled()) {
                    one.click();
                    sendClicked = true;
                    break;
                }
            } catch (Exception ignored) {
            }
        }
        if (!sendClicked) {
            savePageSource(current, "attachment_send_failed");
            return LiepinApplicationResult.failed(
                    LiepinApplicationStatus.UPLOAD_NOT_SUPPORTED,
                    "附件已上传但找不到可用的发送按钮，请改用在线简历模式");
        }
        current.waitForTimeout(800);
        ensureNoVerification(current);
        closeChatWindow(current);
        return LiepinApplicationResult.success("已向“" + posting.getCompanyName() + " - "
                + posting.getJobName() + "”发送附件简历“" + resume.getFileName() + "”");
    }

    private Locator firstVisible(Locator... locators) {
        for (Locator locator : locators) {
            if (visible(locator)) return locator;
        }
        return null;
    }

    private String safeBodyText(Page current) {
        try {
            return defaultIfBlank(current.locator("body").innerText(), "");
        } catch (Exception ignored) {
            return "";
        }
    }
    private LiepinApplicationResult tryApplyFromDetail(Page current, LiepinJobPosting posting) {
        if (posting.getJobUrl() == null || posting.getJobUrl().isBlank()) return null;
        log.info("[LiepinJob] 尝试详情页沟通: job=\"{}\", company=\"{}\", url={}",
                posting.getJobName(), posting.getCompanyName(), posting.getJobUrl());
        for (int retry = 0; retry < 3; retry++) {
            try {
                current.navigate(posting.getJobUrl(),
                        new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                current.waitForTimeout(800);
                ensureNoVerification(current);

                if (visible(current.locator(LiepinLocators.CONTINUE_CHAT_BUTTON).first())) {
                    log.info("[LiepinJob] 详情页检测到已联系过: job=\"{}\"", posting.getJobName());
                    return alreadyContacted(posting);
                }
                Locator chatButton = current.locator(LiepinLocators.CHAT_BUTTON).first();
                if (!visible(chatButton)) {
                    return LiepinApplicationResult.needsUserAction(
                            "岗位详情页没有聊一聊按钮，将尝试从搜索列表发起沟通");
                }
                return clickChatAndVerify(current, chatButton, posting);
            } catch (LiepinLoginRequiredException e) {
                throw e;
            } catch (Exception e) {
                if (retry < 2) {
                    log.info("[LiepinJob] 详情页沟通重试 {}/3: jobId={}, error={}",
                            retry + 1, posting.getExternalJobId(), e.getMessage());
                    current.waitForTimeout(500);
                } else {
                    log.warn("[LiepinJob] 详情页沟通3次均失败: jobId={}", posting.getExternalJobId());
                    savePageSource(current, "detail_apply_failed");
                }
            }
        }
        return null;
    }

    private LiepinApplicationResult tryApplyFromSearchCard(Page current, LiepinJobPosting posting) {
        try {
            LiepinSearchRequest fallbackRequest = new LiepinSearchRequest(
                    posting.getJobName(),
                    searchCityForPosting(posting.getCity()),
                    null,
                    null,
                    false,
                    Math.max(20, properties.getMaxSearchResults()));
            current.navigate(searchUrl(fallbackRequest, 0),
                    new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            closeSubscriptionPopup(current);
            waitForJobCards(current);
            current.waitForTimeout(800);
            ensureNoVerification(current);

            Locator card = findMatchingJobCard(current, posting);
            if (card == null) {
                return LiepinApplicationResult.needsUserAction(
                        "详情页和搜索列表都没有找到该岗位，请重新搜索后再确认");
            }
            scrollAndHoverRecruiter(current, card);

            Locator continueButton = card.locator(LiepinLocators.CONTINUE_CHAT_BUTTON).first();
            if (visible(continueButton)) {
                return alreadyContacted(posting);
            }
            Locator chatButton = card.locator(LiepinLocators.CHAT_BUTTON).first();
            if (!visible(chatButton)) {
                return LiepinApplicationResult.needsUserAction(
                        "已找到岗位卡片，但没有显示“聊一聊”按钮，请在电脑浏览器中确认页面状态");
            }
            return clickChatAndVerify(current, chatButton, posting);
        } catch (LiepinLoginRequiredException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[LiepinJob] 搜索列表沟通失败: jobId={}, error={}",
                    posting.getExternalJobId(), e.getMessage());
            return LiepinApplicationResult.needsUserAction(
                    "猎聘页面操作失败，请在电脑浏览器中检查后重试：" + defaultIfBlank(e.getMessage(), "未知错误"));
        }
    }

    private Locator findMatchingJobCard(Page current, LiepinJobPosting posting) {
        Locator cards = current.locator(LiepinLocators.JOB_CARDS);
        int count = cards.count();
        for (int i = 0; i < count; i++) {
            Locator card = cards.nth(i);
            if (cardMatches(card, posting)) return card;
        }
        return null;
    }

    private boolean cardMatches(Locator card, LiepinJobPosting posting) {
        try {
            String cardText = defaultIfBlank(card.innerText(), "");
            String externalId = defaultIfBlank(posting.getExternalJobId(), "");
            if (!externalId.isBlank()) {
                String ext = defaultIfBlank(card.getAttribute("data-tlg-ext"), "");
                String scm = defaultIfBlank(card.getAttribute("data-tlg-scm"), "");
                Object hrefValue = card.locator("a[href]").evaluateAll(
                        "(elements) => elements.map(element => element.href || '').join(' ')");
                String hrefs = hrefValue == null ? "" : hrefValue.toString();
                if (ext.contains(externalId) || scm.contains(externalId) || hrefs.contains(externalId)) {
                    return true;
                }
            }
            boolean sameJob = !defaultIfBlank(posting.getJobName(), "").isBlank()
                    && cardText.contains(posting.getJobName());
            boolean sameCompany = defaultIfBlank(posting.getCompanyName(), "").isBlank()
                    || cardText.contains(posting.getCompanyName());
            return sameJob && sameCompany;
        } catch (Exception e) {
            return false;
        }
    }

    private void scrollAndHoverRecruiter(Page current, Locator card) {
        card.scrollIntoViewIfNeeded();
        current.waitForTimeout(200);
        Locator recruiterArea = card.locator(LiepinLocators.RECRUITER_AREA).first();
        Locator target = visible(recruiterArea) ? recruiterArea : card;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                target.hover(new Locator.HoverOptions().setTimeout(5000));
                current.waitForTimeout(300);
                return;
            } catch (PlaywrightException e) {
                log.debug("[LiepinJob] 第 {} 次招聘者区域悬停失败: {}", attempt, e.getMessage());
                if (attempt < 3) {
                    card.scrollIntoViewIfNeeded();
                    current.waitForTimeout(300);
                }
            }
        }
    }

    private LiepinApplicationResult clickChatAndVerify(Page current,
                                                       Locator chatButton,
                                                       LiepinJobPosting posting) {
        chatButton.scrollIntoViewIfNeeded();
        chatButton.click();
        try {
            current.locator(LiepinLocators.CHAT_HEADER).first().waitFor(
                    new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(3000));
        } catch (PlaywrightException ignored) {
            ensureNoVerification(current);
            return LiepinApplicationResult.needsUserAction(
                    "已点击“聊一聊”，但没有检测到沟通窗口；请确认是否出现登录或安全验证");
        }
        closeChatWindow(current);
        return LiepinApplicationResult.success("已向“" + posting.getCompanyName() + " - " + posting.getJobName()
                + "”发起沟通；招呼语由猎聘 App 中预先配置的默认招呼语发送");
    }

    private String searchCityForPosting(String city) {
        String candidate = defaultIfBlank(city, "全国").strip();
        try {
            cityCode(candidate);
            return candidate;
        } catch (IllegalArgumentException ignored) {
            for (String knownCity : CITY_CODES.keySet()) {
                if (candidate.contains(knownCity)) return knownCity;
            }
            log.debug("[LiepinJob] 岗位地区“{}”无法直接映射城市代码，回落全国搜索", candidate);
            return "全国";
        }
    }
    private LiepinApplicationResult alreadyContacted(LiepinJobPosting posting) {
        return LiepinApplicationResult.success("“" + posting.getCompanyName() + " - " + posting.getJobName()
                + "”显示为“继续聊”，说明此前已经发起过沟通，本次没有重复发送");
    }
    @PreDestroy
    public synchronized void close() {
        try {
            if (context != null) context.close();
        } catch (Exception e) {
            log.warn("[LiepinJob] 关闭浏览器上下文失败: {}", e.getMessage());
        } finally {
            context = null;
            page = null;
        }
        try {
            if (playwright != null) playwright.close();
        } catch (Exception e) {
            log.warn("[LiepinJob] 关闭 Playwright 失败: {}", e.getMessage());
        } finally {
            playwright = null;
        }
    }

    private Page page() {
        if (page != null && !page.isClosed()) return page;
        try {
            Path profile = Path.of(properties.getProfileDirectory()).toAbsolutePath().normalize();
            Files.createDirectories(profile);
            Map<String, String> playwrightEnv = new HashMap<>(System.getenv());
            playwrightEnv.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
            playwright = Playwright.create(new Playwright.CreateOptions().setEnv(playwrightEnv));
            BrowserType.LaunchPersistentContextOptions options = new BrowserType.LaunchPersistentContextOptions()
                    .setHeadless(properties.isHeadless())
                    .setViewportSize(1440, 900)
                    .setArgs(List.of("--start-maximized"));
            Path executable = resolveBrowserExecutable();
            if (executable != null) {
                options.setExecutablePath(executable);
                log.info("[LiepinJob] 使用本机浏览器: {}", executable);
            }
            context = playwright.chromium().launchPersistentContext(profile, options);
            context.setDefaultTimeout(properties.getNavigationTimeoutSeconds() * 1000.0);
            page = context.pages().isEmpty() ? context.newPage() : context.pages().getFirst();
            return page;
        } catch (Exception e) {
            close();
            throw new IllegalStateException("无法启动猎聘浏览器。请确认 Chrome/Edge 未占用同一资料目录。原始错误：" + e.getMessage(), e);
        }
    }

    private String searchUrl(LiepinSearchRequest request, int pageIndex) {
        String cityCode = cityCode(request.city());
        String salary = properties.getDefaultSalaryCode() == null ? "" : properties.getDefaultSalaryCode().trim();
        return properties.getBaseUrl() + "/zhaopin/?city=" + cityCode
                + "&dq=" + cityCode
                + "&salary=" + URLEncoder.encode(salary, StandardCharsets.UTF_8)
                + "&currentPage=" + pageIndex
                + "&key=" + URLEncoder.encode(request.keyword(), StandardCharsets.UTF_8);
    }

    private void captureSearchResponse(Response response,
                                       Map<String, LiepinJobPosting> captured,
                                       String fallbackCity) {
        if (!response.url().contains(SEARCH_API) || response.url().contains("cond-init") || response.status() != 200) {
            return;
        }
        try {
            for (LiepinJobPosting posting : parseSearchResponse(response.text(), fallbackCity)) {
                captured.putIfAbsent(uniqueKey(posting), posting);
            }
        } catch (Exception e) {
            log.debug("[LiepinJob] 跳过无法解析的搜索响应: url={}, error={}", response.url(), e.getMessage());
        }
    }

    List<LiepinJobPosting> parseSearchResponse(String body, String fallbackCity) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode cards = root.path("data").path("data").path("jobCardList");
        if (!cards.isArray()) cards = root.path("data").path("jobCardList");
        if (!cards.isArray()) return List.of();
        List<LiepinJobPosting> postings = new ArrayList<>();
        for (JsonNode card : cards) {
            LiepinJobPosting posting = fromApiCard(card, fallbackCity);
            if (posting != null) postings.add(posting);
        }
        return postings;
    }
    private LiepinJobPosting fromApiCard(JsonNode card, String fallbackCity) {
        JsonNode job = card.path("job");
        JsonNode comp = card.path("comp");
        JsonNode recruiter = card.path("recruiter");
        String jobName = text(job, "title", "jobTitle");
        String link = text(job, "link", "jobLink");
        String externalId = text(job, "jobId");
        if (jobName.isBlank() || (link.isBlank() && externalId.isBlank())) return null;

        LiepinJobPosting posting = new LiepinJobPosting();
        posting.setExternalJobId(externalId);
        posting.setJobName(jobName);
        posting.setCompanyName(text(comp, "compName"));
        posting.setCompanyIndustry(text(comp, "compIndustry"));
        posting.setCompanyScale(text(comp, "compScale"));
        posting.setCity(defaultIfBlank(text(job, "dq", "jobDq"), fallbackCity));
        posting.setSalary(text(job, "salary"));
        posting.setEducation(text(job, "requireEduLevel"));
        posting.setExperience(text(job, "requireWorkYears"));
        posting.setPublishedAt(text(job, "refreshTime"));
        posting.setRecruiterName(text(recruiter, "recruiterName"));
        posting.setRecruiterTitle(text(recruiter, "recruiterTitle"));
        posting.setRecruiterImId(text(recruiter, "imId"));
        posting.setDescription(buildDescription(job, posting));
        posting.setJobUrl(absoluteJobUrl(link, externalId));
        posting.setStatus("CANDIDATE");
        return posting;
    }

    private void mergeDomFallback(Page current,
                                  Map<String, LiepinJobPosting> captured,
                                  String fallbackCity) {
        Locator cards = current.locator(LiepinLocators.JOB_CARDS);
        int count = Math.min(cards.count(), Math.max(20, properties.getMaxSearchResults() * 2));
        for (int i = 0; i < count; i++) {
            LiepinJobPosting posting = readDomCard(cards.nth(i), fallbackCity);
            if (posting != null) captured.putIfAbsent(uniqueKey(posting), posting);
        }
    }

    private LiepinJobPosting readDomCard(Locator card, String fallbackCity) {
        try {
            LiepinJobPosting posting = new LiepinJobPosting();
            posting.setJobName(firstText(card, ".job-title-box, [class*='job-title'], [class*='job-name']"));
            posting.setCompanyName(firstText(card, "[class*='company-name'], [class*='comp-name']"));
            posting.setCity(defaultIfBlank(firstText(card, "[class*='job-dq'], [class*='job-area']"), fallbackCity));
            posting.setSalary(firstText(card, "[class*='job-salary'], [class*='salary']"));
            posting.setDescription(firstText(card, "[class*='job-labels'], [class*='job-card']"));
            Locator link = card.locator("a[href*='/job/'], a[href*='/a/']").first();
            String href = link.count() == 0 ? "" : defaultIfBlank(link.getAttribute("href"), "");
            if (posting.getJobName().isBlank() || href.isBlank()) return null;
            posting.setJobUrl(absoluteJobUrl(href, ""));
            posting.setStatus("CANDIDATE");
            return posting;
        } catch (Exception e) {
            log.debug("[LiepinJob] 跳过无法解析的职位卡片: {}", e.getMessage());
            return null;
        }
    }

    private String buildDescription(JsonNode job, LiepinJobPosting posting) {
        String labels = job.path("labels").isMissingNode() ? "" : job.path("labels").toString();
        return String.join("；", nonBlank(
                posting.getEducation().isBlank() ? "" : "学历：" + posting.getEducation(),
                posting.getExperience().isBlank() ? "" : "经验：" + posting.getExperience(),
                labels.isBlank() ? "" : "标签：" + labels));
    }

    private List<String> nonBlank(String... values) {
        return Arrays.stream(values).filter(value -> value != null && !value.isBlank()).toList();
    }

    private String text(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (!value.isMissingNode() && !value.isNull()) {
                String text = value.asText("").strip();
                if (!text.isBlank()) return text;
            }
        }
        return "";
    }

    private String absoluteJobUrl(String link, String externalId) {
        if (link != null && !link.isBlank()) {
            if (link.startsWith("http")) return link;
            return properties.getBaseUrl() + (link.startsWith("/") ? link : "/" + link);
        }
        return properties.getBaseUrl() + "/job/" + externalId + ".shtml";
    }

    private String uniqueKey(LiepinJobPosting posting) {
        return defaultIfBlank(posting.getExternalJobId(), posting.getJobUrl());
    }

    private void waitForJobCards(Page current) {
        try {
            current.locator(LiepinLocators.JOB_CARDS).first().waitFor(
                    new Locator.WaitForOptions()
                            .setState(WaitForSelectorState.ATTACHED)
                            .setTimeout(properties.getNavigationTimeoutSeconds() * 1000.0));
        } catch (PlaywrightException e) {
            log.debug("[LiepinJob] 等待职位卡片超时，将尝试使用已捕获的接口数据: {}", e.getMessage());
        }
    }

    private void closeSubscriptionPopup(Page current) {
        clickIfVisible(current.locator(LiepinLocators.SUBSCRIBE_CLOSE).first());
    }

    private void closeChatWindow(Page current) {
        clickIfVisible(current.locator(LiepinLocators.CHAT_CLOSE).first());
    }

    private void savePageSource(Page current, String context) {
        try {
            Path dir = Paths.get("./target/logs/page_sources");
            Files.createDirectories(dir);
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            String name = String.format("liepin_%s_%s.html",
                    context.replaceAll("[^a-zA-Z0-9]", "_"), ts);
            Files.write(dir.resolve(name), current.content().getBytes(StandardCharsets.UTF_8));
            log.info("[LiepinJob] page source saved: {}", dir.resolve(name));
        } catch (Exception e) {
            log.debug("[LiepinJob] save page source failed: {}", e.getMessage());
        }
    }

    private void ensureNoVerification(Page current) {
        String body = current.locator("body").innerText();
        if (body.contains("安全验证") || body.contains("请完成验证") || body.contains("验证码")) {
            savePageSource(current, "verification");
            current.bringToFront();
            throw new LiepinLoginRequiredException("猎聘要求安全验证，请在电脑浏览器中手动完成后重试");
        }
    }

    private void clickIfVisible(Locator locator) {
        try {
            if (visible(locator)) locator.click();
        } catch (PlaywrightException e) {
            log.debug("[LiepinJob] 可选按钮点击失败: {}", e.getMessage());
        }
    }

    private boolean visible(Locator locator) {
        try {
            return locator.count() > 0 && locator.isVisible();
        } catch (PlaywrightException e) {
            return false;
        }
    }

    private String firstText(Locator parent, String selector) {
        Locator locator = parent.locator(selector).first();
        if (locator.count() == 0) return "";
        return defaultIfBlank(locator.innerText(), "").strip();
    }

    private Path resolveBrowserExecutable() {
        List<String> candidates = new ArrayList<>();
        if (properties.getBrowserExecutablePath() != null && !properties.getBrowserExecutablePath().isBlank()) {
            candidates.add(properties.getBrowserExecutablePath());
        }
        String programFiles = System.getenv("ProgramFiles");
        String programFilesX86 = System.getenv("ProgramFiles(x86)");
        String localAppData = System.getenv("LOCALAPPDATA");
        if (programFiles != null) {
            candidates.add(Path.of(programFiles, "Google", "Chrome", "Application", "chrome.exe").toString());
            candidates.add(Path.of(programFiles, "Microsoft", "Edge", "Application", "msedge.exe").toString());
        }
        if (programFilesX86 != null) {
            candidates.add(Path.of(programFilesX86, "Google", "Chrome", "Application", "chrome.exe").toString());
            candidates.add(Path.of(programFilesX86, "Microsoft", "Edge", "Application", "msedge.exe").toString());
        }
        if (localAppData != null) {
            candidates.add(Path.of(localAppData, "Google", "Chrome", "Application", "chrome.exe").toString());
        }
        return candidates.stream().map(Path::of).filter(Files::isRegularFile).findFirst().orElse(null);
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("猎聘功能尚未启用，请把 liepin.enabled 设置为 true");
        }
    }

    public static String cityCode(String city) {
        if (city == null || city.isBlank()) throw new IllegalArgumentException("城市不能为空");
        String trimmed = city.replace("市", "").trim();
        if (trimmed.matches("\\d{3,6}")) return trimmed;
        String code = CITY_CODES.get(trimmed);
        if (code == null) {
            throw new IllegalArgumentException("暂不支持城市“" + city + "”，可直接传猎聘城市代码，或使用全国、北京、上海、天津、重庆、广州、深圳、杭州、大连、成都、南京、武汉、苏州、西安");
        }
        return code;
    }

    static boolean salaryMatches(String salary, Integer minSalaryK, Integer maxSalaryK) {
        if ((minSalaryK == null || minSalaryK <= 0) && (maxSalaryK == null || maxSalaryK <= 0)) return true;
        if (salary == null) return true;
        Matcher matcher = SALARY_PATTERN.matcher(salary);
        if (!matcher.find()) return true;
        int low = Integer.parseInt(matcher.group(1));
        int high = Integer.parseInt(matcher.group(2));
        return (minSalaryK == null || high >= minSalaryK) && (maxSalaryK == null || low <= maxSalaryK);
    }

    static boolean isOutsourcing(LiepinJobPosting posting) {
        String source = String.join(" ", defaultIfBlank(posting.getJobName(), ""),
                defaultIfBlank(posting.getCompanyName(), ""), defaultIfBlank(posting.getDescription(), ""));
        return OUTSOURCE_WORDS.stream().anyMatch(source::contains);
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}