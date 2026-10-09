package co.ara.onboarding.notification;

import java.util.List;
import java.util.Map;

public record PolicyView(AutoRemindView autoRemind, Map<HorizonKind, List<Integer>> horizons) {}
