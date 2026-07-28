package ykd.ykd.job.browser;

/**
 * 猎聘页面定位器集中定义。页面改版时优先在这里维护。
 */
final class LiepinLocators {
    static final String USER_INFO = "#header-quick-menu-user-info, .header-quick-menu-user-photo";
    static final String LOGIN_ENTRY = "#header-quick-menu-login, a[href*='/login'], button:has-text('登录')";
    static final String QR_SWITCH = ".switch-type-mask-img-box, img[src*='qrcode-btn']";
    static final String PAGINATION_BOX = ".list-pagination-box";
    static final String JOB_CARDS = "div[class*='job-card-pc-container']";
    static final String NEXT_PAGE = "li.ant-pagination-next:not(.ant-pagination-disabled)";
    static final String SUBSCRIBE_CLOSE = "div[class*='subscribe-close-btn']";
    static final String CHAT_BUTTON = "button:has-text('聊一聊'), a:has-text('聊一聊')";
    static final String CONTINUE_CHAT_BUTTON = "button:has-text('继续聊'), a:has-text('继续聊')";
    static final String RECRUITER_AREA = """
            .recruiter-info-box,
            .recruiter-info,
            .hr-info,
            .contact-info,
            [class*='recruiter'],
            [class*='hr-'],
            [class*='contact'],
            .job-card-footer,
            .card-footer
            """;
    static final String CHAT_HEADER = ".__im_basic__header-wrap";
    static final String CHAT_CLOSE = "div.__im_basic__contacts-title svg";
    static final String SEND_RESUME_BUTTON = "button:has-text('发简历'), button:has-text('发送简历'), button:has-text('投递简历'), a:has-text('发简历')";
    static final String ONLINE_RESUME_OPTION = "text=在线简历, text=发送在线简历, button:has-text('在线简历')";
    static final String ONLINE_RESUME_CONFIRM = "button:has-text('确认发送'), button:has-text('立即发送'), button:has-text('发送')";
    static final String ATTACHMENT_BUTTON = "button:has-text('附件简历'), text=附件简历, [class*='file-upload'], [class*='attachment']";
    static final String ATTACHMENT_FILE_INPUT = "input[type='file']";
    static final String ATTACHMENT_SEND_BUTTON = "button:has-text('发送附件'), button:has-text('发送文件'), button:has-text('发送')";
    static final String RESUME_SENT_MARKER = "text=简历已发送, text=已发送简历, text=在线简历, [class*='resume-card']";

    private LiepinLocators() {
    }
}