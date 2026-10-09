package co.ara.onboarding.notification;

import java.util.List;

/** A page of the caller's inbox, newest first; {@code nextCursor} is null on the last page. */
public record InboxPage(List<NotificationView> items, long unreadCount, String nextCursor) {}
