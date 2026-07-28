package ykd.ykd.job.browser;

import ykd.ykd.job.model.LiepinApplicationResult;
import ykd.ykd.job.model.LiepinJobPosting;
import ykd.ykd.job.model.LiepinResume;
import ykd.ykd.job.model.LiepinSearchRequest;
import ykd.ykd.job.model.ResumeDeliveryMode;

import java.util.List;
import java.util.function.BooleanSupplier;

public interface LiepinAutomationGateway {
    String openLogin();

    boolean isLoggedIn();

    List<LiepinJobPosting> search(LiepinSearchRequest request, BooleanSupplier cancelled);

    LiepinApplicationResult apply(LiepinJobPosting posting, String greeting);

    LiepinApplicationResult applyAndSendResume(
            LiepinJobPosting posting,
            LiepinResume resume,
            ResumeDeliveryMode mode,
            String greeting);
}