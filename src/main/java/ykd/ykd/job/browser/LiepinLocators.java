package ykd.ykd.job.browser;

/**
 * 猎聘页面定位器集中定义。
 *
 * <p>页面结构发生变化时，只需要优先检查和更新这里。</p>
 */
final class LiepinLocators {
    static final String USER_INFO = "#header-quick-menu-user-info, .header-quick-menu-user-photo";
    static final String LOGIN_ENTRY = "#header-quick-menu-login, a[href*='/login'], button:has-text('登录')";
    static final String QR_SWITCH = ".switch-type-mask-img-box, img[src*='qrcode-btn']";
    static final String JOB_CARDS = "div[class*='job-card-pc-container']";
    static final String NEXT_PAGE = "li.ant-pagination-next:not(.ant-pagination-disabled)";
    static final String SUBSCRIBE_CLOSE = "div[class*='subscribe-close-btn']";
    static final String CHAT_BUTTON = "button:has-text('聊一聊'), a:has-text('聊一聊')";
    static final String CHAT_HEADER = ".__im_basic__header-wrap";
    static final String CHAT_CLOSE = "div.__im_basic__contacts-title svg";

    private LiepinLocators() {
    }
}
