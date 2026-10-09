package co.ara.onboarding.notification;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

public record UpdatePolicyRequest(@NotNull @Valid AutoRemindView autoRemind,
                                  @NotNull Map<HorizonKind, List<Integer>> horizons) {}
